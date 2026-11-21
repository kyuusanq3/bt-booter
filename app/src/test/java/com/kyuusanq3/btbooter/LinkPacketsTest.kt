package com.kyuusanq3.btbooter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPacketsTest {
    @Test
    fun createConnectionCarriesAddressPacketTypesAndValidClockOffset() {
        assertArrayEquals(
            bytes(0x0C, 0x73, 0x8E, 0xAD, 0x9E, 0x18, 0x18, 0xCC, 0x01, 0x00, 0x34, 0x92, 0x01),
            HciLink.createConnectionParams("18:9E:AD:8E:73:0C", 1, 0x1234),
        )
    }

    @Test
    fun connectionCompleteParsesHandleAndAddress() {
        val event = bytes(0x03, 0x0B, 0x00, 0x0B, 0x00, 0x0C, 0x73, 0x8E, 0xAD, 0x9E, 0x18, 0x01, 0x00)
        assertEquals(
            ConnectionComplete(status = 0, handle = 0x000B, address = "18:9E:AD:8E:73:0C", encrypted = false),
            HciLink.parseConnectionComplete(event),
        )
    }

    @Test
    fun l2capFrameIsSplitToAclLimitAndJoinedBack() {
        val payload = ByteArray(40) { it.toByte() }
        val packets = L2cap.aclPackets(0x000B, L2cap.frame(0x0040, payload), maxAclData = 27)
        assertEquals(2, packets.size)
        assertEquals(0x200B, packets[0].u16le(0))
        assertEquals(0x100B, packets[1].u16le(0))
        val joiner = AclReassembler()
        assertNull(joiner.add(packets[0]))
        val frame = joiner.add(packets[1])!!
        assertEquals(0x0040, frame.cid)
        assertArrayEquals(payload, frame.payload)
    }

    @Test
    fun signalingFrameCanCarryTwoCommands() {
        val payload = L2cap.signal(L2cap.CONNECTION_RESPONSE, 1, ByteArray(8)) +
            L2cap.signal(L2cap.CONFIGURE_REQUEST, 7, ByteArray(4))
        val signals = L2cap.parseSignals(payload)
        assertEquals(listOf(L2cap.CONNECTION_RESPONSE, L2cap.CONFIGURE_REQUEST), signals.map { it.code })
        assertEquals(7, signals[1].id)
    }

    @Test
    fun sdpRequestForAndroidAutoUuid() {
        val request = Sdp.searchAttributeRequest(
            transactionId = 1,
            pattern = Sdp.sequence(Sdp.uuid128(Sdp.ANDROID_AUTO_UUID)),
            attributes = Sdp.allAttributes(),
            continuation = byteArrayOf(0),
        )
        val expected = bytes(0x06, 0x00, 0x01, 0x00, 0x1D, 0x35, 0x11, 0x1C) +
            bytes(0x4D, 0xE1, 0x7A, 0x00, 0x52, 0xCB, 0x11, 0xE6, 0xBD, 0xF4, 0x08, 0x00, 0x20, 0x0C, 0x9A, 0x66) +
            bytes(0x03, 0xF0, 0x35, 0x05, 0x0A, 0x00, 0x00, 0xFF, 0xFF, 0x00)
        assertArrayEquals(expected, request)
    }

    @Test
    fun sdpAnswerGivesAndroidAutoRfcommChannel() {
        val name = "AAWireless".toByteArray()
        val record = Sdp.sequence(
            uint16(0x0001),
            Sdp.sequence(Sdp.uuid128(Sdp.ANDROID_AUTO_UUID)),
            uint16(0x0004),
            Sdp.sequence(
                Sdp.sequence(Sdp.uuid16(0x0100)),
                Sdp.sequence(Sdp.uuid16(0x0003), bytes(0x08, 0x04)),
            ),
            uint16(0x0100),
            bytes(0x25, name.size) + name,
        )
        val lists = Sdp.sequence(record)
        val pdu = bytes(0x07, 0x00, 0x01) + be16(lists.size + 3) + be16(lists.size) + lists + bytes(0x00)

        val part = Sdp.parseSearchAttributeResponse(pdu)
        assertEquals(1, part.continuation.size)
        val parsed = Sdp.parseRecords(part.attributeLists).single()
        assertEquals(4, parsed.rfcommChannel)
        assertEquals("AAWireless", parsed.name)
        assertEquals(listOf(Sdp.ANDROID_AUTO_UUID), parsed.serviceClasses)
        assertEquals("Android Auto", Sdp.serviceClassName(parsed.serviceClasses.single()))
    }

    @Test
    fun baseUuidShortensToSixteenBit() {
        val element = byteArrayOf(0x1C) +
            bytes(0x00, 0x00, 0x11, 0x1E, 0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0x80, 0x5F, 0x9B, 0x34, 0xFB)
        assertEquals(SdpElement.Uuid("111E"), Sdp.parseElement(element, 0).first)
    }

    @Test
    fun secureSimplePairingIsFeatureBit51() {
        assertTrue(HciLink.supportsSecureSimplePairing(1L shl 51))
    }

    @Test
    fun ioCapabilityReplyIsDisplayYesNoWithMitmBonding() {
        assertArrayEquals(
            bytes(0x0C, 0x73, 0x8E, 0xAD, 0x9E, 0x18, 0x01, 0x00, 0x03),
            HciLink.ioCapabilityReplyParams("18:9E:AD:8E:73:0C"),
        )
    }

    @Test
    fun pinReplyPadsPinToSixteenBytes() {
        val params = HciLink.pinCodeReplyParams("18:9E:AD:8E:73:0C", "0000")
        assertEquals(6 + 1 + 16, params.size)
        assertEquals(4, params[6].toInt())
        assertEquals('0'.code, params[7].toInt())
    }

    @Test
    fun userConfirmationCodeIsLittleEndian() {
        val event = bytes(0x33, 0x0A, 0x0C, 0x73, 0x8E, 0xAD, 0x9E, 0x18, 0x40, 0xE2, 0x01, 0x00)
        assertEquals(123456, HciLink.pairingCode(event))
    }

    @Test
    fun linkKeyNotificationKeepsKeyAndType() {
        val key = ByteArray(16) { (it + 1).toByte() }
        val event = bytes(0x18, 0x17, 0x0C, 0x73, 0x8E, 0xAD, 0x9E, 0x18) + key + bytes(0x05)
        val parsed = HciLink.parseLinkKeyNotification(event)!!
        assertEquals("18:9E:AD:8E:73:0C", parsed.address)
        assertArrayEquals(key, parsed.key)
        assertEquals(5, parsed.type)
    }

    private fun uint16(value: Int): ByteArray = byteArrayOf(0x09) + be16(value)

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
