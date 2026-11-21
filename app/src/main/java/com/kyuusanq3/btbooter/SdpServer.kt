package com.kyuusanq3.btbooter

import android.util.Log

internal class SdpServiceRecord(val handle: Long, val attributes: Map<Int, ByteArray>) {
    val uuids: Set<String> = buildSet {
        for (value in attributes.values) collectUuids(Sdp.parseElement(value, 0).first, this)
    }

    private fun collectUuids(element: SdpElement, into: MutableSet<String>) {
        when (element) {
            is SdpElement.Uuid -> into += element.value
            is SdpElement.Sequence -> element.items.forEach { collectUuids(it, into) }
            else -> Unit
        }
    }
}

/**
 * Answers the device's SDP questions about BT Booter. The T9E looks up the phone's hands-free audio
 * gateway record while setting up hands-free, so that record is served.
 */
internal class SdpServer(private val records: List<SdpServiceRecord>) {
    fun attach(channel: L2capChannel) {
        channel.onData = { request ->
            Log.d(TAG, "SDP server request: ${request.toHex()}")
            val answer = runCatching { respond(request) }.getOrElse { error(request, ERROR_INVALID_SYNTAX) }
            Log.d(TAG, "SDP server answer: ${answer.toHex()}")
            channel.send(answer)
        }
    }

    fun respond(request: ByteArray): ByteArray {
        if (request.size < 5) return error(request, ERROR_INVALID_SYNTAX)
        val transaction = request.u16be(1)
        return when (request[0].toInt() and 0xFF) {
            PDU_SERVICE_SEARCH_REQUEST -> {
                val (pattern, next) = Sdp.parseElement(request, 5)
                val maxCount = request.u16be(next)
                val handles = matching(pattern).take(maxCount).map { be32(it.handle) }
                pdu(
                    PDU_SERVICE_SEARCH_RESPONSE,
                    transaction,
                    be16(handles.size) + be16(handles.size) + handles.fold(ByteArray(0)) { all, h -> all + h } + byteArrayOf(0),
                )
            }
            PDU_SERVICE_ATTRIBUTE_REQUEST -> {
                val record = records.firstOrNull { it.handle == request.u32be(5) }
                    ?: return error(request, ERROR_INVALID_HANDLE)
                val (ids, _) = Sdp.parseElement(request, 11)
                val list = attributeList(record, ids)
                pdu(PDU_SERVICE_ATTRIBUTE_RESPONSE, transaction, be16(list.size) + list + byteArrayOf(0))
            }
            PDU_SERVICE_SEARCH_ATTRIBUTE_REQUEST -> {
                val (pattern, next) = Sdp.parseElement(request, 5)
                val (ids, _) = Sdp.parseElement(request, next + 2)
                val lists = Sdp.sequenceOfAll(matching(pattern).map { attributeList(it, ids) })
                pdu(PDU_SERVICE_SEARCH_ATTRIBUTE_RESPONSE, transaction, be16(lists.size) + lists + byteArrayOf(0))
            }
            else -> error(request, ERROR_INVALID_SYNTAX)
        }
    }

    private fun matching(pattern: SdpElement): List<SdpServiceRecord> {
        val wanted = (pattern as? SdpElement.Sequence)?.items?.filterIsInstance<SdpElement.Uuid>()?.map { it.value }
            ?: return emptyList()
        return records.filter { it.uuids.containsAll(wanted) }
    }

    private fun attributeList(record: SdpServiceRecord, ids: SdpElement): ByteArray {
        val ranges = (ids as? SdpElement.Sequence)?.items?.filterIsInstance<SdpElement.UInt>()?.map {
            if (it.size == 4) (it.value shr 16).toInt()..(it.value and 0xFFFF).toInt() else it.value.toInt()..it.value.toInt()
        }.orEmpty()
        val chosen = record.attributes.keys.sorted().filter { id -> ranges.any { id in it } }
        return Sdp.sequenceOfAll(chosen.map { Sdp.uint16(it) + record.attributes.getValue(it) })
    }

    private fun pdu(id: Int, transaction: Int, params: ByteArray): ByteArray =
        byteArrayOf(id.toByte()) + be16(transaction) + be16(params.size) + params

    private fun error(request: ByteArray, code: Int): ByteArray {
        val transaction = if (request.size >= 3) request.u16be(1) else 0
        return pdu(PDU_ERROR_RESPONSE, transaction, be16(code))
    }

    companion object {
        private const val TAG = "BtBooter"
        private const val PDU_ERROR_RESPONSE = 0x01
        private const val PDU_SERVICE_SEARCH_REQUEST = 0x02
        private const val PDU_SERVICE_SEARCH_RESPONSE = 0x03
        private const val PDU_SERVICE_ATTRIBUTE_REQUEST = 0x04
        private const val PDU_SERVICE_ATTRIBUTE_RESPONSE = 0x05
        private const val PDU_SERVICE_SEARCH_ATTRIBUTE_REQUEST = 0x06
        private const val PDU_SERVICE_SEARCH_ATTRIBUTE_RESPONSE = 0x07
        private const val ERROR_INVALID_HANDLE = 0x0002
        private const val ERROR_INVALID_SYNTAX = 0x0003

        /** RFCOMM channel advertised for the audio gateway; BT Booter refuses connections to it for now. */
        const val AUDIO_GATEWAY_CHANNEL = 3

        /** Hands-free audio gateway record like a phone's: version 1.8, the S21's features (0x002F). */
        fun handsFreeAudioGateway(): SdpServiceRecord = SdpServiceRecord(
            handle = 0x00010001,
            attributes = mapOf(
                0x0000 to Sdp.uint32(0x00010001),
                0x0001 to Sdp.sequence(Sdp.uuid16(0x111F), Sdp.uuid16(0x1203)),
                0x0004 to Sdp.sequence(
                    Sdp.sequence(Sdp.uuid16(0x0100)),
                    Sdp.sequence(Sdp.uuid16(0x0003), Sdp.uint8(AUDIO_GATEWAY_CHANNEL)),
                ),
                0x0005 to Sdp.sequence(Sdp.uuid16(0x1002)),
                0x0009 to Sdp.sequence(Sdp.sequence(Sdp.uuid16(0x111E), Sdp.uint16(0x0108))),
                0x0100 to Sdp.text("Voice Gateway"),
                0x0301 to Sdp.uint8(1),
                0x0311 to Sdp.uint16(0x002F),
            ),
        )
    }
}
