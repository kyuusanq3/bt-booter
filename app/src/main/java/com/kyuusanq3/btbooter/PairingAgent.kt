package com.kyuusanq3.btbooter

internal interface LinkKeyStore {
    fun get(address: String): ByteArray?
    fun put(address: String, key: ByteArray)
    fun remove(address: String)
}

/**
 * Answers the controller's pairing events for one link: offers a saved key, otherwise runs Secure Simple
 * Pairing as a display-yes/no device and auto-confirms the code, which the user accepts on the other side.
 * Replies go out through [sendCommand] once the current command finishes.
 */
internal class PairingAgent(
    private val linkKeys: LinkKeyStore,
    private val sendCommand: (Int, ByteArray) -> Unit,
    private val line: (String, String) -> Unit,
    private val onProgress: (String) -> Unit,
) {
    var usedSavedKey = false
        private set
    var authenticationStatus: Int? = null
        private set
    var encryptionStatus: Int? = null
        private set

    /** Handles [event] if it is about pairing; returns false otherwise. */
    fun onEvent(event: ByteArray, handle: Int): Boolean {
        when (HciPackets.eventCode(event)) {
            HciLink.EVENT_LINK_KEY_REQUEST -> {
                val address = HciLink.requestAddress(event)
                val key = linkKeys.get(address)
                if (key != null) {
                    usedSavedKey = true
                    sendCommand(HciLink.OPCODE_LINK_KEY_REPLY, HciLink.linkKeyReplyParams(address, key))
                } else {
                    sendCommand(HciLink.OPCODE_LINK_KEY_NEGATIVE_REPLY, HciPackets.addressBytes(address))
                }
            }
            HciLink.EVENT_PIN_CODE_REQUEST -> {
                line("Pairing", "the device asked for a PIN (old-style pairing); sent $LEGACY_PIN")
                sendCommand(
                    HciLink.OPCODE_PIN_CODE_REPLY,
                    HciLink.pinCodeReplyParams(HciLink.requestAddress(event), LEGACY_PIN),
                )
            }
            HciLink.EVENT_IO_CAPABILITY_REQUEST ->
                sendCommand(HciLink.OPCODE_IO_CAPABILITY_REPLY, HciLink.ioCapabilityReplyParams(HciLink.requestAddress(event)))
            HciLink.EVENT_IO_CAPABILITY_RESPONSE -> if (event.size >= 11) {
                line(
                    "Its pairing",
                    "${HciLink.ioCapabilityName(event[8].toInt() and 0xFF)}, " +
                        "requirements 0x%02X".format(event[10].toInt() and 0xFF),
                )
            }
            HciLink.EVENT_USER_CONFIRMATION_REQUEST -> {
                val code = "%06d".format(HciLink.pairingCode(event))
                line("Pairing code", code)
                onProgress("Pairing: accept code $code on the other device.")
                sendCommand(HciLink.OPCODE_USER_CONFIRMATION_REPLY, HciPackets.addressBytes(HciLink.requestAddress(event)))
            }
            HciLink.EVENT_USER_PASSKEY_REQUEST -> {
                line("Pairing", "the device wants a passkey typed here; BT Booter cannot, declined")
                sendCommand(HciLink.OPCODE_USER_PASSKEY_NEGATIVE_REPLY, HciPackets.addressBytes(HciLink.requestAddress(event)))
            }
            HciLink.EVENT_USER_PASSKEY_NOTIFICATION -> {
                val code = "%06d".format(HciLink.pairingCode(event))
                line("Passkey", "$code (type it on the other device)")
                onProgress("Pairing: type $code on the other device.")
            }
            HciLink.EVENT_SIMPLE_PAIRING_COMPLETE -> {
                val status = event.getOrNull(2)?.toInt()?.and(0xFF) ?: -1
                if (status != 0) line("Pairing", "failed: ${HciLink.statusName(status)} (0x%02X)".format(status))
            }
            HciLink.EVENT_LINK_KEY_NOTIFICATION -> HciLink.parseLinkKeyNotification(event)?.let {
                linkKeys.put(it.address, it.key)
                line("Pairing", "key saved (${HciLink.linkKeyTypeName(it.type)})")
            }
            HciLink.EVENT_AUTHENTICATION_COMPLETE -> HciLink.statusAndHandle(event)
                ?.takeIf { it.second == handle }
                ?.let { authenticationStatus = it.first }
            HciLink.EVENT_ENCRYPTION_CHANGE -> HciLink.statusAndHandle(event)
                ?.takeIf { it.second == handle }
                ?.let { encryptionStatus = if (it.first == 0 && event.getOrNull(5)?.toInt() == 0) -1 else it.first }
            else -> return false
        }
        return true
    }

    /** A saved key the device no longer accepts would fail every connect, so it is dropped. */
    fun forgetRejectedKey(address: String) {
        val status = authenticationStatus ?: return
        if (usedSavedKey && (status == STATUS_AUTHENTICATION_FAILURE || status == STATUS_KEY_MISSING)) {
            linkKeys.remove(address)
            line("Pairing", "the saved key was rejected and has been removed; connect again to pair fresh")
        }
    }

    private companion object {
        const val LEGACY_PIN = "0000"
        const val STATUS_AUTHENTICATION_FAILURE = 0x05
        const val STATUS_KEY_MISSING = 0x06
    }
}
