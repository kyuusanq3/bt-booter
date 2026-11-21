package com.kyuusanq3.btbooter

import android.util.Log

/**
 * RFCOMM multiplexer over one L2CAP channel, with BT Booter as the initiator (the phone's role).
 * Opens data channels with credit-based flow control; refuses channels the device tries to open.
 * Nothing here blocks inside a frame callback: outgoing data waits in a queue until credits arrive.
 */
internal class RfcommMux(
    private val channel: L2capChannel,
    private val waiter: LinkWaiter,
    private val line: (String, String) -> Unit,
) {
    inner class Dlc(val dlci: Int, private val onData: (ByteArray) -> Unit) {
        val serverChannel: Int get() = dlci shr 1
        internal var connected = false
        internal var closing = false
        internal var refused: String? = null
        internal var sendCredits = 0
        internal var creditFlow = true
        private val outgoing = ArrayDeque<ByteArray>()

        fun send(data: ByteArray) {
            outgoing.addLast(data)
            flush()
        }

        internal fun flush() {
            while (outgoing.isNotEmpty() && connected && (!creditFlow || sendCredits > 0)) {
                if (creditFlow) sendCredits--
                channel.send(Rfcomm.frame(dlci, true, Rfcomm.UIH, outgoing.removeFirst()))
            }
        }

        internal fun receive(info: ByteArray) {
            if (info.isEmpty()) return
            onData(info)
            if (creditFlow) channel.send(Rfcomm.frame(dlci, true, Rfcomm.UIH, credits = 1))
        }
    }

    private val dlcs = HashMap<Int, Dlc>()
    private val parameterReplies = HashMap<Int, ByteArray>()
    private var muxUp = false
    private var muxRefused = false
    private var frameSize = FRAME_SIZE

    init {
        channel.onData = ::onFrame
    }

    fun start() {
        channel.send(Rfcomm.frame(0, true, Rfcomm.SABM or Rfcomm.PF))
        waiter.waitFor("RFCOMM to start", TIMEOUT_MS) {
            if (muxRefused) throw HciException("The device refused RFCOMM.")
            muxUp.takeIf { it }
        }
    }

    fun open(serverChannel: Int, onData: (ByteArray) -> Unit): Dlc {
        val dlci = serverChannel shl 1
        val dlc = Dlc(dlci, onData)
        dlcs[dlci] = dlc
        sendMcc(Rfcomm.MCC_PN, true, Rfcomm.pnValues(dlci, Rfcomm.CREDIT_FLOW_REQUEST, frameSize, INITIAL_CREDITS))
        val pn = waiter.waitFor("RFCOMM channel $serverChannel settings", TIMEOUT_MS) { parameterReplies.remove(dlci) }
        dlc.creditFlow = pn.size >= 8 && (pn[1].toInt() and 0xFF) == Rfcomm.CREDIT_FLOW_ACCEPT
        if (dlc.creditFlow) dlc.sendCredits = pn[7].toInt() and 0xFF
        if (pn.size >= 6) frameSize = minOf(frameSize, pn.u16le(4))
        channel.send(Rfcomm.frame(dlci, true, Rfcomm.SABM or Rfcomm.PF))
        waiter.waitFor("RFCOMM channel $serverChannel to open", TIMEOUT_MS) {
            dlc.refused?.let { throw HciException(it) }
            dlc.connected.takeIf { it }
        }
        sendMcc(Rfcomm.MCC_MSC, true, Rfcomm.mscValues(dlci, Rfcomm.MODEM_SIGNALS))
        return dlc
    }

    fun close(dlc: Dlc) {
        if (!dlc.connected) return
        dlc.closing = true
        channel.send(Rfcomm.frame(dlc.dlci, true, Rfcomm.DISC or Rfcomm.PF))
        runCatching { waiter.waitFor("RFCOMM channel ${dlc.serverChannel} to close", CLOSE_TIMEOUT_MS) { (!dlc.connected).takeIf { it } } }
        dlcs.remove(dlc.dlci)
    }

    fun stop() {
        if (!muxUp) return
        dlcs.values.toList().forEach(::close)
        channel.send(Rfcomm.frame(0, true, Rfcomm.DISC or Rfcomm.PF))
        runCatching { waiter.waitFor("RFCOMM to stop", CLOSE_TIMEOUT_MS) { (!muxUp).takeIf { it } } }
    }

    private fun onFrame(bytes: ByteArray) {
        val frame = Rfcomm.parse(bytes) ?: run {
            Log.d(TAG, "Bad RFCOMM frame: ${bytes.toHex()}")
            return
        }
        val dlc = dlcs[frame.dlci]
        when (frame.control) {
            Rfcomm.UA -> when {
                frame.dlci == 0 -> muxUp = !muxUp
                dlc != null -> dlc.connected = !dlc.closing
            }
            Rfcomm.DM -> when {
                frame.dlci == 0 -> muxRefused = true
                dlc != null -> {
                    dlc.connected = false
                    dlc.refused = "The device refused RFCOMM channel ${dlc.serverChannel}."
                }
            }
            Rfcomm.SABM -> {
                line("Note", "the device tried to open RFCOMM channel ${frame.dlci shr 1} back; refused")
                channel.send(Rfcomm.frame(frame.dlci, false, Rfcomm.DM or Rfcomm.PF))
            }
            Rfcomm.DISC -> {
                channel.send(Rfcomm.frame(frame.dlci, false, Rfcomm.UA or Rfcomm.PF))
                if (frame.dlci == 0) muxUp = false
                dlc?.let {
                    it.connected = false
                    it.refused = "The device closed RFCOMM channel ${it.serverChannel}."
                    line("RFCOMM", "the device closed channel ${it.serverChannel}")
                }
            }
            Rfcomm.UIH -> if (frame.dlci == 0) {
                onMcc(frame.info)
            } else if (dlc != null) {
                frame.credits?.let { dlc.sendCredits += it }
                dlc.receive(frame.info)
                dlc.flush()
            }
        }
    }

    private fun onMcc(info: ByteArray) {
        val mcc = Rfcomm.parseMcc(info) ?: return
        if (!mcc.command) {
            if (mcc.type == Rfcomm.MCC_PN && mcc.values.isNotEmpty()) {
                parameterReplies[mcc.values[0].toInt() and 0x3F] = mcc.values
            }
            return
        }
        when (mcc.type) {
            Rfcomm.MCC_MSC, Rfcomm.MCC_RPN, Rfcomm.MCC_RLS, Rfcomm.MCC_TEST,
            Rfcomm.MCC_FCON, Rfcomm.MCC_FCOFF -> sendMcc(mcc.type, false, mcc.values)
            Rfcomm.MCC_PN -> if (mcc.values.size >= 8) {
                val requested = mcc.values.u16le(4)
                sendMcc(
                    Rfcomm.MCC_PN,
                    false,
                    Rfcomm.pnValues(mcc.values[0].toInt() and 0x3F, Rfcomm.CREDIT_FLOW_ACCEPT, requested, INITIAL_CREDITS),
                )
            }
            else -> sendMcc(Rfcomm.MCC_NSC, false, byteArrayOf(info[0]))
        }
    }

    private fun sendMcc(type: Int, command: Boolean, values: ByteArray) =
        channel.send(Rfcomm.frame(0, true, Rfcomm.UIH, Rfcomm.mcc(type, command, values)))

    private companion object {
        const val TAG = "BtBooter"
        const val FRAME_SIZE = 256
        const val INITIAL_CREDITS = 7
        const val TIMEOUT_MS = 8_000L
        const val CLOSE_TIMEOUT_MS = 3_000L
    }
}
