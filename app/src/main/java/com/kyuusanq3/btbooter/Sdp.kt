package com.kyuusanq3.btbooter

/** Service Discovery Protocol requests and the data-element format its answers use. */
internal object Sdp {
    const val ANDROID_AUTO_UUID = "4de17a00-52cb-11e6-bdf4-0800200c9a66"

    /**
     * Looked up one by one: the T9E's records carry no Browse Group List, so a Public Browse Group
     * search returns nothing.
     */
    val COMMON_SERVICES = intArrayOf(
        0x111E, 0x1108, 0x110B, 0x110A, 0x110C, 0x110E, 0x112E, 0x112F, 0x1132, 0x1133, 0x1101, 0x1200,
    )

    /** The limit Android's own SDP client asks for. */
    private const val MAX_ATTRIBUTE_BYTES = 0x03F0
    private const val PDU_ERROR = 0x01
    private const val PDU_SEARCH_ATTRIBUTE_REQUEST = 0x06
    private const val PDU_SEARCH_ATTRIBUTE_RESPONSE = 0x07
    private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    fun uuid16(value: Int): ByteArray = byteArrayOf(0x19) + be16(value)

    fun uuid128(uuid: String): ByteArray {
        val hex = uuid.replace("-", "")
        require(hex.length == 32) { "Not a 128-bit UUID: $uuid" }
        return byteArrayOf(0x1C) + ByteArray(16) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }

    fun sequence(vararg elements: ByteArray): ByteArray = sequenceOfAll(elements.toList())

    fun sequenceOfAll(elements: List<ByteArray>): ByteArray {
        val content = elements.fold(ByteArray(0)) { all, element -> all + element }
        return if (content.size < 256) {
            byteArrayOf(0x35, content.size.toByte()) + content
        } else {
            byteArrayOf(0x36) + be16(content.size) + content
        }
    }

    fun uint8(value: Int): ByteArray = byteArrayOf(0x08, value.toByte())

    fun uint16(value: Int): ByteArray = byteArrayOf(0x09) + be16(value)

    fun uint32(value: Long): ByteArray = byteArrayOf(0x0A) + be32(value)

    fun text(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size < 256) { "SDP text too long for a one-byte length." }
        return byteArrayOf(0x25, bytes.size.toByte()) + bytes
    }

    fun allAttributes(): ByteArray = sequence(byteArrayOf(0x0A, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte()))

    fun searchAttributeRequest(
        transactionId: Int,
        pattern: ByteArray,
        attributes: ByteArray,
        continuation: ByteArray,
    ): ByteArray {
        val params = pattern + be16(MAX_ATTRIBUTE_BYTES) + attributes + continuation
        return byteArrayOf(PDU_SEARCH_ATTRIBUTE_REQUEST.toByte()) + be16(transactionId) + be16(params.size) + params
    }

    fun parseSearchAttributeResponse(pdu: ByteArray): SdpResponsePart {
        if (pdu.size < 5) throw HciException("SDP answer was only ${pdu.size} bytes.")
        when (pdu[0].toInt() and 0xFF) {
            PDU_ERROR -> {
                val code = if (pdu.size >= 7) pdu.u16be(5) else -1
                throw HciException("The T9E answered the service lookup with SDP error 0x%04X.".format(code))
            }
            PDU_SEARCH_ATTRIBUTE_RESPONSE -> Unit
            else -> throw HciException("Unexpected SDP answer type 0x%02X.".format(pdu[0].toInt() and 0xFF))
        }
        val count = pdu.u16be(5)
        val listsEnd = 7 + count
        if (pdu.size < listsEnd + 1) throw HciException("SDP answer was cut short.")
        val continuationLength = pdu[listsEnd].toInt() and 0xFF
        return SdpResponsePart(
            attributeLists = pdu.copyOfRange(7, listsEnd),
            continuation = pdu.copyOfRange(listsEnd, minOf(pdu.size, listsEnd + 1 + continuationLength)),
        )
    }

    fun parseRecords(attributeLists: ByteArray): List<SdpRecord> {
        if (attributeLists.isEmpty()) return emptyList()
        val top = parseElement(attributeLists, 0).first as? SdpElement.Sequence ?: return emptyList()
        return top.items.filterIsInstance<SdpElement.Sequence>().map { record ->
            val attributes = HashMap<Int, SdpElement>()
            record.items.chunked(2).forEach { pair ->
                val id = pair.getOrNull(0) as? SdpElement.UInt ?: return@forEach
                pair.getOrNull(1)?.let { attributes[id.value.toInt()] = it }
            }
            SdpRecord(attributes)
        }
    }

    fun parseElement(bytes: ByteArray, offset: Int): Pair<SdpElement, Int> {
        val descriptor = bytes[offset].toInt() and 0xFF
        val type = descriptor shr 3
        var pos = offset + 1
        val length = when (descriptor and 0x07) {
            0 -> if (type == 0) 0 else 1
            1 -> 2
            2 -> 4
            3 -> 8
            4 -> 16
            5 -> (bytes[pos].toInt() and 0xFF).also { pos += 1 }
            6 -> bytes.u16be(pos).also { pos += 2 }
            else -> ((bytes.u16be(pos) shl 16) or bytes.u16be(pos + 2)).also { pos += 4 }
        }
        val end = pos + length
        if (end > bytes.size) throw HciException("SDP data element runs past the end of the answer.")
        val element = when (type) {
            0 -> SdpElement.Nil
            1 -> SdpElement.UInt(unsigned(bytes, pos, length), length)
            2 -> SdpElement.SInt(unsigned(bytes, pos, length))
            3 -> SdpElement.Uuid(formatUuid(bytes, pos, length))
            4, 8 -> SdpElement.Text(String(bytes, pos, length, Charsets.UTF_8).trimEnd('\u0000'))
            5 -> SdpElement.Bool(length > 0 && bytes[pos].toInt() != 0)
            6, 7 -> {
                val items = mutableListOf<SdpElement>()
                var i = pos
                while (i < end) {
                    val (item, next) = parseElement(bytes, i)
                    items += item
                    i = next
                }
                SdpElement.Sequence(items)
            }
            else -> SdpElement.Nil
        }
        return element to end
    }

    fun serviceClassName(uuid: String): String = when (uuid) {
        ANDROID_AUTO_UUID -> "Android Auto"
        "1101" -> "Serial port"
        "1105" -> "Object push"
        "1108" -> "Headset"
        "110A" -> "A2DP audio source"
        "110B" -> "A2DP audio sink"
        "110C" -> "AVRCP target"
        "110E", "110F" -> "AVRCP controller"
        "1112" -> "Headset audio gateway"
        "1115" -> "PAN user"
        "1116" -> "PAN access point"
        "111E" -> "Hands-free"
        "111F" -> "Hands-free audio gateway"
        "112E" -> "Phonebook client"
        "112F" -> "Phonebook server"
        "1132" -> "Message access server"
        "1133" -> "Message access client"
        "1200" -> "Device ID"
        "1203" -> "Generic audio"
        else -> uuid
    }

    private fun unsigned(bytes: ByteArray, offset: Int, length: Int): Long {
        var value = 0L
        for (i in offset until offset + length) value = value shl 8 or (bytes[i].toLong() and 0xFF)
        return value
    }

    /** 16- and 32-bit UUIDs, and 128-bit ones on the Bluetooth base, print as short upper-case hex. */
    private fun formatUuid(bytes: ByteArray, offset: Int, length: Int): String {
        val hex = bytes.copyOfRange(offset, offset + length).toHex()
        if (length != 16) return hex.uppercase().trimStart('0').padStart(4, '0')
        val full = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
        return if (full.endsWith(BASE_UUID_SUFFIX)) {
            full.substring(0, 8).uppercase().trimStart('0').padStart(4, '0')
        } else {
            full
        }
    }
}

internal class SdpResponsePart(val attributeLists: ByteArray, val continuation: ByteArray)

internal sealed class SdpElement {
    object Nil : SdpElement()
    /** [size] in bytes tells a 16-bit attribute ID from a 32-bit ID range in attribute lists. */
    data class UInt(val value: Long, val size: Int = 0) : SdpElement()
    data class SInt(val value: Long) : SdpElement()
    data class Uuid(val value: String) : SdpElement()
    data class Text(val value: String) : SdpElement()
    data class Bool(val value: Boolean) : SdpElement()
    data class Sequence(val items: List<SdpElement>) : SdpElement()
}

internal class SdpRecord(val attributes: Map<Int, SdpElement>) {
    val handle: Long? get() = (attributes[ATTR_RECORD_HANDLE] as? SdpElement.UInt)?.value

    val serviceClasses: List<String>
        get() = (attributes[ATTR_SERVICE_CLASSES] as? SdpElement.Sequence)?.items
            ?.filterIsInstance<SdpElement.Uuid>()?.map { it.value }.orEmpty()

    val name: String?
        get() = (attributes[ATTR_SERVICE_NAME] as? SdpElement.Text)?.value?.takeIf { it.isNotBlank() }

    val rfcommChannel: Int? get() = protocolParameter(RFCOMM)

    val l2capPsm: Int? get() = protocolParameter(L2CAP)

    private fun protocolParameter(protocol: String): Int? {
        val stack = attributes[ATTR_PROTOCOLS] as? SdpElement.Sequence ?: return null
        for (layer in stack.items) {
            val items = (layer as? SdpElement.Sequence)?.items ?: continue
            if ((items.firstOrNull() as? SdpElement.Uuid)?.value == protocol) {
                return (items.getOrNull(1) as? SdpElement.UInt)?.value?.toInt()
            }
        }
        return null
    }

    private companion object {
        const val ATTR_RECORD_HANDLE = 0x0000
        const val ATTR_SERVICE_CLASSES = 0x0001
        const val ATTR_PROTOCOLS = 0x0004
        const val ATTR_SERVICE_NAME = 0x0100
        const val L2CAP = "0100"
        const val RFCOMM = "0003"
    }
}
