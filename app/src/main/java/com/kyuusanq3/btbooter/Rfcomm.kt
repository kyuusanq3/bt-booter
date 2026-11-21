package com.kyuusanq3.btbooter

/** RFCOMM (TS 07.10) frames and multiplexer control commands, as Bluetooth uses them. */
internal object Rfcomm {
    const val SABM = 0x2F
    const val UA = 0x63
    const val DM = 0x0F
    const val DISC = 0x43
    const val UIH = 0xEF
    const val PF = 0x10

    const val MCC_PN = 0x20
    const val MCC_MSC = 0x38
    const val MCC_RPN = 0x24
    const val MCC_RLS = 0x14
    const val MCC_TEST = 0x08
    const val MCC_FCON = 0x28
    const val MCC_FCOFF = 0x18
    const val MCC_NSC = 0x04

    /** Parameter negotiation flow values: ask for, and accept, credit-based flow control. */
    const val CREDIT_FLOW_REQUEST = 0xF0
    const val CREDIT_FLOW_ACCEPT = 0xE0

    /** Modem signals RTC, RTR, DV with the EA bit, as phones send them. */
    const val MODEM_SIGNALS = 0x8D

    private val crcTable = IntArray(256) { index ->
        var crc = index
        repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xE0 else crc ushr 1 }
        crc
    }

    /**
     * One frame. [command] sets the C/R bit; [credits] (UIH only) sets P/F and adds a credit byte.
     * UIH frames check address and control only; other frames also check the length.
     */
    fun frame(dlci: Int, command: Boolean, control: Int, info: ByteArray = ByteArray(0), credits: Int? = null): ByteArray {
        val address = (dlci shl 2) or (if (command) 0x02 else 0) or 0x01
        val fullControl = if (credits != null) control or PF else control
        val length = if (info.size <= 127) {
            byteArrayOf((info.size shl 1 or 1).toByte())
        } else {
            byteArrayOf((info.size shl 1 and 0xFE).toByte(), (info.size shr 7).toByte())
        }
        val header = byteArrayOf(address.toByte(), fullControl.toByte()) + length
        val checked = if (control == UIH) header.copyOf(2) else header
        val creditByte = credits?.let { byteArrayOf(it.toByte()) } ?: ByteArray(0)
        return header + creditByte + info + byteArrayOf(fcs(checked))
    }

    fun parse(bytes: ByteArray): RfcommFrame? {
        if (bytes.size < 4) return null
        val address = bytes[0].toInt() and 0xFF
        val control = bytes[1].toInt() and 0xFF
        var pos = 2
        var length = (bytes[pos].toInt() and 0xFF) shr 1
        if (bytes[pos].toInt() and 1 == 0) {
            length = length or ((bytes[pos + 1].toInt() and 0xFF) shl 7)
            pos += 2
        } else {
            pos += 1
        }
        val dlci = address shr 2
        val kind = control and PF.inv()
        val pollFinal = control and PF != 0
        var credits: Int? = null
        if (kind == UIH && pollFinal && dlci != 0) {
            credits = bytes[pos].toInt() and 0xFF
            pos += 1
        }
        if (pos + length > bytes.size) return null
        return RfcommFrame(
            dlci = dlci,
            command = address and 0x02 != 0,
            control = kind,
            pollFinal = pollFinal,
            credits = credits,
            info = bytes.copyOfRange(pos, pos + length),
        )
    }

    fun mcc(type: Int, command: Boolean, values: ByteArray): ByteArray =
        byteArrayOf(((type shl 2) or (if (command) 0x02 else 0) or 0x01).toByte(), (values.size shl 1 or 1).toByte()) + values

    fun parseMcc(info: ByteArray): Mcc? {
        if (info.size < 2) return null
        val header = info[0].toInt() and 0xFF
        val length = (info[1].toInt() and 0xFF) shr 1
        if (2 + length > info.size) return null
        return Mcc(type = header shr 2, command = header and 0x02 != 0, values = info.copyOfRange(2, 2 + length))
    }

    fun pnValues(dlci: Int, flow: Int, frameSize: Int, credits: Int): ByteArray =
        byteArrayOf(dlci.toByte(), flow.toByte(), 0, 0) + le16(frameSize) + byteArrayOf(0, credits.toByte())

    fun mscValues(dlci: Int, signals: Int): ByteArray = byteArrayOf(((dlci shl 2) or 0x03).toByte(), signals.toByte())

    fun fcs(bytes: ByteArray): Byte {
        var crc = 0xFF
        for (b in bytes) crc = crcTable[crc xor (b.toInt() and 0xFF)]
        return (0xFF - crc).toByte()
    }
}

internal class RfcommFrame(
    val dlci: Int,
    val command: Boolean,
    val control: Int,
    val pollFinal: Boolean,
    val credits: Int?,
    val info: ByteArray,
)

internal class Mcc(val type: Int, val command: Boolean, val values: ByteArray)
