package com.kyuusanq3.btbooter

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/**
 * Talks HCI to a USB Bluetooth controller with no kernel driver, through Android's USB host API.
 * Events arrive on the interrupt endpoint and ACL data on the bulk endpoints, both read as queued USB requests.
 */
internal class UsbHciTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val hciInterface: UsbInterface,
    eventEndpoint: UsbEndpoint,
    aclInEndpoint: UsbEndpoint,
    private val aclOutEndpoint: UsbEndpoint,
) : Closeable {
    /** Receives events that arrive while [command] or [readEvent] wait for something else. */
    var eventListener: ((ByteArray) -> Unit)? = null

    /** Receives ACL data packets as they arrive. */
    var aclListener: ((ByteArray) -> Unit)? = null

    private val received = ArrayDeque<Inbound>()
    private val eventStream = InboundStream(eventEndpoint, eventEndpoint.maxPacketSize, isEvent = true)
    private val aclStream = InboundStream(aclInEndpoint, ACL_BUFFER_SIZE, isEvent = false)

    init {
        eventStream.queue()
        aclStream.queue()
    }

    /**
     * Sends [opcode] and returns its Command Complete parameters from the status byte on.
     * Commands answered only by Command Status (Inquiry, Create Connection, ...) return just the status byte.
     */
    fun command(
        opcode: Int,
        params: ByteArray = ByteArray(0),
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
    ): ByteArray {
        val packet = HciPackets.command(opcode, params)
        val sent = connection.controlTransfer(
            HCI_COMMAND_REQUEST_TYPE,
            0,
            0,
            hciInterface.id,
            packet,
            packet.size,
            timeoutMs.toInt(),
        )
        if (sent != packet.size) {
            throw HciException(
                "The dongle did not take command 0x%04X (sent %d of %d bytes).".format(opcode, sent, packet.size),
            )
        }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                throw HciException("The dongle did not answer command 0x%04X.".format(opcode))
            }
            val inbound = readPacket(remaining) ?: continue
            if (inbound is Inbound.Acl) {
                aclListener?.invoke(inbound.bytes)
                continue
            }
            val event = inbound.bytes
            HciPackets.commandCompleteReturn(event, opcode)?.let { return it }
            val status = HciPackets.commandStatus(event, opcode)
            if (status == null) {
                eventListener?.invoke(event)
                continue
            }
            if (status != 0) {
                throw HciException("Command 0x%04X was refused with HCI status 0x%02X.".format(opcode, status))
            }
            return byteArrayOf(0)
        }
    }

    /** Next event, or null after [timeoutMs]. ACL data that arrives meanwhile goes to [aclListener]. */
    fun readEvent(timeoutMs: Long): ByteArray? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return null
            when (val inbound = readPacket(remaining) ?: return null) {
                is Inbound.Event -> return inbound.bytes
                is Inbound.Acl -> aclListener?.invoke(inbound.bytes)
            }
        }
    }

    /** Waits up to [timeoutMs] for one packet and hands it to the matching listener. */
    fun poll(timeoutMs: Long): Boolean {
        when (val inbound = readPacket(timeoutMs) ?: return false) {
            is Inbound.Event -> eventListener?.invoke(inbound.bytes)
            is Inbound.Acl -> aclListener?.invoke(inbound.bytes)
        }
        return true
    }

    fun sendAcl(packet: ByteArray) {
        val sent = connection.bulkTransfer(aclOutEndpoint, packet, packet.size, ACL_SEND_TIMEOUT_MS)
        if (sent != packet.size) {
            throw HciException("The dongle did not take ACL data (sent %d of %d bytes).".format(sent, packet.size))
        }
    }

    private fun readPacket(timeoutMs: Long): Inbound? {
        received.removeFirstOrNull()?.let { return it }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return null
            val request = try {
                connection.requestWait(remaining)
            } catch (_: TimeoutException) {
                return null
            } ?: throw HciException("Reading from the dongle failed. Was it unplugged?")
            (request.clientData as? InboundStream)?.complete(received)
            received.removeFirstOrNull()?.let { return it }
        }
    }

    override fun close() {
        eventStream.close()
        aclStream.close()
        connection.releaseInterface(hciInterface)
        connection.close()
    }

    private sealed class Inbound(val bytes: ByteArray) {
        class Event(bytes: ByteArray) : Inbound(bytes)
        class Acl(bytes: ByteArray) : Inbound(bytes)
    }

    /** One IN endpoint with a request always queued, joining USB transfers into whole HCI packets. */
    private inner class InboundStream(endpoint: UsbEndpoint, bufferSize: Int, private val isEvent: Boolean) {
        private val buffer = ByteBuffer.allocate(bufferSize.coerceAtLeast(MIN_IN_BUFFER))
        private val pending = ByteArrayOutputStream()
        private val request = UsbRequest().also {
            if (!it.initialize(connection, endpoint)) throw HciException("Could not set up a USB read on the dongle.")
            it.clientData = this
        }

        fun queue() {
            buffer.clear()
            if (!request.queue(buffer)) throw HciException("Could not queue a USB read on the dongle.")
        }

        fun complete(out: ArrayDeque<Inbound>) {
            pending.write(buffer.array(), 0, buffer.position())
            var bytes = pending.toByteArray()
            while (true) {
                val length = (if (isEvent) HciPackets.eventLength(bytes, bytes.size) else aclLength(bytes)) ?: break
                if (bytes.size < length) break
                val packet = bytes.copyOf(length)
                when {
                    !isEvent -> out.addLast(Inbound.Acl(packet))
                    HciPackets.eventCode(packet) != 0 -> out.addLast(Inbound.Event(packet))
                }
                bytes = bytes.copyOfRange(length, bytes.size)
            }
            pending.reset()
            pending.write(bytes)
            queue()
        }

        fun close() {
            request.cancel()
            request.close()
        }

        private fun aclLength(bytes: ByteArray): Int? = if (bytes.size < 4) null else 4 + bytes.u16le(2)
    }

    companion object {
        private const val TAG = "BtBooter"
        private const val HCI_COMMAND_REQUEST_TYPE = 0x20
        private const val COMMAND_TIMEOUT_MS = 3_000L
        private const val ACL_SEND_TIMEOUT_MS = 2_000
        private const val ACL_BUFFER_SIZE = 1_028
        private const val MIN_IN_BUFFER = 16

        fun isBluetoothController(device: UsbDevice): Boolean = findHciInterface(device) != null

        fun open(manager: UsbManager, device: UsbDevice): UsbHciTransport {
            val hciInterface = findHciInterface(device)
                ?: throw HciException("${describe(device)} has no Bluetooth HCI interface.")
            val endpoints = (0 until hciInterface.endpointCount).map { hciInterface.getEndpoint(it) }
            fun endpoint(type: Int, direction: Int, what: String): UsbEndpoint =
                endpoints.firstOrNull { it.type == type && it.direction == direction }
                    ?: throw HciException("${describe(device)} has no HCI $what endpoint.")
            val eventEndpoint = endpoint(UsbConstants.USB_ENDPOINT_XFER_INT, UsbConstants.USB_DIR_IN, "event")
            val aclIn = endpoint(UsbConstants.USB_ENDPOINT_XFER_BULK, UsbConstants.USB_DIR_IN, "data-in")
            val aclOut = endpoint(UsbConstants.USB_ENDPOINT_XFER_BULK, UsbConstants.USB_DIR_OUT, "data-out")
            val connection = manager.openDevice(device)
                ?: throw HciException("Android would not open ${describe(device)}.")
            if (!connection.claimInterface(hciInterface, true)) {
                connection.close()
                throw HciException("Could not claim the HCI interface of ${describe(device)}.")
            }
            // The S10A xHCI host times out HCI control writes until the dongle is reset once.
            val reset = resetUsbDevice(connection)
            val reclaimed = connection.claimInterface(hciInterface, true)
            Log.i(TAG, "USB reset before HCI: reset=$reset claimed=$reclaimed")
            if (!reset) {
                connection.close()
                throw HciException("Could not reset ${describe(device)} before talking to it.")
            }
            if (!reclaimed) {
                connection.close()
                throw HciException("Could not claim the HCI interface of ${describe(device)} after reset.")
            }
            return try {
                UsbHciTransport(connection, hciInterface, eventEndpoint, aclIn, aclOut)
            } catch (error: HciException) {
                connection.releaseInterface(hciInterface)
                connection.close()
                throw error
            }
        }

        fun describe(device: UsbDevice): String {
            val name = device.productName?.takeIf { it.isNotBlank() } ?: device.deviceName
            return "%s (%04x:%04x)".format(name, device.vendorId, device.productId)
        }

        /** Present on device since API 24. The compile stubs used here do not declare it. */
        private fun resetUsbDevice(connection: UsbDeviceConnection): Boolean {
            val method = connection.javaClass.methods.firstOrNull { method ->
                method.name == "resetDevice" && method.parameterTypes.isEmpty()
            } ?: return false
            return method.invoke(connection) as? Boolean ?: false
        }

        private fun findHciInterface(device: UsbDevice): UsbInterface? =
            (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull {
                    it.interfaceClass == UsbConstants.USB_CLASS_WIRELESS_CONTROLLER &&
                        it.interfaceSubclass == 1 &&
                        it.interfaceProtocol == 1
                }
    }
}
