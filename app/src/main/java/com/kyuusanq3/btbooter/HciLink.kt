package com.kyuusanq3.btbooter

/** HCI commands and events for a classic Bluetooth link (ACL) to one remote device. */
internal object HciLink {
    const val OPCODE_CREATE_CONNECTION = 0x0405
    const val OPCODE_DISCONNECT = 0x0406
    const val OPCODE_LINK_KEY_REPLY = 0x040B
    const val OPCODE_LINK_KEY_NEGATIVE_REPLY = 0x040C
    const val OPCODE_PIN_CODE_REPLY = 0x040D
    const val OPCODE_AUTHENTICATION_REQUESTED = 0x0411
    const val OPCODE_SET_CONNECTION_ENCRYPTION = 0x0413
    const val OPCODE_READ_REMOTE_FEATURES = 0x041B
    const val OPCODE_READ_REMOTE_VERSION = 0x041D
    const val OPCODE_IO_CAPABILITY_REPLY = 0x042B
    const val OPCODE_USER_CONFIRMATION_REPLY = 0x042C
    const val OPCODE_USER_PASSKEY_NEGATIVE_REPLY = 0x042F
    const val OPCODE_WRITE_CLASS_OF_DEVICE = 0x0C24
    const val OPCODE_WRITE_SIMPLE_PAIRING_MODE = 0x0C56
    const val OPCODE_READ_BUFFER_SIZE = 0x1005

    const val EVENT_CONNECTION_COMPLETE = 0x03
    const val EVENT_DISCONNECTION_COMPLETE = 0x05
    const val EVENT_AUTHENTICATION_COMPLETE = 0x06
    const val EVENT_ENCRYPTION_CHANGE = 0x08
    const val EVENT_REMOTE_FEATURES_COMPLETE = 0x0B
    const val EVENT_REMOTE_VERSION_COMPLETE = 0x0C
    const val EVENT_PIN_CODE_REQUEST = 0x16
    const val EVENT_LINK_KEY_REQUEST = 0x17
    const val EVENT_LINK_KEY_NOTIFICATION = 0x18
    const val EVENT_IO_CAPABILITY_REQUEST = 0x31
    const val EVENT_IO_CAPABILITY_RESPONSE = 0x32
    const val EVENT_USER_CONFIRMATION_REQUEST = 0x33
    const val EVENT_USER_PASSKEY_REQUEST = 0x34
    const val EVENT_SIMPLE_PAIRING_COMPLETE = 0x36
    const val EVENT_USER_PASSKEY_NOTIFICATION = 0x3B

    const val REASON_REMOTE_USER_TERMINATED = 0x13

    /** Smartphone, with telephony, object transfer, capturing, and networking service bits (like a phone). */
    const val CLASS_SMARTPHONE = 0x5A020C

    /** Display with yes/no, so the T9E can show a code to compare. */
    private const val IO_DISPLAY_YES_NO = 0x01

    /** MITM protection with dedicated bonding. */
    private const val AUTH_MITM_DEDICATED_BONDING = 0x03

    /** DM1, DH1, DM3, DH3, DM5, DH5. */
    private const val PACKET_TYPES = 0xCC18

    fun createConnectionParams(address: String, pageScanRepetitionMode: Int, clockOffset: Int?): ByteArray =
        HciPackets.addressBytes(address) +
            le16(PACKET_TYPES) +
            byteArrayOf(pageScanRepetitionMode.toByte(), 0) +
            le16(clockOffset?.let { it and 0x7FFF or 0x8000 } ?: 0) +
            byteArrayOf(1)

    fun disconnectParams(handle: Int, reason: Int): ByteArray = le16(handle) + byteArrayOf(reason.toByte())

    fun classOfDeviceParams(classOfDevice: Int): ByteArray =
        byteArrayOf(classOfDevice.toByte(), (classOfDevice shr 8).toByte(), (classOfDevice shr 16).toByte())

    fun ioCapabilityReplyParams(address: String): ByteArray =
        HciPackets.addressBytes(address) +
            byteArrayOf(IO_DISPLAY_YES_NO.toByte(), 0, AUTH_MITM_DEDICATED_BONDING.toByte())

    fun linkKeyReplyParams(address: String, key: ByteArray): ByteArray {
        require(key.size == LINK_KEY_SIZE) { "Link keys are $LINK_KEY_SIZE bytes." }
        return HciPackets.addressBytes(address) + key
    }

    fun pinCodeReplyParams(address: String, pin: String): ByteArray {
        val digits = pin.toByteArray(Charsets.US_ASCII)
        require(digits.size in 1..16) { "PINs are 1 to 16 characters." }
        return HciPackets.addressBytes(address) + byteArrayOf(digits.size.toByte()) + digits.copyOf(16)
    }

    /** Address carried by pairing request and notification events. */
    fun requestAddress(event: ByteArray): String = HciPackets.formatAddress(event, 2)

    /** Numeric code in User Confirmation Request and User Passkey Notification events. */
    fun pairingCode(event: ByteArray): Int =
        event.u16le(8) or (event.u16le(10) shl 16)

    fun parseLinkKeyNotification(event: ByteArray): LinkKey? {
        if (HciPackets.eventCode(event) != EVENT_LINK_KEY_NOTIFICATION || event.size < 25) return null
        return LinkKey(
            address = requestAddress(event),
            key = event.copyOfRange(8, 8 + LINK_KEY_SIZE),
            type = event[24].toInt() and 0xFF,
        )
    }

    /** Status and handle of Authentication Complete or Encryption Change. */
    fun statusAndHandle(event: ByteArray): Pair<Int, Int>? {
        if (event.size < 5) return null
        return (event[2].toInt() and 0xFF) to (event.u16le(3) and 0x0FFF)
    }

    fun linkKeyTypeName(type: Int): String = when (type) {
        0x00 -> "combination key"
        0x04 -> "unauthenticated (Just Works)"
        0x05 -> "authenticated"
        0x07 -> "unauthenticated, Secure Connections"
        0x08 -> "authenticated, Secure Connections"
        else -> "type 0x%02X".format(type)
    }

    fun ioCapabilityName(capability: Int): String = when (capability) {
        0x00 -> "display only"
        0x01 -> "display with yes/no"
        0x02 -> "keyboard only"
        0x03 -> "no input or output"
        else -> "0x%02X".format(capability)
    }

    private const val LINK_KEY_SIZE = 16

    fun parseConnectionComplete(event: ByteArray): ConnectionComplete? {
        if (HciPackets.eventCode(event) != EVENT_CONNECTION_COMPLETE || event.size < 13) return null
        return ConnectionComplete(
            status = event[2].toInt() and 0xFF,
            handle = event.u16le(3) and 0x0FFF,
            address = HciPackets.formatAddress(event, 5),
            encrypted = event[12].toInt() != 0,
        )
    }

    fun parseDisconnectionComplete(event: ByteArray): Disconnection? {
        if (HciPackets.eventCode(event) != EVENT_DISCONNECTION_COMPLETE || event.size < 6) return null
        return Disconnection(
            status = event[2].toInt() and 0xFF,
            handle = event.u16le(3) and 0x0FFF,
            reason = event[5].toInt() and 0xFF,
        )
    }

    fun parseRemoteVersion(event: ByteArray): RemoteVersion? {
        if (HciPackets.eventCode(event) != EVENT_REMOTE_VERSION_COMPLETE || event.size < 10) return null
        return RemoteVersion(
            status = event[2].toInt() and 0xFF,
            version = event[5].toInt() and 0xFF,
            manufacturer = event.u16le(6),
            subversion = event.u16le(8),
        )
    }

    fun parseRemoteFeatures(event: ByteArray): RemoteFeatures? {
        if (HciPackets.eventCode(event) != EVENT_REMOTE_FEATURES_COMPLETE || event.size < 13) return null
        var features = 0L
        for (i in 7 downTo 0) features = features shl 8 or (event[5 + i].toLong() and 0xFF)
        return RemoteFeatures(status = event[2].toInt() and 0xFF, features = features)
    }

    /** ACL data bytes the controller accepts per packet, from Read Buffer Size. */
    fun parseAclDataLength(returned: ByteArray): Int {
        HciPackets.requireSuccess(returned, "Read Buffer Size")
        if (returned.size < 3) throw HciException("Read Buffer Size returned only ${returned.size} bytes.")
        return returned.u16le(1)
    }

    fun supportsSecureSimplePairing(features: Long): Boolean = features shr 51 and 1L == 1L

    fun statusName(status: Int): String = when (status) {
        0x00 -> "success"
        0x02 -> "unknown connection"
        0x04 -> "page timeout, the device did not answer"
        0x05 -> "authentication failure"
        0x06 -> "PIN or key missing"
        0x08 -> "connection timeout"
        0x0B -> "connection already exists"
        0x0C -> "command disallowed"
        0x0D -> "rejected, limited resources"
        0x0E -> "rejected for security reasons"
        0x0F -> "rejected, unacceptable address"
        0x13 -> "remote side ended the connection"
        0x16 -> "ended by this side"
        0x18 -> "pairing not allowed"
        0x1A -> "unsupported remote feature"
        0x22 -> "LMP response timeout"
        else -> "HCI status"
    }
}

internal data class ConnectionComplete(val status: Int, val handle: Int, val address: String, val encrypted: Boolean)

internal data class Disconnection(val status: Int, val handle: Int, val reason: Int)

internal data class RemoteVersion(val status: Int, val version: Int, val manufacturer: Int, val subversion: Int)

internal data class RemoteFeatures(val status: Int, val features: Long)

internal class LinkKey(val address: String, val key: ByteArray, val type: Int)
