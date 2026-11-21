package com.kyuusanq3.btbooter

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

internal data class DongleInfo(
    val usb: String,
    val usbRelease: String?,
    val address: String,
    val version: LocalVersion,
    val localName: String?,
) {
    fun describe(): String = buildString {
        appendLine("USB:          $usb${usbRelease?.let { ", release $it" }.orEmpty()}")
        appendLine("Address:      $address")
        appendLine(
            "Chip maker:   ${HciPackets.manufacturerName(version.manufacturer)} (id ${version.manufacturer})",
        )
        appendLine(
            "HCI:          Bluetooth ${HciPackets.bluetoothVersionName(version.hciVersion)}" +
                " (version ${version.hciVersion}, revision 0x%04X)".format(version.hciRevision),
        )
        appendLine(
            "LMP:          Bluetooth ${HciPackets.bluetoothVersionName(version.lmpVersion)}" +
                " (version ${version.lmpVersion}, subversion 0x%04X)".format(version.lmpSubversion),
        )
        append("Name:         ${localName?.ifBlank { "(empty)" } ?: "(not reported)"}")
    }
}

internal object DongleReader {
    fun read(manager: UsbManager, device: UsbDevice): DongleInfo {
        UsbHciTransport.open(manager, device).use { hci ->
            hci.command(HciPackets.OPCODE_RESET).also { returned ->
                val status = returned.firstOrNull()?.toInt()?.and(0xFF)
                if (status != 0) throw HciException("Reset failed with HCI status 0x%02X.".format(status ?: -1))
            }
            val version = HciPackets.parseLocalVersion(hci.command(HciPackets.OPCODE_READ_LOCAL_VERSION))
            val address = HciPackets.parseBdAddr(hci.command(HciPackets.OPCODE_READ_BD_ADDR))
            val name = runCatching {
                HciPackets.parseLocalName(hci.command(HciPackets.OPCODE_READ_LOCAL_NAME))
            }.getOrNull()
            return DongleInfo(
                usb = UsbHciTransport.describe(device),
                usbRelease = device.version.takeIf { it.isNotBlank() },
                address = address,
                version = version,
                localName = name,
            )
        }
    }
}
