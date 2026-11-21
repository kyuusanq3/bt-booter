package com.kyuusanq3.btbooter

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.ToggleButton
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var usbManager: UsbManager
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var readButton: Button
    private lateinit var scanToggle: ToggleButton
    private lateinit var connectButton: Button
    private lateinit var updates: AppUpdateClient
    private lateinit var updateStatus: TextView
    private lateinit var updateAction: Button
    private lateinit var selectedLabel: TextView
    private lateinit var deviceList: ListView
    private lateinit var deviceAdapter: ArrayAdapter<String>
    private var devices: List<InquiryResponse> = emptyList()
    private var selectedAddress: String? = null
    private var selectedName: String? = null
    private var afterPermission: ((UsbDevice) -> Unit)? = null
    private var busy = false
    private var connectAfterScan = false
    private var downloadedApk: File? = null
    private var afterWifiPermission: (() -> Unit)? = null
    private val hotspotJoiner by lazy { WifiHotspotJoiner(this) }

    @Volatile
    private var scanning = false

    @Volatile
    private var destroyed = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.usbDevice() ?: return
                    val action = afterPermission
                    afterPermission = null
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && action != null) {
                        action(device)
                    } else {
                        busy = false
                        scanToggle.isChecked = false
                        status.text = getString(R.string.permission_denied, UsbHciTransport.describe(device))
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    note("USB ${intent.action?.substringAfterLast('_')?.lowercase()}: ${intent.usbDevice()?.deviceName}")
                    if (!busy) showDongleState()
                }
            }
        }
    }

    /**
     * `adb shell am broadcast -a com.kyuusanq3.btbooter.CONNECT -p com.kyuusanq3.btbooter --ei delay_s 20`
     * runs Connect later, so it can happen while another app (Carletter) is on screen and adb is cut off.
     */
    private val adbConnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val delaySeconds = intent.getIntExtra(EXTRA_DELAY_SECONDS, 0).coerceIn(0, MAX_ADB_DELAY_SECONDS)
            Log.i(TAG, "Connect requested over adb, starting in $delaySeconds s")
            runCatching { java.io.File(filesDir, LAST_CONNECT_FILE).writeText("armed, waiting $delaySeconds s\n") }
            mainHandler.postDelayed({ if (!destroyed) onConnectClicked() }, delaySeconds * 1_000L)
        }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val linkKeys = object : LinkKeyStore {
        private val prefs get() = getSharedPreferences(LINK_KEY_PREFS, MODE_PRIVATE)

        override fun get(address: String): ByteArray? =
            prefs.getString(address, null)?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()

        override fun put(address: String, key: ByteArray) {
            prefs.edit().putString(address, key.toHex()).commit()
        }

        override fun remove(address: String) {
            prefs.edit().remove(address).commit()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usbManager = getSystemService(UsbManager::class.java)
        status = findViewById(R.id.dongle_status)
        details = findViewById(R.id.dongle_details)
        readButton = findViewById(R.id.read_button)
        scanToggle = findViewById(R.id.scan_toggle)
        connectButton = findViewById(R.id.connect_button)
        selectedLabel = findViewById(R.id.selected_device)
        deviceList = findViewById(R.id.device_list)
        updates = AppUpdateClient(this)
        updateStatus = findViewById(R.id.update_status)
        updateAction = findViewById(R.id.update_action_button)
        findViewById<TextView>(R.id.version_label).text =
            getString(R.string.installed_version, BuildConfig.VERSION_NAME)
        findViewById<Button>(R.id.check_update_button).setOnClickListener { checkForUpdate() }
        updateAction.setOnClickListener { onUpdateAction() }

        deviceAdapter = ArrayAdapter(this, R.layout.device_row)
        deviceList.adapter = deviceAdapter
        deviceList.emptyView = findViewById(R.id.device_list_empty)
        deviceList.setOnItemClickListener { _, _, position, _ -> selectDevice(devices[position]) }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        selectedAddress = prefs.getString(KEY_SELECTED_ADDRESS, null)
        selectedName = prefs.getString(KEY_SELECTED_NAME, null)
        showSelection()

        readButton.setOnClickListener { onReadClicked() }
        scanToggle.setOnClickListener { onScanToggled(scanToggle.isChecked) }
        connectButton.setOnClickListener { onConnectClicked() }

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val adbFilter = IntentFilter(ACTION_ADB_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(adbConnectReceiver, adbFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
            registerReceiver(adbConnectReceiver, adbFilter)
        }
        showDongleState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!busy) showDongleState()
    }

    override fun onDestroy() {
        destroyed = true
        scanning = false
        mainHandler.removeCallbacksAndMessages(null)
        unregisterReceiver(usbReceiver)
        unregisterReceiver(adbConnectReceiver)
        io.shutdownNow()
        super.onDestroy()
    }

    private fun findDongle(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { UsbHciTransport.isBluetoothController(it) }

    private fun showDongleState() {
        val dongle = findDongle()
        readButton.isEnabled = dongle != null
        scanToggle.isEnabled = dongle != null
        connectButton.isEnabled = dongle != null && selectedAddress != null
        status.text = if (dongle == null) {
            getString(R.string.no_dongle)
        } else {
            getString(R.string.dongle_found, UsbHciTransport.describe(dongle))
        }
    }

    private fun onReadClicked() {
        if (busy) return
        withDongle { readDongle(it) }
    }

    private fun onScanToggled(start: Boolean) {
        if (!start) {
            if (scanning) {
                scanning = false
                scanToggle.isEnabled = false
                status.setText(R.string.stopping_scan)
            }
            return
        }
        if (busy) {
            scanToggle.isChecked = false
            return
        }
        withDongle { startScan(it) }
    }

    private fun onConnectClicked() {
        if (scanning) {
            connectAfterScan = true
            scanning = false
            scanToggle.isEnabled = false
            connectButton.isEnabled = false
            status.setText(R.string.stopping_scan)
            return
        }
        if (busy) return note("Connect skipped: busy")
        if (!hasWifiPermission()) {
            afterWifiPermission = { startConnect() }
            requestPermissions(wifiPermissions(), REQUEST_WIFI)
            return
        }
        startConnect()
    }

    private fun startConnect() {
        if (busy) return note("Connect skipped: busy")
        val address = selectedAddress ?: return note("Connect skipped: no device selected")
        val seen = devices.firstOrNull { it.address == address }
        val target = ConnectTarget(
            address = address,
            name = seen?.name ?: selectedName,
            pageScanRepetitionMode = seen?.pageScanRepetitionMode ?: DEFAULT_PAGE_SCAN_REPETITION_MODE,
            clockOffset = seen?.clockOffset,
        )
        withDongle { connectTo(it, target) }
    }

    private fun connectTo(device: UsbDevice, target: ConnectTarget) {
        busy = true
        readButton.isEnabled = false
        scanToggle.isEnabled = false
        connectButton.isEnabled = false
        details.text = ""
        io.execute {
            val result = runCatching {
                UsbHciTransport.open(usbManager, device).use { hci ->
                    DeviceConnector(hci, linkKeys, { message ->
                        runOnUiThread { if (!destroyed) status.text = message }
                    }, hotspotJoiner).run(target)
                }
            }.getOrElse { ConnectResult(report = "", error = it.message ?: it.javaClass.simpleName) }
            Log.i(TAG, "Connect result:\n${result.report}\nerror=${result.error}")
            runCatching {
                java.io.File(filesDir, LAST_CONNECT_FILE).appendText("${result.report}\nerror=${result.error}\n")
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                busy = false
                showDongleState()
                status.text = result.error?.let { getString(R.string.connect_failed, it) }
                    ?: getString(R.string.connect_ok)
                details.text = result.report
            }
        }
    }

    /** Runs [action] on the plugged-in dongle once Android has granted USB access to it. */
    private fun withDongle(action: (UsbDevice) -> Unit) {
        val dongle = findDongle()
        if (dongle == null) {
            note("No dongle found")
            scanToggle.isChecked = false
            showDongleState()
            return
        }
        busy = true
        if (usbManager.hasPermission(dongle)) {
            action(dongle)
            return
        }
        note("Asking for USB permission")
        afterPermission = action
        status.setText(R.string.asking_permission)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            mutable,
        )
        usbManager.requestPermission(dongle, permissionIntent)
    }

    private fun readDongle(device: UsbDevice) {
        busy = true
        scanToggle.isEnabled = false
        connectButton.isEnabled = false
        status.setText(R.string.reading)
        details.text = ""
        io.execute {
            val result = runCatching { DongleReader.read(usbManager, device) }
            result.onSuccess { Log.i(TAG, "Dongle read:\n${it.describe()}") }
                .onFailure { Log.e(TAG, "Dongle read failed", it) }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                busy = false
                showDongleState()
                result.fold(
                    onSuccess = { info ->
                        status.setText(R.string.read_ok)
                        details.text = info.describe()
                    },
                    onFailure = { error ->
                        status.text = getString(R.string.read_failed, error.message ?: error.javaClass.simpleName)
                    },
                )
            }
        }
    }

    private fun startScan(device: UsbDevice) {
        busy = true
        scanning = true
        scanToggle.isChecked = true
        readButton.isEnabled = false
        connectButton.isEnabled = false
        status.setText(R.string.scanning)
        io.execute {
            val result = runCatching {
                UsbHciTransport.open(usbManager, device).use { hci ->
                    DeviceScanner(hci) { found -> runOnUiThread { showDevices(found) } }
                        .run { scanning && !destroyed }
                }
            }
            result.onFailure { Log.e(TAG, "Scan failed", it) }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                scanning = false
                busy = false
                scanToggle.isChecked = false
                showDongleState()
                val startConnect = connectAfterScan
                connectAfterScan = false
                if (startConnect) {
                    onConnectClicked()
                    return@runOnUiThread
                }
                status.text = result.fold(
                    onSuccess = { getString(R.string.scan_stopped, devices.size) },
                    onFailure = { getString(R.string.scan_failed, it.message ?: it.javaClass.simpleName) },
                )
            }
        }
    }

    private fun showDevices(found: List<InquiryResponse>) {
        if (destroyed) return
        devices = found
        deviceAdapter.clear()
        deviceAdapter.addAll(found.map(::deviceRow))
        val selectedIndex = found.indexOfFirst { it.address == selectedAddress }
        deviceList.clearChoices()
        if (selectedIndex >= 0) {
            deviceList.setItemChecked(selectedIndex, true)
            found[selectedIndex].name?.let { if (it != selectedName) saveSelection(found[selectedIndex]) }
        }
        showSelection()
        refreshConnectButton()
        Log.i(TAG, "Devices: " + found.joinToString { "${it.address} ${it.name ?: "?"} rssi=${it.rssi}" })
    }

    private fun selectDevice(device: InquiryResponse) {
        saveSelection(device)
        showSelection()
        refreshConnectButton()
    }

    /** Connect stays available once a device is chosen, including while a scan is still running. */
    private fun refreshConnectButton() {
        if (busy && !scanning) return
        connectButton.isEnabled = findDongle() != null && selectedAddress != null
    }

    private fun saveSelection(device: InquiryResponse) {
        selectedAddress = device.address
        selectedName = device.name
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_SELECTED_ADDRESS, device.address)
            .putString(KEY_SELECTED_NAME, device.name)
            .apply()
    }

    private fun showSelection() {
        val address = selectedAddress
        selectedLabel.text = if (address == null) {
            getString(R.string.no_selection)
        } else {
            getString(R.string.selected_device, "${selectedName ?: getString(R.string.unknown_name)} ($address)")
        }
    }

    private fun deviceRow(device: InquiryResponse): String {
        val details = listOfNotNull(
            device.address,
            HciPackets.majorDeviceClassName(device.classOfDevice),
            device.rssi?.let { "$it dBm" },
        ).joinToString(" · ")
        return "${device.name ?: getString(R.string.unknown_name)}\n$details"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_WIFI) return
        val action = afterWifiPermission
        afterWifiPermission = null
        action?.invoke()
    }

    private fun hasWifiPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return wifiPermissions().any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun wifiPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun checkForUpdate() {
        if (busy) return
        busy = true
        downloadedApk = null
        updateAction.visibility = View.GONE
        updateStatus.setText(R.string.update_checking)
        io.execute {
            val result = try {
                Result.success(updates.checkForUpdate())
            } catch (error: Exception) {
                logUpdateFailure("Failed to check for update", error)
                Result.failure(error)
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                busy = false
                result.fold(
                    onSuccess = { check ->
                        when (check) {
                            UpdateCheck.UpToDate -> updateStatus.setText(R.string.update_up_to_date)
                            is UpdateCheck.Available -> {
                                updateStatus.text = getString(R.string.update_available, check.versionName)
                                updateAction.setText(R.string.download_update)
                                updateAction.tag = ACTION_DOWNLOAD
                                updateAction.visibility = View.VISIBLE
                            }
                        }
                    },
                    onFailure = { updateStatus.setText(R.string.update_check_failed) },
                )
            }
        }
    }

    private fun onUpdateAction() {
        if (busy) return
        when (updateAction.tag) {
            ACTION_DOWNLOAD -> downloadUpdate()
            ACTION_INSTALL -> downloadedApk?.let { launchApkInstall(it) }
        }
    }

    private fun downloadUpdate() {
        busy = true
        updateStatus.setText(R.string.update_downloading)
        io.execute {
            val result = try {
                Result.success(
                    updates.downloadApk { percent ->
                        runOnUiThread {
                            if (!destroyed) {
                                updateStatus.text = getString(R.string.update_downloading_percent, percent)
                            }
                        }
                    },
                )
            } catch (error: Exception) {
                logUpdateFailure("Failed to download APK", error)
                Result.failure(error)
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                busy = false
                result.fold(
                    onSuccess = { file ->
                        downloadedApk = file
                        updateStatus.setText(R.string.update_ready)
                        updateAction.setText(R.string.install_update)
                        updateAction.tag = ACTION_INSTALL
                        updateAction.visibility = View.VISIBLE
                    },
                    onFailure = {
                        updateStatus.setText(R.string.update_download_failed)
                        updateAction.visibility = View.GONE
                    },
                )
            }
        }
    }

    private fun launchApkInstall(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                    },
                )
            } catch (_: ActivityNotFoundException) {
                updateStatus.setText(R.string.update_install_failed)
            }
            return
        }
        val uri = UpdateApkProvider.apkUri()
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri(apkFile.name, uri)
                },
            )
        } catch (_: ActivityNotFoundException) {
            updateStatus.setText(R.string.update_install_failed)
        }
    }

    /** Logs [text] and appends it to the last-connect file, which outlives the T9E's small log buffer. */
    private fun note(text: String) {
        Log.i(TAG, text)
        runCatching { java.io.File(filesDir, LAST_CONNECT_FILE).appendText("$text\n") }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    companion object {
        private const val TAG = "BtBooter"
        private const val ACTION_USB_PERMISSION = "com.kyuusanq3.btbooter.USB_PERMISSION"
        private const val ACTION_ADB_CONNECT = "com.kyuusanq3.btbooter.CONNECT"
        private const val EXTRA_DELAY_SECONDS = "delay_s"
        private const val MAX_ADB_DELAY_SECONDS = 120
        private const val LAST_CONNECT_FILE = "last-connect.txt"
        private const val LINK_KEY_PREFS = "link_keys"
        private const val PREFS = "bt_booter"
        private const val KEY_SELECTED_ADDRESS = "selected_address"
        private const val KEY_SELECTED_NAME = "selected_name"
        private const val DEFAULT_PAGE_SCAN_REPETITION_MODE = 1
        private const val REQUEST_WIFI = 1
        private const val ACTION_DOWNLOAD = "download"
        private const val ACTION_INSTALL = "install"
    }
}
