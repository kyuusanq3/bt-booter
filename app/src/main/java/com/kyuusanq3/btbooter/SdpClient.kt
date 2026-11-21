package com.kyuusanq3.btbooter

import android.util.Log
import java.io.ByteArrayOutputStream

/** Service Search Attribute requests over one open SDP channel, following continuation states. */
internal class SdpClient(private val channel: L2capChannel, private val waiter: LinkWaiter) {
    private val answers = ArrayDeque<ByteArray>()
    private var transaction = 0

    init {
        channel.onData = { answers.addLast(it) }
    }

    fun search(pattern: ByteArray): List<SdpRecord> {
        val lists = ByteArrayOutputStream()
        var continuation = byteArrayOf(0)
        repeat(MAX_PARTS) {
            val request = Sdp.searchAttributeRequest(transaction, pattern, Sdp.allAttributes(), continuation)
            transaction = transaction + 1 and 0xFFFF
            Log.d(TAG, "SDP request: ${request.toHex()}")
            channel.send(request)
            val answer = waiter.waitFor("the SDP answer", ANSWER_TIMEOUT_MS) { answers.removeFirstOrNull() }
            Log.d(TAG, "SDP answer: ${answer.toHex()}")
            val part = Sdp.parseSearchAttributeResponse(answer)
            lists.write(part.attributeLists)
            continuation = part.continuation
            if (continuation.size <= 1) return Sdp.parseRecords(lists.toByteArray())
        }
        throw HciException("The SDP answer did not finish after $MAX_PARTS parts.")
    }

    private companion object {
        const val TAG = "BtBooter"
        const val MAX_PARTS = 16
        const val ANSWER_TIMEOUT_MS = 8_000L
    }
}
