package com.kyuusanq3.btbooter

import java.io.ByteArrayOutputStream

/** L2CAP frames inside HCI ACL data packets, and the signaling commands that open channels. */
internal object L2cap {
    const val CID_SIGNALING = 0x0001
    const val PSM_SDP = 0x0001
    const val PSM_RFCOMM = 0x0003

    const val COMMAND_REJECT = 0x01
    const val CONNECTION_REQUEST = 0x02
    const val CONNECTION_RESPONSE = 0x03
    const val CONFIGURE_REQUEST = 0x04
    const val CONFIGURE_RESPONSE = 0x05
    const val DISCONNECTION_REQUEST = 0x06
    const val DISCONNECTION_RESPONSE = 0x07
    const val ECHO_REQUEST = 0x08
    const val ECHO_RESPONSE = 0x09
    const val INFORMATION_REQUEST = 0x0A
    const val INFORMATION_RESPONSE = 0x0B

    const val RESULT_SUCCESS = 0x0000
    const val RESULT_PENDING = 0x0001
    const val RESULT_PSM_NOT_SUPPORTED = 0x0002

    const val PB_FIRST_FLUSHABLE = 0b10
    const val PB_CONTINUATION = 0b01

    private const val INFO_EXTENDED_FEATURES = 0x0002
    private const val INFO_FIXED_CHANNELS = 0x0003

    fun frame(cid: Int, payload: ByteArray): ByteArray = le16(payload.size) + le16(cid) + payload

    fun signal(code: Int, id: Int, data: ByteArray): ByteArray =
        byteArrayOf(code.toByte(), id.toByte()) + le16(data.size) + data

    /** Splits an L2CAP [frame] into ACL packets of at most [maxAclData] data bytes. */
    fun aclPackets(handle: Int, frame: ByteArray, maxAclData: Int): List<ByteArray> =
        (frame.indices step maxAclData).map { start ->
            val end = minOf(start + maxAclData, frame.size)
            val boundary = if (start == 0) PB_FIRST_FLUSHABLE else PB_CONTINUATION
            le16(handle or (boundary shl 12)) + le16(end - start) + frame.copyOfRange(start, end)
        }

    /** The signaling channel may pack several commands into one frame. */
    fun parseSignals(payload: ByteArray): List<Signal> {
        val signals = mutableListOf<Signal>()
        var i = 0
        while (i + 4 <= payload.size) {
            val length = payload.u16le(i + 2)
            if (i + 4 + length > payload.size) break
            signals += Signal(
                code = payload[i].toInt() and 0xFF,
                id = payload[i + 1].toInt() and 0xFF,
                data = payload.copyOfRange(i + 4, i + 4 + length),
            )
            i += 4 + length
        }
        return signals
    }

    /** Answer to an Information Request: no extended features, only the signaling fixed channel. */
    fun informationResponse(request: ByteArray): ByteArray {
        val type = if (request.size >= 2) request.u16le(0) else 0
        return when (type) {
            INFO_EXTENDED_FEATURES -> le16(type) + le16(RESULT_SUCCESS) + ByteArray(4)
            INFO_FIXED_CHANNELS -> le16(type) + le16(RESULT_SUCCESS) + byteArrayOf(0x02, 0, 0, 0, 0, 0, 0, 0)
            else -> le16(type) + le16(1)
        }
    }
}

internal class Signal(val code: Int, val id: Int, val data: ByteArray)

internal class L2capFrame(val cid: Int, val payload: ByteArray)

/** Joins ACL packets back into whole L2CAP frames (one link only). */
internal class AclReassembler {
    private val pending = ByteArrayOutputStream()

    fun add(packet: ByteArray): L2capFrame? {
        if (packet.size < 4) return null
        val boundary = packet.u16le(0) shr 12 and 0b11
        val length = minOf(packet.u16le(2), packet.size - 4)
        if (boundary != L2cap.PB_CONTINUATION) pending.reset()
        pending.write(packet, 4, length)
        val bytes = pending.toByteArray()
        if (bytes.size < 4) return null
        val frameLength = bytes.u16le(0)
        if (bytes.size < 4 + frameLength) return null
        pending.reset()
        return L2capFrame(cid = bytes.u16le(2), payload = bytes.copyOfRange(4, 4 + frameLength))
    }
}
