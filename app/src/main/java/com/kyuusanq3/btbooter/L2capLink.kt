package com.kyuusanq3.btbooter

import android.util.Log

/** Runs the caller's event loop until [check] returns non-null, or throws on timeout or a dropped link. */
internal interface LinkWaiter {
    fun <T : Any> waitFor(what: String, timeoutMs: Long, check: () -> T?): T
}

internal class L2capChannel(val psm: Int, val localCid: Int, private val link: L2capLink) {
    var remoteCid = -1
        internal set
    internal var localConfigured = false
    internal var remoteConfigured = false
    internal var closed = false

    /** Receives each whole L2CAP frame for this channel. */
    var onData: (ByteArray) -> Unit = {}

    val isOpen: Boolean get() = localConfigured && remoteConfigured && !closed

    fun send(payload: ByteArray) = link.sendFrame(remoteCid, payload)
}

/**
 * L2CAP channels on one ACL link: opens channels to the device, accepts ones it opens to us for
 * registered PSMs, and answers its signaling (configuration, information, echo, disconnect).
 */
internal class L2capLink(
    private val handle: Int,
    private val aclDataLimit: Int,
    private val sendAcl: (ByteArray) -> Unit,
    private val waiter: LinkWaiter,
    private val line: (String, String) -> Unit,
) {
    private val reassembler = AclReassembler()
    private val channels = HashMap<Int, L2capChannel>()
    private val acceptors = HashMap<Int, (L2capChannel) -> Unit>()
    private val pendingAccepts = HashMap<Int, (L2capChannel) -> Unit>()
    private val configRequests = HashMap<Int, L2capChannel>()
    private val replies = HashMap<Int, Signal>()
    private var nextLocalCid = FIRST_DYNAMIC_CID
    private var nextSignalId = 1

    /** Accepts channels the device opens to [psm]; [onOpen] runs once both sides have configured it. */
    fun accept(psm: Int, onOpen: (L2capChannel) -> Unit) {
        acceptors[psm] = onOpen
    }

    fun open(psm: Int, what: String): L2capChannel {
        val channel = newChannel(psm)
        val requestId = sendRequest(L2cap.CONNECTION_REQUEST, le16(psm) + le16(channel.localCid))
        val reply = waiter.waitFor("the $what channel to be accepted", SIGNAL_TIMEOUT_MS) {
            takeReply(requestId) { it.code != L2cap.CONNECTION_RESPONSE || it.data.u16le(4) != L2cap.RESULT_PENDING }
        }
        if (reply.code == L2cap.COMMAND_REJECT) {
            channels.remove(channel.localCid)
            throw HciException("The device rejected the $what channel request.")
        }
        val result = reply.data.u16le(4)
        if (result != L2cap.RESULT_SUCCESS) {
            channels.remove(channel.localCid)
            throw HciException("The device refused the $what channel (L2CAP result 0x%04X).".format(result))
        }
        channel.remoteCid = reply.data.u16le(0)
        configure(channel)
        return waiter.waitFor("the $what channel configuration", SIGNAL_TIMEOUT_MS) {
            if (channel.closed) throw HciException("The device closed the $what channel while setting it up.")
            channel.takeIf { it.isOpen }
        }
    }

    fun close(channel: L2capChannel) {
        if (channel.closed || channel.remoteCid < 0) return
        val requestId = sendRequest(L2cap.DISCONNECTION_REQUEST, le16(channel.remoteCid) + le16(channel.localCid))
        runCatching { waiter.waitFor("a channel to close", CLOSE_TIMEOUT_MS) { takeReply(requestId) } }
        channel.closed = true
        channels.remove(channel.localCid)
    }

    internal fun sendFrame(cid: Int, payload: ByteArray) {
        L2cap.aclPackets(handle, L2cap.frame(cid, payload), aclDataLimit).forEach(sendAcl)
    }

    fun onAcl(packet: ByteArray) {
        val frame = reassembler.add(packet) ?: return
        if (frame.cid == L2cap.CID_SIGNALING) {
            L2cap.parseSignals(frame.payload).forEach(::onSignal)
            return
        }
        val channel = channels[frame.cid]
        if (channel == null) {
            Log.d(TAG, "L2CAP frame on unknown CID 0x%04X: %s".format(frame.cid, frame.payload.toHex()))
        } else {
            channel.onData(frame.payload)
        }
    }

    private fun onSignal(signal: Signal) {
        Log.d(TAG, "L2CAP signal 0x%02X id %d: %s".format(signal.code, signal.id, signal.data.toHex()))
        when (signal.code) {
            L2cap.CONNECTION_REQUEST -> onConnectionRequest(signal)
            L2cap.CONFIGURE_REQUEST -> {
                val channel = if (signal.data.size >= 2) channels[signal.data.u16le(0)] else null
                if (channel == null) {
                    sendSignal(L2cap.COMMAND_REJECT, signal.id, le16(REJECT_INVALID_CID) + signal.data.copyOf(4))
                    return
                }
                sendSignal(L2cap.CONFIGURE_RESPONSE, signal.id, le16(channel.remoteCid) + le16(0) + le16(L2cap.RESULT_SUCCESS))
                channel.remoteConfigured = true
                finishAccept(channel)
            }
            L2cap.CONFIGURE_RESPONSE -> {
                val channel = configRequests.remove(signal.id) ?: return
                val result = if (signal.data.size >= 6) signal.data.u16le(4) else -1
                if (result == L2cap.RESULT_SUCCESS) {
                    channel.localConfigured = true
                    finishAccept(channel)
                } else {
                    line("L2CAP", "the device rejected channel settings (result 0x%04X)".format(result))
                    channel.closed = true
                }
            }
            L2cap.INFORMATION_REQUEST ->
                sendSignal(L2cap.INFORMATION_RESPONSE, signal.id, L2cap.informationResponse(signal.data))
            L2cap.ECHO_REQUEST -> sendSignal(L2cap.ECHO_RESPONSE, signal.id, signal.data)
            L2cap.DISCONNECTION_REQUEST -> {
                sendSignal(L2cap.DISCONNECTION_RESPONSE, signal.id, signal.data)
                if (signal.data.size >= 2) channels.remove(signal.data.u16le(0))?.closed = true
            }
            else -> replies[signal.id] = signal
        }
    }

    private fun onConnectionRequest(signal: Signal) {
        if (signal.data.size < 4) return
        val psm = signal.data.u16le(0)
        val sourceCid = signal.data.u16le(2)
        val onOpen = acceptors[psm]
        if (onOpen == null) {
            line("Note", "the device tried to open a channel back (PSM 0x%04X); refused".format(psm))
            sendSignal(
                L2cap.CONNECTION_RESPONSE,
                signal.id,
                le16(0) + le16(sourceCid) + le16(L2cap.RESULT_PSM_NOT_SUPPORTED) + le16(0),
            )
            return
        }
        val channel = newChannel(psm)
        channel.remoteCid = sourceCid
        sendSignal(
            L2cap.CONNECTION_RESPONSE,
            signal.id,
            le16(channel.localCid) + le16(sourceCid) + le16(L2cap.RESULT_SUCCESS) + le16(0),
        )
        pendingAccepts[channel.localCid] = onOpen
        configure(channel)
    }

    private fun finishAccept(channel: L2capChannel) {
        if (channel.isOpen) pendingAccepts.remove(channel.localCid)?.invoke(channel)
    }

    private fun configure(channel: L2capChannel) {
        configRequests[sendRequest(L2cap.CONFIGURE_REQUEST, le16(channel.remoteCid) + le16(0))] = channel
    }

    private fun newChannel(psm: Int): L2capChannel {
        val channel = L2capChannel(psm, nextLocalCid++, this)
        channels[channel.localCid] = channel
        return channel
    }

    private fun sendRequest(code: Int, data: ByteArray): Int {
        val id = nextSignalId
        nextSignalId = if (nextSignalId >= 0xFF) 1 else nextSignalId + 1
        sendSignal(code, id, data)
        return id
    }

    private fun sendSignal(code: Int, id: Int, data: ByteArray) =
        sendFrame(L2cap.CID_SIGNALING, L2cap.signal(code, id, data))

    private fun takeReply(id: Int, accept: (Signal) -> Boolean = { true }): Signal? {
        val reply = replies.remove(id) ?: return null
        return reply.takeIf(accept)
    }

    private companion object {
        const val TAG = "BtBooter"
        const val FIRST_DYNAMIC_CID = 0x0040
        const val REJECT_INVALID_CID = 0x0002
        const val SIGNAL_TIMEOUT_MS = 8_000L
        const val CLOSE_TIMEOUT_MS = 3_000L
    }
}
