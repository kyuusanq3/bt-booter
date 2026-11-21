package com.kyuusanq3.btbooter

/**
 * HCI command and event packets as a USB Bluetooth controller carries them:
 * commands go out on the control endpoint, events come back on the interrupt endpoint.
 */
internal object HciPackets {
    const val OPCODE_RESET = 0x0C03
    const val OPCODE_READ_LOCAL_NAME = 0x0C14
    const val OPCODE_READ_LOCAL_VERSION = 0x1001
    const val OPCODE_READ_BD_ADDR = 0x1009
    const val OPCODE_INQUIRY = 0x0401
    const val OPCODE_INQUIRY_CANCEL = 0x0402
    const val OPCODE_REMOTE_NAME_REQUEST = 0x0419
    const val OPCODE_SET_EVENT_MASK = 0x0C01
    const val OPCODE_WRITE_INQUIRY_MODE = 0x0C45
    const val OPCODE_WRITE_LOCAL_NAME = 0x0C13
    const val OPCODE_WRITE_SCAN_ENABLE = 0x0C1A

    const val SCAN_ENABLE_INQUIRY_AND_PAGE = 0x03

    const val EVENT_INQUIRY_COMPLETE = 0x01
    const val EVENT_INQUIRY_RESULT = 0x02
    const val EVENT_REMOTE_NAME_COMPLETE = 0x07
    const val EVENT_COMMAND_COMPLETE = 0x0E
    const val EVENT_COMMAND_STATUS = 0x0F
    const val EVENT_INQUIRY_RESULT_WITH_RSSI = 0x22
    const val EVENT_EXTENDED_INQUIRY_RESULT = 0x2F

    const val INQUIRY_MODE_EXTENDED = 0x02

    /** BlueZ's classic mask: inquiry with RSSI and extended results, Secure Simple Pairing, link key events. */
    private const val EVENT_MASK = 0x3DBFF807FFFBFFFFL
    private const val GENERAL_INQUIRY_LAP = 0x9E8B33
    private const val EIR_SHORT_NAME = 0x08
    private const val EIR_COMPLETE_NAME = 0x09

    fun command(opcode: Int, params: ByteArray = ByteArray(0)): ByteArray {
        require(params.size <= 255) { "HCI command parameters are limited to 255 bytes." }
        val packet = ByteArray(3 + params.size)
        packet[0] = (opcode and 0xFF).toByte()
        packet[1] = (opcode shr 8).toByte()
        packet[2] = params.size.toByte()
        params.copyInto(packet, destinationOffset = 3)
        return packet
    }

    /** Total event length once the two-byte header is known, or null if the header is incomplete. */
    fun eventLength(header: ByteArray, size: Int): Int? {
        if (size < 2) return null
        return 2 + (header[1].toInt() and 0xFF)
    }

    /** Return parameters of a Command Complete event for [opcode], starting at the status byte. */
    fun commandCompleteReturn(event: ByteArray, opcode: Int): ByteArray? {
        if (event.size < 5 || event[0].toInt() and 0xFF != EVENT_COMMAND_COMPLETE) return null
        if (u16(event, 3) != opcode) return null
        return event.copyOfRange(5, event.size)
    }

    /** Status of a Command Status event for [opcode]. */
    fun commandStatus(event: ByteArray, opcode: Int): Int? {
        if (event.size < 6 || event[0].toInt() and 0xFF != EVENT_COMMAND_STATUS) return null
        if (u16(event, 4) != opcode) return null
        return event[2].toInt() and 0xFF
    }

    fun parseLocalVersion(returned: ByteArray): LocalVersion {
        requireOk(returned, minSize = 9, what = "Read Local Version")
        return LocalVersion(
            hciVersion = returned[1].toInt() and 0xFF,
            hciRevision = u16(returned, 2),
            lmpVersion = returned[4].toInt() and 0xFF,
            manufacturer = u16(returned, 5),
            lmpSubversion = u16(returned, 7),
        )
    }

    fun parseBdAddr(returned: ByteArray): String {
        requireOk(returned, minSize = 7, what = "Read BD_ADDR")
        return formatAddress(returned, 1)
    }

    fun requireSuccess(returned: ByteArray, what: String) = requireOk(returned, minSize = 1, what = what)

    fun eventCode(event: ByteArray): Int = if (event.isEmpty()) -1 else event[0].toInt() and 0xFF

    fun localNameParams(name: String): ByteArray {
        val encoded = name.toByteArray(Charsets.UTF_8)
        require(encoded.size < 248) { "Bluetooth names are limited to 247 bytes." }
        return encoded.copyOf(248)
    }

    fun setEventMaskParams(): ByteArray = ByteArray(8) { (EVENT_MASK ushr (8 * it)).toByte() }

    /** General inquiry for [lengthUnits] × 1.28 s with no response limit. */
    fun inquiryParams(lengthUnits: Int): ByteArray = byteArrayOf(
        (GENERAL_INQUIRY_LAP and 0xFF).toByte(),
        (GENERAL_INQUIRY_LAP shr 8 and 0xFF).toByte(),
        (GENERAL_INQUIRY_LAP shr 16).toByte(),
        lengthUnits.toByte(),
        0,
    )

    fun remoteNameRequestParams(device: InquiryResponse): ByteArray =
        addressBytes(device.address) + byteArrayOf(
            device.pageScanRepetitionMode.toByte(),
            0,
            (device.clockOffset and 0xFF).toByte(),
            (device.clockOffset shr 8 and 0x7F or 0x80).toByte(),
        )

    /** Devices in an Inquiry Result, Inquiry Result with RSSI, or Extended Inquiry Result event. */
    fun parseInquiryResults(event: ByteArray): List<InquiryResponse> = when (eventCode(event)) {
        EVENT_INQUIRY_RESULT -> groupedInquiryResults(event, reservedBytes = 2, withRssi = false)
        EVENT_INQUIRY_RESULT_WITH_RSSI -> groupedInquiryResults(event, reservedBytes = 1, withRssi = true)
        EVENT_EXTENDED_INQUIRY_RESULT -> extendedInquiryResult(event)
        else -> emptyList()
    }

    fun parseRemoteNameComplete(event: ByteArray): RemoteName? {
        if (eventCode(event) != EVENT_REMOTE_NAME_COMPLETE || event.size < 9) return null
        val name = event.copyOfRange(9, event.size)
        val end = name.indexOf(0).let { if (it < 0) name.size else it }
        return RemoteName(
            address = formatAddress(event, 3),
            status = event[2].toInt() and 0xFF,
            name = String(name, 0, end, Charsets.UTF_8),
        )
    }

    fun majorDeviceClassName(classOfDevice: Int): String = when (classOfDevice shr 8 and 0x1F) {
        1 -> "Computer"
        2 -> "Phone"
        3 -> "Network"
        4 -> "Audio/Video"
        5 -> "Peripheral"
        6 -> "Imaging"
        7 -> "Wearable"
        8 -> "Toy"
        9 -> "Health"
        else -> "Other"
    }

    /** Bluetooth addresses travel little-endian; this prints them most significant byte first. */
    fun formatAddress(bytes: ByteArray, offset: Int): String =
        (5 downTo 0).joinToString(":") { "%02X".format(bytes[offset + it].toInt() and 0xFF) }

    fun addressBytes(address: String): ByteArray {
        val parts = address.split(":")
        require(parts.size == 6) { "Not a Bluetooth address: $address" }
        return ByteArray(6) { parts[5 - it].toInt(16).toByte() }
    }

    private fun groupedInquiryResults(event: ByteArray, reservedBytes: Int, withRssi: Boolean): List<InquiryResponse> {
        if (event.size < 3) return emptyList()
        val count = event[2].toInt() and 0xFF
        val perDevice = 6 + 1 + reservedBytes + 3 + 2 + if (withRssi) 1 else 0
        if (event.size < 3 + count * perDevice) return emptyList()
        val addressAt = 3
        val repetitionAt = addressAt + 6 * count
        val classAt = repetitionAt + count + reservedBytes * count
        val clockAt = classAt + 3 * count
        val rssiAt = clockAt + 2 * count
        return (0 until count).map { i ->
            InquiryResponse(
                address = formatAddress(event, addressAt + 6 * i),
                pageScanRepetitionMode = event[repetitionAt + i].toInt() and 0xFF,
                classOfDevice = u24(event, classAt + 3 * i),
                clockOffset = u16(event, clockAt + 2 * i) and 0x7FFF,
                rssi = if (withRssi) event[rssiAt + i].toInt() else null,
                name = null,
            )
        }
    }

    private fun extendedInquiryResult(event: ByteArray): List<InquiryResponse> {
        val eirAt = 17
        if (event.size < eirAt) return emptyList()
        return listOf(
            InquiryResponse(
                address = formatAddress(event, 3),
                pageScanRepetitionMode = event[9].toInt() and 0xFF,
                classOfDevice = u24(event, 11),
                clockOffset = u16(event, 14) and 0x7FFF,
                rssi = event[16].toInt(),
                name = eirName(event, eirAt),
            ),
        )
    }

    private fun eirName(bytes: ByteArray, start: Int): String? {
        var shortName: String? = null
        var i = start
        while (i < bytes.size) {
            val length = bytes[i].toInt() and 0xFF
            if (length == 0 || i + 1 + length > bytes.size) break
            val type = bytes[i + 1].toInt() and 0xFF
            if (type == EIR_COMPLETE_NAME || type == EIR_SHORT_NAME) {
                val name = String(bytes, i + 2, length - 1, Charsets.UTF_8)
                if (type == EIR_COMPLETE_NAME) return name
                shortName = name
            }
            i += 1 + length
        }
        return shortName
    }

    fun parseLocalName(returned: ByteArray): String {
        requireOk(returned, minSize = 1, what = "Read Local Name")
        val name = returned.copyOfRange(1, returned.size)
        val end = name.indexOf(0).let { if (it < 0) name.size else it }
        return String(name, 0, end, Charsets.UTF_8)
    }

    fun bluetoothVersionName(version: Int): String = when (version) {
        0 -> "1.0b"
        1 -> "1.1"
        2 -> "1.2"
        3 -> "2.0"
        4 -> "2.1"
        5 -> "3.0"
        6 -> "4.0"
        7 -> "4.1"
        8 -> "4.2"
        9 -> "5.0"
        10 -> "5.1"
        11 -> "5.2"
        12 -> "5.3"
        13 -> "5.4"
        14 -> "6.0"
        else -> "unknown"
    }

    fun manufacturerName(id: Int): String = when (id) {
        2 -> "Intel"
        10 -> "Cambridge Silicon Radio (Qualcomm)"
        15 -> "Broadcom"
        29 -> "Qualcomm"
        70 -> "MediaTek"
        93 -> "Realtek"
        else -> "unknown"
    }

    private fun requireOk(returned: ByteArray, minSize: Int, what: String) {
        if (returned.isEmpty()) throw HciException("$what returned nothing.")
        val status = returned[0].toInt() and 0xFF
        if (status != 0) throw HciException("$what failed with HCI status 0x%02X.".format(status))
        if (returned.size < minSize) throw HciException("$what returned only ${returned.size} bytes.")
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u24(bytes: ByteArray, offset: Int): Int =
        u16(bytes, offset) or ((bytes[offset + 2].toInt() and 0xFF) shl 16)
}

internal data class InquiryResponse(
    val address: String,
    val pageScanRepetitionMode: Int,
    val classOfDevice: Int,
    val clockOffset: Int,
    val rssi: Int?,
    val name: String?,
)

internal data class RemoteName(val address: String, val status: Int, val name: String)

internal data class LocalVersion(
    val hciVersion: Int,
    val hciRevision: Int,
    val lmpVersion: Int,
    val manufacturer: Int,
    val lmpSubversion: Int,
)

internal class HciException(message: String) : java.io.IOException(message)
