package com.kyuusanq3.btbooter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeTest {
    @Test
    fun sabmOnControlChannelMatchesCapture() {
        assertArrayEquals(bytes(0x03, 0x3F, 0x01, 0x1C), Rfcomm.frame(0, true, Rfcomm.SABM or Rfcomm.PF))
    }

    @Test
    fun parameterNegotiationMatchesPhoneInCapture() {
        val values = Rfcomm.pnValues(2, Rfcomm.CREDIT_FLOW_REQUEST, 256, 7)
        val frame = Rfcomm.frame(0, true, Rfcomm.UIH, Rfcomm.mcc(Rfcomm.MCC_PN, true, values))
        assertEquals("03ef158311" + "02f0000000010007" + "70", frame.toHex())
    }

    @Test
    fun creditFrameParsesBackWithCredits() {
        val parsed = Rfcomm.parse(Rfcomm.frame(2, true, Rfcomm.UIH, credits = 5))
        assertNotNull(parsed)
        assertEquals(2, parsed!!.dlci)
        assertEquals(Rfcomm.UIH, parsed.control)
        assertEquals(5, parsed.credits)
        assertEquals(0, parsed.info.size)
    }

    @Test
    fun longFrameUsesTwoLengthBytesAndParsesBack() {
        val info = ByteArray(200) { it.toByte() }
        val parsed = Rfcomm.parse(Rfcomm.frame(2, true, Rfcomm.UIH, info))!!
        assertArrayEquals(info, parsed.info)
        assertEquals(null, parsed.credits)
    }

    @Test
    fun mscMatchesCapture() {
        assertEquals("e3050b8d", Rfcomm.mcc(Rfcomm.MCC_MSC, true, Rfcomm.mscValues(2, Rfcomm.MODEM_SIGNALS)).toHex())
    }

    @Test
    fun sdpServerAnswersHandsFreeGatewayLookupLikeT9EMakes() {
        val server = SdpServer(listOf(SdpServer.handsFreeAudioGateway()))
        val pattern = Sdp.sequence(bytes(0x1A, 0x00, 0x00, 0x11, 0x1F))
        val answer = server.respond(Sdp.searchAttributeRequest(3, pattern, Sdp.allAttributes(), byteArrayOf(0)))
        assertEquals(0x0003, answer.u16be(1))
        val records = Sdp.parseRecords(Sdp.parseSearchAttributeResponse(answer).attributeLists)
        assertEquals(1, records.size)
        assertEquals(0x00010001L, records[0].handle)
        assertTrue("111F" in records[0].serviceClasses)
        assertEquals(SdpServer.AUDIO_GATEWAY_CHANNEL, records[0].rfcommChannel)
        assertEquals("Voice Gateway", records[0].name)
    }

    @Test
    fun sdpServerReturnsOnlyAskedAttributesAndNothingForOtherServices() {
        val server = SdpServer(listOf(SdpServer.handsFreeAudioGateway()))
        val onlyProtocols = Sdp.sequence(Sdp.uint16(0x0004))
        val answer = server.respond(
            Sdp.searchAttributeRequest(0, Sdp.sequence(Sdp.uuid16(0x111F)), onlyProtocols, byteArrayOf(0)),
        )
        val record = Sdp.parseRecords(Sdp.parseSearchAttributeResponse(answer).attributeLists).single()
        assertEquals(setOf(0x0004), record.attributes.keys)

        val none = server.respond(
            Sdp.searchAttributeRequest(0, Sdp.sequence(Sdp.uuid16(0x112F)), Sdp.allAttributes(), byteArrayOf(0)),
        )
        assertTrue(Sdp.parseRecords(Sdp.parseSearchAttributeResponse(none).attributeLists).isEmpty())
    }

    @Test
    fun gatewayAnswersSetupCommandsLikeThePhone() {
        val gateway = HandsFreeGateway { _, _ -> }
        assertEquals(listOf("+BRSF: 4079") to true, gateway.answer("AT+BRSF=127"))
        assertTrue(gateway.answer("AT+CIND=?").first.single().startsWith("+CIND: (\"call\""))
        assertFalse(gateway.serviceLevelConnected)
        assertEquals(emptyList<String>() to true, gateway.answer("AT+CMER=3,0,0,1"))
        assertTrue(gateway.serviceLevelConnected)
        assertEquals(listOf("+CHLD: (0,1,1x,2,2x,3)") to true, gateway.answer("AT+CHLD=?"))
        assertEquals(emptyList<String>() to false, gateway.answer("AT+WHATEVER"))
    }

    @Test
    fun wirelessSetupAnswersT9EMessagesFromCaptureAndReadsWifiDetails() {
        val sent = mutableListOf<String>()
        val setup = WirelessSetup(send = { sent += it.toHex() }, line = { _, _ -> })
        setup.onData(hex("00140004080110071800202420950120970120a10120a501"))
        assertEquals(listOf("00060002000100070000"), sent)

        val start = hex("001400010a0c3139322e3136382e34332e3110dda7021801")
        setup.onData(start.copyOfRange(0, 9))
        setup.onData(start.copyOfRange(9, start.size))
        assertEquals(WirelessStart("192.168.43.1", 37853, 1), setup.start)
        assertEquals(listOf("00060005" + "0a00" + "1000" + "1800", "00000007"), sent.drop(1))

        setup.onData(
            hex(
                "003100030a0e6361726c65747465725f33363862120831323334353637381a1142323a34303a34453a30443a45333a364520082801",
            ),
        )
        assertEquals(
            WirelessCredentials("carletter_368b", "12345678", "B2:40:4E:0D:E3:6E", 8, 1),
            setup.credentials,
        )
        assertEquals(3, setup.messagesReceived)
    }

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
