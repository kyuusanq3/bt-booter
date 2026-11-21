package com.kyuusanq3.btbooter

import java.io.ByteArrayOutputStream

/** Wi-Fi details the car hands over during Android Auto's wireless setup. */
internal data class WirelessCredentials(
    val ssid: String?,
    val password: String?,
    val bssid: String?,
    val securityMode: Int?,
    val accessPointType: Int?,
)

internal data class WirelessStart(val ipAddress: String?, val port: Int?, val reason: Int?)

/**
 * The phone's side of Android Auto wireless setup over RFCOMM (`wifi_discovery` messages, each framed as
 * 2-byte length, 2-byte type, protobuf body, big-endian). The car asks for a version, says where its
 * Android Auto server listens, then hands over its hotspot details when asked.
 */
internal class WirelessSetup(
    private val send: (ByteArray) -> Unit,
    private val line: (String, String) -> Unit,
) {
    private val pending = ByteArrayOutputStream()

    var start: WirelessStart? = null
        private set
    var credentials: WirelessCredentials? = null
        private set
    var messagesReceived = 0
        private set

    fun onData(bytes: ByteArray) {
        pending.write(bytes)
        while (true) {
            val buffer = pending.toByteArray()
            if (buffer.size < HEADER) return
            val length = buffer.u16be(0)
            if (buffer.size < HEADER + length) return
            val type = buffer.u16be(2)
            val body = buffer.copyOfRange(HEADER, HEADER + length)
            pending.reset()
            pending.write(buffer, HEADER + length, buffer.size - HEADER - length)
            messagesReceived++
            handle(type, body)
        }
    }

    private fun handle(type: Int, body: ByteArray) {
        val fields = Protobuf.parse(body)
        when (type) {
            TYPE_VERSION_REQUEST -> {
                val major = fields.int(1) ?: 1
                val minor = fields.int(2) ?: 7
                line("Android Auto", "version request: $major.$minor, Wi-Fi channels ${fields.ints(4)}")
                // The car reads the reply as fixed bytes, not protobuf: major and minor as big-endian
                // 16-bit values, then a status byte where 0 means success.
                sendMessage(TYPE_VERSION_RESPONSE, be16(major) + be16(minor) + byteArrayOf(0, 0))
                line("Android Auto", "sent version response $major.$minor (success)")
            }
            TYPE_START_REQUEST -> {
                val request = WirelessStart(fields.string(1), fields.int(2), fields.int(3))
                start = request
                line("Android Auto", "start request: server ${request.ipAddress}:${request.port} (reason ${request.reason})")
                sendMessage(
                    TYPE_START_RESPONSE,
                    Protobuf.string(1, "") + Protobuf.varint(2, 0) + Protobuf.varint(3, STATUS_SUCCESS),
                )
                sendMessage(TYPE_INFO_REQUEST, ByteArray(0))
                line("Android Auto", "sent start response (success) and Wi-Fi info request")
            }
            TYPE_INFO_RESPONSE -> {
                val info = WirelessCredentials(
                    ssid = fields.string(1),
                    password = fields.string(2),
                    bssid = fields.string(3),
                    securityMode = fields.int(4),
                    accessPointType = fields.int(5),
                )
                credentials = info
                line(
                    "Android Auto",
                    "Wi-Fi: \"${info.ssid}\" password \"${info.password}\" BSSID ${info.bssid}, " +
                        "security ${info.securityMode}, access point type ${info.accessPointType}",
                )
            }
            else -> line("Android Auto", "message type $type: ${body.toHex()}")
        }
    }

    private fun sendMessage(type: Int, body: ByteArray) = send(be16(body.size) + be16(type) + body)

    internal companion object {
        const val HEADER = 4
        const val TYPE_START_REQUEST = 1
        const val TYPE_VERSION_RESPONSE = 2
        const val TYPE_INFO_RESPONSE = 3
        const val TYPE_VERSION_REQUEST = 4
        const val TYPE_START_RESPONSE = 5
        const val TYPE_CONNECT_STATUS = 6
        const val TYPE_INFO_REQUEST = 7
        const val STATUS_SUCCESS = 0L
    }
}

/** Just enough protobuf wire format for the wireless setup messages. */
internal object Protobuf {
    class Fields(private val values: List<Pair<Int, Any>>) {
        fun int(field: Int): Int? = (values.firstOrNull { it.first == field }?.second as? Long)?.toInt()
        fun ints(field: Int): List<Int> = values.filter { it.first == field }.mapNotNull { (it.second as? Long)?.toInt() }
        fun string(field: Int): String? =
            (values.firstOrNull { it.first == field }?.second as? ByteArray)?.toString(Charsets.UTF_8)
    }

    fun parse(bytes: ByteArray): Fields {
        val values = mutableListOf<Pair<Int, Any>>()
        var pos = 0
        while (pos < bytes.size) {
            val (key, afterKey) = readVarint(bytes, pos)
            pos = afterKey
            val field = (key shr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> readVarint(bytes, pos).let { (value, next) -> values += field to value; pos = next }
                2 -> {
                    val (length, next) = readVarint(bytes, pos)
                    val end = next + length.toInt()
                    if (end > bytes.size) throw HciException("Protobuf field runs past the end.")
                    values += field to bytes.copyOfRange(next, end)
                    pos = end
                }
                1 -> pos += 8
                5 -> pos += 4
                else -> throw HciException("Unsupported protobuf wire type in field $field.")
            }
        }
        return Fields(values)
    }

    fun varint(field: Int, value: Long): ByteArray = encodeVarint((field shl 3).toLong()) + encodeVarint(value)

    fun string(field: Int, value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return encodeVarint((field shl 3 or 2).toLong()) + encodeVarint(bytes.size.toLong()) + bytes
    }

    private fun encodeVarint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var rest = value
        while (true) {
            if (rest and 0x7FL.inv() == 0L) {
                out.write(rest.toInt())
                return out.toByteArray()
            }
            out.write((rest and 0x7F or 0x80).toInt())
            rest = rest ushr 7
        }
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < bytes.size) {
            val b = bytes[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result to pos
            shift += 7
        }
        throw HciException("Protobuf varint runs past the end.")
    }
}
