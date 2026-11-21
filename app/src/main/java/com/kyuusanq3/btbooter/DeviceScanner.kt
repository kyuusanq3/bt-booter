package com.kyuusanq3.btbooter

import android.os.SystemClock
import android.util.Log

/**
 * Repeats Bluetooth inquiry rounds on the dongle and looks up names that devices did not
 * include in their inquiry response, until [run]'s keepScanning returns false.
 */
internal class DeviceScanner(
    private val hci: UsbHciTransport,
    private val onDevicesChanged: (List<InquiryResponse>) -> Unit,
) {
    private val devices = LinkedHashMap<String, InquiryResponse>()
    private val nameLookupsTried = HashSet<String>()

    fun run(keepScanning: () -> Boolean) {
        hci.eventListener = ::handleEvent
        HciPackets.requireSuccess(hci.command(HciPackets.OPCODE_RESET), "Reset")
        HciPackets.requireSuccess(
            hci.command(HciPackets.OPCODE_SET_EVENT_MASK, HciPackets.setEventMaskParams()),
            "Set Event Mask",
        )
        logCommand("Write Local Name") {
            hci.command(HciPackets.OPCODE_WRITE_LOCAL_NAME, HciPackets.localNameParams(DONGLE_NAME))
        }
        logCommand("Write Scan Enable") {
            hci.command(
                HciPackets.OPCODE_WRITE_SCAN_ENABLE,
                byteArrayOf(HciPackets.SCAN_ENABLE_INQUIRY_AND_PAGE.toByte()),
            )
        }
        // Chips without extended inquiry keep sending plain results, which the scan still reads.
        logCommand("Write Inquiry Mode") {
            hci.command(HciPackets.OPCODE_WRITE_INQUIRY_MODE, byteArrayOf(HciPackets.INQUIRY_MODE_EXTENDED.toByte()))
        }
        while (keepScanning()) {
            inquire(keepScanning)
            lookUpMissingNames(keepScanning)
        }
    }

    private fun logCommand(what: String, send: () -> ByteArray) {
        runCatching(send)
            .onSuccess { Log.d(TAG, "$what -> ${it.toHex()}") }
            .onFailure { Log.w(TAG, "$what failed: ${it.message}") }
    }

    private fun inquire(keepScanning: () -> Boolean) {
        hci.command(HciPackets.OPCODE_INQUIRY, HciPackets.inquiryParams(INQUIRY_LENGTH_UNITS))
        Log.d(TAG, "Inquiry started")
        val deadline = SystemClock.elapsedRealtime() + INQUIRY_LENGTH_UNITS * INQUIRY_UNIT_MS + INQUIRY_GRACE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!keepScanning()) {
                runCatching { hci.command(HciPackets.OPCODE_INQUIRY_CANCEL) }
                return
            }
            val event = hci.readEvent(POLL_MS) ?: continue
            if (HciPackets.eventCode(event) == HciPackets.EVENT_INQUIRY_COMPLETE) {
                Log.d(TAG, "Inquiry complete: ${event.toHex()}")
                return
            }
            handleEvent(event)
        }
        Log.w(TAG, "Inquiry round ended without Inquiry Complete")
    }

    private fun lookUpMissingNames(keepScanning: () -> Boolean) {
        val unnamed = devices.values.filter { it.name == null && it.address !in nameLookupsTried }
        for (device in unnamed) {
            if (!keepScanning()) return
            nameLookupsTried += device.address
            val started = runCatching {
                hci.command(HciPackets.OPCODE_REMOTE_NAME_REQUEST, HciPackets.remoteNameRequestParams(device))
            }.isSuccess
            if (!started) continue
            val deadline = SystemClock.elapsedRealtime() + NAME_LOOKUP_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!keepScanning()) return
                val event = hci.readEvent(POLL_MS) ?: continue
                handleEvent(event)
                if (HciPackets.parseRemoteNameComplete(event)?.address == device.address) break
            }
        }
    }

    private fun handleEvent(event: ByteArray) {
        Log.d(TAG, "Event: ${event.toHex()}")
        var changed = false
        for (found in HciPackets.parseInquiryResults(event)) {
            val known = devices[found.address]
            val merged = if (known == null) {
                found
            } else {
                found.copy(name = found.name ?: known.name, rssi = found.rssi ?: known.rssi)
            }
            if (merged != known) {
                devices[found.address] = merged
                changed = true
            }
        }
        HciPackets.parseRemoteNameComplete(event)?.let { result ->
            val known = devices[result.address]
            if (result.status == 0 && known != null && result.name.isNotBlank() && known.name != result.name) {
                devices[result.address] = known.copy(name = result.name)
                changed = true
            }
        }
        if (changed) onDevicesChanged(devices.values.toList())
    }

    private companion object {
        const val TAG = "BtBooter"
        const val DONGLE_NAME = "BT Booter"
        const val INQUIRY_LENGTH_UNITS = 4
        const val INQUIRY_UNIT_MS = 1_280L
        const val INQUIRY_GRACE_MS = 2_000L
        const val NAME_LOOKUP_TIMEOUT_MS = 6_000L
        const val POLL_MS = 250L
    }
}
