package com.kyuusanq3.btbooter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HciPacketsTest {
    @Test
    fun resetCommandIsOpcodeLittleEndianWithNoParameters() {
        assertArrayEquals(byteArrayOf(0x03, 0x0C, 0x00), HciPackets.command(HciPackets.OPCODE_RESET))
    }

    @Test
    fun commandCompleteReturnsParametersForMatchingOpcode() {
        val event = bytes(0x0E, 0x04, 0x01, 0x03, 0x0C, 0x00)
        assertArrayEquals(bytes(0x00), HciPackets.commandCompleteReturn(event, HciPackets.OPCODE_RESET))
        assertNull(HciPackets.commandCompleteReturn(event, HciPackets.OPCODE_READ_BD_ADDR))
    }

    @Test
    fun commandStatusReadsStatusForMatchingOpcode() {
        val event = bytes(0x0F, 0x04, 0x0C, 0x01, 0x14, 0x0C)
        assertEquals(0x0C, HciPackets.commandStatus(event, HciPackets.OPCODE_READ_LOCAL_NAME))
    }

    @Test
    fun localVersionFieldsAreLittleEndian() {
        val version = HciPackets.parseLocalVersion(bytes(0x00, 0x06, 0xBB, 0x22, 0x06, 0x0A, 0x00, 0xBB, 0x22))
        assertEquals(LocalVersion(6, 0x22BB, 6, 10, 0x22BB), version)
        assertEquals("4.0", HciPackets.bluetoothVersionName(version.hciVersion))
    }

    @Test
    fun bdAddrIsPrintedMostSignificantByteFirst() {
        val address = HciPackets.parseBdAddr(bytes(0x00, 0x0C, 0x73, 0x8E, 0xAD, 0x9E, 0x18))
        assertEquals("18:9E:AD:8E:73:0C", address)
    }

    @Test
    fun localNameStopsAtFirstNul() {
        val returned = byteArrayOf(0x00) + "CSR8510".toByteArray() + ByteArray(5)
        assertEquals("CSR8510", HciPackets.parseLocalName(returned))
    }

    @Test
    fun eventLengthComesFromParameterLengthByte() {
        assertEquals(6, HciPackets.eventLength(bytes(0x0E, 0x04), 2))
        assertNull(HciPackets.eventLength(bytes(0x0E), 1))
    }

    @Test(expected = HciException::class)
    fun nonZeroStatusIsAnError() {
        HciPackets.parseBdAddr(bytes(0x01, 0, 0, 0, 0, 0, 0))
    }

    @Test
    fun inquiryUsesGeneralInquiryAccessCode() {
        assertArrayEquals(bytes(0x33, 0x8B, 0x9E, 0x04, 0x00), HciPackets.inquiryParams(4))
    }

    @Test
    fun eventMaskEnablesExtendedInquiryAndSimplePairing() {
        assertArrayEquals(
            bytes(0xFF, 0xFF, 0xFB, 0xFF, 0x07, 0xF8, 0xBF, 0x3D),
            HciPackets.setEventMaskParams(),
        )
    }

    @Test
    fun inquiryResultWithRssiParsesOneDevice() {
        val event = bytes(
            0x22, 0x0F, 0x01,
            0x0C, 0x73, 0x8E, 0xAD, 0x9E, 0x18,
            0x01, 0x00,
            0x08, 0x04, 0x24,
            0x34, 0x92,
            0xC4,
        )
        val device = HciPackets.parseInquiryResults(event).single()
        assertEquals("18:9E:AD:8E:73:0C", device.address)
        assertEquals(1, device.pageScanRepetitionMode)
        assertEquals(0x240408, device.classOfDevice)
        assertEquals("Audio/Video", HciPackets.majorDeviceClassName(device.classOfDevice))
        assertEquals(0x1234, device.clockOffset)
        assertEquals(-60, device.rssi)
        assertNull(device.name)
    }

    @Test
    fun extendedInquiryResultReadsCompleteName() {
        val name = "T9E_WT-1195".toByteArray()
        val header = bytes(
            0x2F, 0xFF, 0x01,
            0x0C, 0x73, 0x8E, 0xAD, 0x9E, 0x18,
            0x01, 0x00,
            0x08, 0x04, 0x24,
            0x00, 0x00,
            0xC4,
        )
        val eir = bytes(0x03, 0x08, 0x54, 0x39) + byteArrayOf((name.size + 1).toByte(), 0x09) + name
        val event = header + eir + ByteArray(240 - eir.size)
        assertEquals("T9E_WT-1195", HciPackets.parseInquiryResults(event).single().name)
    }

    @Test
    fun remoteNameRequestMarksClockOffsetValid() {
        val device = InquiryResponse("18:9E:AD:8E:73:0C", 1, 0x240408, 0x1234, null, null)
        assertArrayEquals(
            bytes(0x0C, 0x73, 0x8E, 0xAD, 0x9E, 0x18, 0x01, 0x00, 0x34, 0x92),
            HciPackets.remoteNameRequestParams(device),
        )
    }

    @Test
    fun remoteNameCompleteStopsAtNul() {
        val event = bytes(0x07, 0xFF, 0x00, 0x0C, 0x73, 0x8E, 0xAD, 0x9E, 0x18) +
            "Galaxy S21".toByteArray() + ByteArray(238)
        assertEquals(
            RemoteName("18:9E:AD:8E:73:0C", 0, "Galaxy S21"),
            HciPackets.parseRemoteNameComplete(event),
        )
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
