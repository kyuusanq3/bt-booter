package com.kyuusanq3.btbooter

/**
 * The phone's side of hands-free setup (HFP audio gateway): answers the car's AT commands the way the
 * S21 did in the capture, with no call and full signal. No audio.
 */
internal class HandsFreeGateway(private val line: (String, String) -> Unit) {
    var dlc: RfcommMux.Dlc? = null

    /**
     * True once the car has finished hands-free setup. Blink on the T9E stops at AT+CLIP and never
     * sends AT+CMER; either command counts, because that is when it registers the Android Auto channel.
     */
    var serviceLevelConnected = false
        private set

    private val pending = StringBuilder()

    fun onData(bytes: ByteArray) {
        pending.append(String(bytes, Charsets.ISO_8859_1))
        while (true) {
            val end = pending.indexOf("\r")
            if (end < 0) return
            val command = pending.substring(0, end).trim()
            pending.delete(0, end + 1)
            if (command.isNotEmpty()) handle(command)
        }
    }

    private fun handle(command: String) {
        val (results, ok) = answer(command)
        val text = results.joinToString("") { "\r\n$it\r\n" } + if (ok) "\r\nOK\r\n" else "\r\nERROR\r\n"
        dlc?.send(text.toByteArray(Charsets.ISO_8859_1))
        val shown = results.joinToString(" | ").ifEmpty { if (ok) "OK" else "ERROR" }
        line("Hands-free", "$command -> $shown")
    }

    /** Result lines and whether the command succeeded. */
    internal fun answer(command: String): Pair<List<String>, Boolean> {
        val upper = command.uppercase()
        return when {
            upper.startsWith("AT+BRSF=") -> listOf("+BRSF: $GATEWAY_FEATURES") to true
            upper == "AT+CIND=?" -> listOf(INDICATOR_RANGES) to true
            upper == "AT+CIND?" -> listOf(INDICATOR_VALUES) to true
            upper.startsWith("AT+CMER=") || upper.startsWith("AT+CLIP") -> {
                serviceLevelConnected = true
                emptyList<String>() to true
            }
            upper == "AT+CHLD=?" -> listOf("+CHLD: (0,1,1x,2,2x,3)") to true
            upper == "AT+CGMI" -> listOf(MANUFACTURER) to true
            upper == "AT+CGMM" -> listOf(MODEL) to true
            upper == "AT+CGMR" -> listOf(REVISION) to true
            upper == "AT+CSCS=?" -> listOf("+CSCS: (\"UTF-8\",\"IRA\",\"GSM\")") to true
            upper == "AT+COPS?" -> listOf("+COPS: 0,0,\"$OPERATOR\"") to true
            upper == "AT+BTRH?" || upper == "AT+CLCC" || upper == "AT+CNUM" -> emptyList<String>() to true
            ACCEPTED_PREFIXES.any { upper.startsWith(it) } -> emptyList<String>() to true
            else -> emptyList<String>() to false
        }
    }

    private companion object {
        /** The S21's features: 3-way calls, echo cancel, voice recognition, in-band ring, voice tag, reject,
         *  enhanced call status and control, extended errors, codec negotiation, HF indicators. */
        const val GATEWAY_FEATURES = 4079
        const val INDICATOR_RANGES = "+CIND: (\"call\",(0,1)),(\"callsetup\",(0-3)),(\"service\",(0-1))," +
            "(\"signal\",(0-5)),(\"roam\",(0,1)),(\"battchg\",(0-5)),(\"callheld\",(0-2))"
        const val INDICATOR_VALUES = "+CIND: 0,0,1,5,0,5,0"
        const val MANUFACTURER = "BT Booter"
        const val MODEL = "BT Booter"
        const val REVISION = "0.1"
        const val OPERATOR = "BT Booter"
        val ACCEPTED_PREFIXES = listOf(
            "AT+NREC", "AT+CCWA", "AT+CMEE", "AT+VGS", "AT+VGM", "AT+BIA", "AT+COPS=", "AT+CSCS=",
            "AT+BAC", "AT+XAPL", "AT+IPHONEACCEV", "AT+CSRSF",
        )
    }
}
