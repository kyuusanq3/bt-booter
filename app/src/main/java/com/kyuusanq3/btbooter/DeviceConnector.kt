package com.kyuusanq3.btbooter

import android.os.SystemClock
import android.util.Log

internal data class ConnectTarget(
    val address: String,
    val name: String?,
    val pageScanRepetitionMode: Int,
    val clockOffset: Int?,
)

internal data class ConnectResult(val report: String, val error: String?)

/**
 * Opens a link to one device, reads what it says about itself, pairs (or reuses a saved key), encrypts,
 * looks up its services over SDP, then connects hands-free as the phone would and looks up Android Auto
 * again while that is up. Opens Android Auto's RFCOMM channel if the device lists it, then disconnects.
 */
internal class DeviceConnector(
    private val hci: UsbHciTransport,
    linkKeys: LinkKeyStore,
    private val onProgress: (String) -> Unit,
) : LinkWaiter {
    private val report = StringBuilder()
    private val pendingCommands = ArrayDeque<Pair<Int, ByteArray>>()
    private val pairing = PairingAgent(
        linkKeys = linkKeys,
        sendCommand = { opcode, params -> pendingCommands.addLast(opcode to params) },
        line = ::line,
        onProgress = { onProgress(it) },
    )
    private val sdpServer = SdpServer(listOf(SdpServer.handsFreeAudioGateway()))
    private var target: ConnectTarget? = null
    private var aclDataLimit = DEFAULT_ACL_DATA
    private var connection: ConnectionComplete? = null
    private var handle = -1
    private var disconnectReason: Int? = null
    private var remoteVersion: RemoteVersion? = null
    private var remoteFeatures: RemoteFeatures? = null
    private var remoteName: RemoteName? = null
    private var l2cap: L2capLink? = null
    private var handsFreeChannel = DEFAULT_HANDS_FREE_CHANNEL

    fun run(target: ConnectTarget): ConnectResult {
        this.target = target
        line("Started", java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
        hci.eventListener = ::onEvent
        hci.aclListener = { l2cap?.onAcl(it) }
        val error = runCatching {
            try {
                prepareDongle()
                connect(target)
                readRemoteDetails(target)
                optional("pairing") { pairAndEncrypt(target) }
                lookUpServices()
                optional("hands-free") { connectHandsFree() }
            } finally {
                if (handle >= 0 && disconnectReason == null) disconnect()
            }
        }.exceptionOrNull()
        error?.let { Log.e(TAG, "Connect stopped", it) }
        return ConnectResult(report.toString().trimEnd(), error?.let { it.message ?: it.javaClass.simpleName })
    }

    private fun prepareDongle() {
        HciPackets.requireSuccess(hci.command(HciPackets.OPCODE_RESET), "Reset")
        HciPackets.requireSuccess(
            hci.command(HciPackets.OPCODE_SET_EVENT_MASK, HciPackets.setEventMaskParams()),
            "Set Event Mask",
        )
        runCatching { HciLink.parseAclDataLength(hci.command(HciLink.OPCODE_READ_BUFFER_SIZE)) }
            .onSuccess { aclDataLimit = it.coerceIn(MIN_ACL_DATA, MAX_ACL_DATA) }
        HciPackets.requireSuccess(
            hci.command(HciLink.OPCODE_WRITE_SIMPLE_PAIRING_MODE, byteArrayOf(1)),
            "Write Simple Pairing Mode",
        )
        runCatching {
            hci.command(HciLink.OPCODE_WRITE_CLASS_OF_DEVICE, HciLink.classOfDeviceParams(HciLink.CLASS_SMARTPHONE))
        }
        runCatching { hci.command(HciPackets.OPCODE_WRITE_LOCAL_NAME, HciPackets.localNameParams(DONGLE_NAME)) }
    }

    private fun connect(target: ConnectTarget) {
        onProgress("Connecting to ${target.label()}…")
        hci.command(
            HciLink.OPCODE_CREATE_CONNECTION,
            HciLink.createConnectionParams(target.address, target.pageScanRepetitionMode, target.clockOffset),
        )
        val done = waitFor("the link to come up", CONNECT_TIMEOUT_MS) { connection }
        if (done.status != 0) {
            throw HciException("The link failed: ${HciLink.statusName(done.status)} (0x%02X).".format(done.status))
        }
        handle = done.handle
        l2cap = L2capLink(handle, aclDataLimit, hci::sendAcl, this, ::line).also {
            it.accept(L2cap.PSM_SDP) { channel ->
                line("Note", "the device opened an SDP lookup to BT Booter")
                sdpServer.attach(channel)
            }
        }
        line("Link", "up to ${done.address}, handle 0x%03X, %s".format(
            done.handle,
            if (done.encrypted) "encrypted" else "not encrypted",
        ))
    }

    private fun readRemoteDetails(target: ConnectTarget) {
        onProgress("Reading what ${target.label()} reports about itself…")
        optional("remote version") {
            hci.command(HciLink.OPCODE_READ_REMOTE_VERSION, le16(handle))
            val version = waitFor("the remote version", DETAIL_TIMEOUT_MS) { remoteVersion }
            line(
                "Remote chip",
                "Bluetooth ${HciPackets.bluetoothVersionName(version.version)}, " +
                    "${HciPackets.manufacturerName(version.manufacturer)} (id ${version.manufacturer}), " +
                    "subversion 0x%04X".format(version.subversion),
            )
        }
        optional("remote features") {
            hci.command(HciLink.OPCODE_READ_REMOTE_FEATURES, le16(handle))
            val features = waitFor("the remote features", DETAIL_TIMEOUT_MS) { remoteFeatures }.features
            val ssp = if (HciLink.supportsSecureSimplePairing(features)) "yes" else "no"
            line("Features", "0x%016X (Secure Simple Pairing: %s)".format(features, ssp))
        }
        optional("remote name") {
            val lookup = InquiryResponse(
                address = target.address,
                pageScanRepetitionMode = target.pageScanRepetitionMode,
                classOfDevice = 0,
                clockOffset = target.clockOffset ?: 0,
                rssi = null,
                name = null,
            )
            hci.command(HciPackets.OPCODE_REMOTE_NAME_REQUEST, HciPackets.remoteNameRequestParams(lookup))
            val name = waitFor("the remote name", DETAIL_TIMEOUT_MS) { remoteName }
            line("Name", if (name.status == 0) name.name else "not given (${HciLink.statusName(name.status)})")
        }
    }

    private fun pairAndEncrypt(target: ConnectTarget) {
        onProgress("Pairing with ${target.label()}. Accept the request on its screen if one appears.")
        hci.command(HciLink.OPCODE_AUTHENTICATION_REQUESTED, le16(handle))
        val status = waitFor("pairing to finish", PAIRING_TIMEOUT_MS) { pairing.authenticationStatus }
        if (status != 0) {
            pairing.forgetRejectedKey(target.address)
            throw HciException("${HciLink.statusName(status)} (0x%02X)".format(status))
        }
        line("Pairing", if (pairing.usedSavedKey) "done with the saved key" else "done")
        hci.command(HciLink.OPCODE_SET_CONNECTION_ENCRYPTION, le16(handle) + byteArrayOf(1))
        when (val encryption = waitFor("encryption", DETAIL_TIMEOUT_MS) { pairing.encryptionStatus }) {
            0 -> line("Encryption", "on")
            -1 -> line("Encryption", "the device left it off")
            else -> line("Encryption", "failed: ${HciLink.statusName(encryption)} (0x%02X)".format(encryption))
        }
    }

    private fun lookUpServices() {
        onProgress("Asking ${target?.label()} for its Bluetooth services…")
        withSdp { sdp ->
            lookUpAndroidAuto(sdp, "first lookup")
            val seen = HashSet<Long>()
            for (service in Sdp.COMMON_SERVICES) {
                optional("service 0x%04X lookup".format(service)) {
                    sdp.search(Sdp.sequence(Sdp.uuid16(service)))
                        .filter { record -> record.handle?.let(seen::add) ?: true }
                        .forEach { record ->
                            line("Service", describe(record))
                            if (service == HANDS_FREE_SERVICE) record.rfcommChannel?.let { handsFreeChannel = it }
                        }
                }
            }
        }
    }

    private fun connectHandsFree() {
        val link = l2cap ?: return
        onProgress("Connecting hands-free to ${target?.label()} on RFCOMM channel $handsFreeChannel…")
        val rfcommChannel = link.open(L2cap.PSM_RFCOMM, "RFCOMM")
        val mux = RfcommMux(rfcommChannel, this, ::line)
        try {
            mux.start()
            val gateway = HandsFreeGateway(::line)
            val dlc = mux.open(handsFreeChannel, gateway::onData)
            gateway.dlc = dlc
            line("Hands-free", "RFCOMM channel $handsFreeChannel open")
            val connected = runCatching {
                waitFor("hands-free setup", HANDS_FREE_SETUP_TIMEOUT_MS) { gateway.serviceLevelConnected.takeIf { it } }
            }.isSuccess
            line("Hands-free", if (connected) "set up (the device turned on indicator reports)" else "setup did not finish")
            pump(AFTER_SETUP_MS)
            val androidAuto = lookUpAndroidAutoWhileConnected()
            if (androidAuto != null) optional("Android Auto channel") { listenToAndroidAuto(mux, androidAuto) }
            mux.close(dlc)
        } finally {
            runCatching { mux.stop() }
            link.close(rfcommChannel)
        }
    }

    private fun lookUpAndroidAutoWhileConnected(): Int? {
        repeat(ANDROID_AUTO_RETRIES) { attempt ->
            if (attempt > 0) pump(ANDROID_AUTO_RETRY_MS)
            val channel = runCatching {
                withSdp { sdp -> lookUpAndroidAuto(sdp, "with hands-free up, try ${attempt + 1}") }
            }.onFailure { line("Skipped", "Android Auto lookup: ${it.message}") }.getOrNull()
            if (channel != null) return channel
        }
        return null
    }

    private fun listenToAndroidAuto(mux: RfcommMux, channel: Int) {
        onProgress("Opening Android Auto on RFCOMM channel $channel…")
        var opened: RfcommMux.Dlc? = null
        val early = ArrayDeque<ByteArray>()
        val setup = WirelessSetup(send = { opened?.send(it) ?: early.addLast(it) }, line = ::line)
        val dlc = mux.open(channel) { data ->
            Log.d(TAG, "Android Auto data: ${data.toHex()}")
            setup.onData(data)
        }
        opened = dlc
        while (early.isNotEmpty()) dlc.send(early.removeFirst())
        line("Android Auto", "RFCOMM channel $channel open, waiting for the car")
        val got = runCatching {
            waitFor("the car's Wi-Fi details", ANDROID_AUTO_LISTEN_MS) { setup.credentials }
        }.isSuccess
        if (!got) {
            line(
                "Android Auto",
                if (setup.messagesReceived == 0) "nothing received in ${ANDROID_AUTO_LISTEN_MS / 1000} s"
                else "no Wi-Fi details within ${ANDROID_AUTO_LISTEN_MS / 1000} s",
            )
        }
        pump(AFTER_CREDENTIALS_MS)
        mux.close(dlc)
    }

    private fun <T> withSdp(block: (SdpClient) -> T): T {
        val link = l2cap ?: throw HciException("No link.")
        val channel = link.open(L2cap.PSM_SDP, "SDP")
        try {
            return block(SdpClient(channel, this))
        } finally {
            link.close(channel)
        }
    }

    private fun lookUpAndroidAuto(sdp: SdpClient, attempt: String): Int? {
        val record = sdp.search(Sdp.sequence(Sdp.uuid128(Sdp.ANDROID_AUTO_UUID))).firstOrNull { it.rfcommChannel != null }
        val found = record?.let { "RFCOMM channel ${it.rfcommChannel}" + (it.name?.let { name -> " (\"$name\")" } ?: "") }
        line("Android Auto", "${found ?: "not found"} [$attempt]")
        return record?.rfcommChannel
    }

    private fun describe(record: SdpRecord): String {
        val where = record.rfcommChannel?.let { "RFCOMM $it" }
            ?: record.l2capPsm?.let { "PSM 0x%04X".format(it) }
            ?: "-"
        val classes = record.serviceClasses.joinToString(", ") { Sdp.serviceClassName(it) }.ifEmpty { "?" }
        return "$where: $classes" + (record.name?.let { " (\"$it\")" } ?: "")
    }

    private fun disconnect() {
        onProgress("Disconnecting…")
        runCatching {
            hci.command(HciLink.OPCODE_DISCONNECT, HciLink.disconnectParams(handle, HciLink.REASON_REMOTE_USER_TERMINATED))
            waitFor("the link to close", DETAIL_TIMEOUT_MS) { disconnectReason }
        }.onSuccess { line("Link", "closed") }
            .onFailure { line("Link", "did not close cleanly: ${it.message}") }
    }

    private fun onEvent(event: ByteArray) {
        Log.d(TAG, "Event: ${event.toHex()}")
        if (pairing.onEvent(event, handle)) return
        when (HciPackets.eventCode(event)) {
            HciLink.EVENT_CONNECTION_COMPLETE -> HciLink.parseConnectionComplete(event)
                ?.takeIf { it.address == target?.address }
                ?.let { connection = it }
            HciLink.EVENT_DISCONNECTION_COMPLETE -> HciLink.parseDisconnectionComplete(event)
                ?.takeIf { it.handle == handle && it.status == 0 }
                ?.let { disconnectReason = it.reason }
            HciLink.EVENT_REMOTE_VERSION_COMPLETE -> remoteVersion = HciLink.parseRemoteVersion(event)
            HciLink.EVENT_REMOTE_FEATURES_COMPLETE -> remoteFeatures = HciLink.parseRemoteFeatures(event)
            HciPackets.EVENT_REMOTE_NAME_COMPLETE -> remoteName = HciPackets.parseRemoteNameComplete(event)
        }
    }

    /** Keeps answering the device for [ms] (hands-free commands, lookups it makes of BT Booter). */
    private fun pump(ms: Long) {
        runCatching { waitFor<Unit>("a pause", ms) { null } }.onFailure { if (disconnectReason != null) throw it }
    }

    override fun <T : Any> waitFor(what: String, timeoutMs: Long, check: () -> T?): T {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            sendPendingCommands()
            check()?.let { return it }
            disconnectReason?.let {
                throw HciException("The device dropped the link: ${HciLink.statusName(it)} (0x%02X).".format(it))
            }
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) throw HciException("Timed out waiting for $what.")
            hci.poll(minOf(remaining, POLL_MS))
        }
    }

    private fun sendPendingCommands() {
        while (true) {
            val (opcode, params) = pendingCommands.removeFirstOrNull() ?: return
            runCatching { hci.command(opcode, params) }
                .onFailure { Log.w(TAG, "Command 0x%04X failed: %s".format(opcode, it.message)) }
        }
    }

    private fun optional(what: String, block: () -> Unit) {
        runCatching(block).onFailure {
            if (disconnectReason != null) throw it
            line("Skipped", "$what: ${it.message}")
        }
    }

    private fun line(label: String, value: String) {
        val text = "%-14s%s".format("$label:", value)
        Log.i(TAG, text)
        report.appendLine(text)
    }

    private fun ConnectTarget.label(): String = name ?: address

    private companion object {
        const val TAG = "BtBooter"
        const val DONGLE_NAME = "BT Booter"
        const val HANDS_FREE_SERVICE = 0x111E
        const val DEFAULT_HANDS_FREE_CHANNEL = 1
        const val PAIRING_TIMEOUT_MS = 60_000L
        const val DEFAULT_ACL_DATA = 310
        const val MIN_ACL_DATA = 27
        const val MAX_ACL_DATA = 1_021
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val DETAIL_TIMEOUT_MS = 6_000L
        const val HANDS_FREE_SETUP_TIMEOUT_MS = 20_000L
        const val AFTER_SETUP_MS = 3_000L
        const val ANDROID_AUTO_RETRIES = 3
        const val ANDROID_AUTO_RETRY_MS = 5_000L
        const val ANDROID_AUTO_LISTEN_MS = 45_000L
        const val AFTER_CREDENTIALS_MS = 5_000L
        const val POLL_MS = 250L
    }
}
