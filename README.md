# bt-booter

Drives a USB Bluetooth dongle from an ordinary Android app, through Android's USB host API.
Target is the T9E head unit (`T9E_WT` / `uis8581a2h10`): its kernel has no `btusb` driver,
so the dongle is free for an app to claim.

The goal is for the dongle to play the phone's side of Carletter's wireless Android Auto
start-up, so the S10A's Android Auto can project to the T9E over Wi-Fi.

## Current step

The app can claim a CSR8510, scan, pair, and run the hands-free plus Android Auto
wireless-setup exchange with Carletter on the T9E. Hands-free is finished at `AT+CLIP`
(blink never sends `AT+CMER`). The app then stays on the Android Auto channel, records
the type 3 hotspot, and joins it. The TCP port and the gearhead launch are not in this
version. On the S10A the USB host must be in `host` mode, and the dongle must be reset
once before the first HCI command.

Check for updates reads the latest GitHub release and installs `bt-booter.apk`.

Head-unit findings from that work live in `C:/dev/proj/automd/headunit-bt-dongle.md`.

Not platform-signed and no `sharedUserId`: the T9E's system key is a vendor key
(`O=Zhantang`), not the AOSP test key.

## Build and install

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug testDebugUnitTest
adb -s 192.168.0.236:5555 install -r app\build\outputs\apk\debug\app-debug.apk
```

Logs: `adb logcat -s BtBooter`.
