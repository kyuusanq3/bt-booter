# bt-booter agent notes

USB Bluetooth dongle that plays the phone for Carletter wireless Android Auto. Package `com.kyuusanq3.btbooter`. Durable head-unit captures live in `C:/dev/proj/automd/headunit-bt-dongle.md`, not in this repo.

## Lessons learned

- On the S10A (Qualcomm xHCI), a CSR8510 A10 enumerates at 12 Mbps but HCI control writes return `USBDEVFS_CONTROL … ret -110` until `UsbDeviceConnection.resetDevice()` runs once after `claimInterface`. The method is missing from the compile stubs; call it by reflection. Padding the 3-byte reset out to the 64-byte max packet still times out. Queuing the interrupt read before the first command is not the cause.
- The S10A port comes up as host, then about 76 s after boot the HCT stack logs `usb switch to peripheral` / `--mtc otg_dis` and removes xHCI. Write `host` to `/sys/devices/platform/soc/4e00000.ssusb/mode` as root to bring the dongle back. `adb root` restarts adbd and moves the wireless debugging port.
- Carletter's `WifiVersionResponse` is six raw bytes (`major, minor, status`), not protobuf. Type 2, status byte 0 means success. Working frame for 1.7: `00060002000100070000`. A protobuf reply is ignored and the version request is repeated.
- The T9E vendor stack `blink` finishes hands-free without `AT+CMER` (it stops after `AT+CLIP`). Gating Android Auto on `AT+CMER` makes BT Booter tear the link down while Carletter is already sending the hotspot. Channel 4 (`android` SPP) is registered only after that hands-free sequence.
- Do not join `carletter_368b` until that hotspot is on the air. Joining it drops the S10A's wireless adb session.
- Connect stays disabled while a scan is running. Selecting a device must enable Connect, and Connect must stop the scan before opening the dongle again.
