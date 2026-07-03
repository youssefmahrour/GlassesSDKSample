package com.sdk.glassessdksample

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.sdk.glassessdksample.ai.VisionActivity
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.sdk.glassessdksample.databinding.AcitivytMainBinding
import com.sdk.glassessdksample.ui.*
import com.sdk.glassessdksample.ui.wifi.p2p.WifiP2pManagerSingleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: AcitivytMainBinding
    private val deviceNotifyListener by lazy { MyDeviceNotifyListener() }
    private val logFile by lazy { File(getExternalFilesDir(null), "app_logs.txt") }
    private var bluetoothEnableRequestInFlight = false
    private var p2pHostAddress: String? = null
    private var activeDownloadJob: Job? = null
    private var activeP2pReceiver: BroadcastReceiver? = null
    private var activeP2pManager: WifiP2pManagerSingleton? = null
    private var autoCaptureJob: Job? = null
    private var autoCaptureIntervalMs: Long = 1000L
    private var autoCaptureEnabled: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = AcitivytMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EventBus.getDefault().register(this)
        initView()
        updateConnectionStatus()
        logEvent("MainActivity created")
    }

    inner class PermissionCallback : OnPermissionCallback {
        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
            if (all) {
                startKtxActivity<DeviceBindActivity>()
            }
        }

        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
            super.onDenied(permissions, never)
            if (never) {
                XXPermissions.startPermissionActivity(this@MainActivity, permissions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateConnectionStatus()
        logEvent("MainActivity resumed")
        try {
            if (!BluetoothUtils.isEnabledBluetooth(this) && !bluetoothEnableRequestInFlight) {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return
                    }
                }
                bluetoothEnableRequestInFlight = true
                startActivityForResult(intent, 300)
            }
        } catch (e: Exception) {
            bluetoothEnableRequestInFlight = false
            Log.w("MainActivity", "Unable to request Bluetooth enable", e)
        }
        if (!hasBluetooth(this)) {
            requestBluetoothPermission(this, BluetoothPermissionCallback())
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onGlassesDataEvent(event: GlassesDataEvent) {
        val summary = if (!event.parsedIp.isNullOrBlank()) {
            "Incoming glasses data: ${event.message.take(80)}\nDiscovered IP: ${event.parsedIp}"
        } else {
            "Incoming glasses data: ${event.message.take(120)}"
        }
        binding.downloadSummary.text = "Glasses data: $summary"
        binding.statusText.text = "Glasses data received from ${event.source}"
        logEvent("Glasses data received: ${event.source} -> ${event.message}")
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onBluetoothState(event: BluetoothEvent) {
        updateConnectionStatus()
        if (event.connect) {
            startInitialHandshake()
            binding.statusText.text = "Connected to the glasses. Waiting for device data..."
        } else {
            binding.statusText.text = "Waiting for glasses connection"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 300) {
            bluetoothEnableRequestInFlight = false
            if (resultCode == Activity.RESULT_OK) {
                showToast("Bluetooth enabled")
            } else {
                showToast("Bluetooth is required for the glasses flow")
            }
            updateConnectionStatus()
        }
    }

    override fun onDestroy() {
        activeDownloadJob?.cancel()
        stopAutoCapture()
        cleanupP2pResources()
        try {
            EventBus.getDefault().unregister(this)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    inner class BluetoothPermissionCallback : OnPermissionCallback {
        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
            // Permission granted
        }

        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
            super.onDenied(permissions, never)
            if (never) {
                XXPermissions.startPermissionActivity(this@MainActivity, permissions)
            }
        }
    }

    private fun updateConnectionStatus() {
        val bleManager = getBleManagerOrNull()
        val deviceManager = getDeviceManagerOrNull()
        val connected = bleManager?.isConnected == true
        val address = deviceManager?.deviceAddress?.takeIf { it.isNotBlank() }
            ?: getSharedPreferences("app_state", MODE_PRIVATE).getString("device_address", null)?.trim()
        val lastDevice = address?.takeIf { it.isNotBlank() } ?: "Not available"
        val status = when {
            connected && !address.isNullOrBlank() -> "Connected to $address"
            !address.isNullOrBlank() -> "Device selected: $address"
            else -> "No glasses device connected — tap Scan to discover one"
        }
        binding.statusText.text = status
        binding.lastDeviceSummary.text = "Last connected device: $lastDevice"
        logEvent("Connection status: $status")
    }

    private fun getBleManagerOrNull(): BleOperateManager? = try {
        BleOperateManager.getInstance(application)
    } catch (t: Throwable) {
        logEvent("BleOperateManager unavailable: ${t.message}")
        null
    }

    private fun getDeviceManagerOrNull(): DeviceManager? = try {
        DeviceManager.getInstance()
    } catch (t: Throwable) {
        logEvent("DeviceManager unavailable: ${t.message}")
        null
    }

    private fun initView() {
        setOnClickListener(
            binding.btnScan,
            binding.btnConnect,
            binding.btnDisconnect,
            binding.btnAddListener,
            binding.btnSetTime,
            binding.btnVersion,
            binding.btnCamera,
            binding.btnVideo,
            binding.btnRecord,
            binding.btnThumbnail,
            binding.btnBt,
            binding.btnBattery,
            binding.btnVolume,
            binding.btnMediaCount,
            binding.btnDataDownload
        ) {
            try {
                logEvent("Button tapped: ${this.id}")
                when (this) {
                    binding.btnScan -> {
                        logEvent("Scan button pressed")
                        requestBluetoothPermission(this@MainActivity, PermissionCallback())
                    }

                    binding.btnConnect -> {
                        logEvent("Connect button pressed")
                        val deviceManager = getDeviceManagerOrNull()
                        val bleManager = getBleManagerOrNull()
                        val address = deviceManager?.deviceAddress
                        if (address.isNullOrBlank()) {
                            logEvent("Connect failed: no device address")
                            showToast("No glasses device is connected yet")
                            updateConnectionStatus()
                            return@setOnClickListener
                        }
                        if (bleManager == null) {
                            logEvent("Connect failed: BLE SDK unavailable")
                            showToast("Bluetooth SDK is not ready yet")
                            updateConnectionStatus()
                            return@setOnClickListener
                        }
                        bleManager.connectDirectly(address)
                        updateConnectionStatus()
                    }

                    binding.btnDisconnect -> {
                        logEvent("Disconnect button pressed")
                        getBleManagerOrNull()?.unBindDevice()
                        updateConnectionStatus()
                    }

                    binding.btnAddListener -> {
                        logEvent("Add listener button pressed")
                        if (!ensureBleReady("Add listener")) return@setOnClickListener
                        LargeDataHandler.getInstance().addOutDeviceListener(100, deviceNotifyListener)
                    }

                    binding.btnSetTime -> {
                        logEvent("Set time button pressed")
                        if (!ensureBleReady("Set time")) return@setOnClickListener
                        LargeDataHandler.getInstance().syncTime { _, _ -> }
                    }

                    binding.btnVersion -> {
                        logEvent("Get version button pressed")
                        if (!ensureBleReady("Get version")) return@setOnClickListener
                        LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
                            if (response != null) {
                                Log.d("MainActivity", "Device info received")
                            }
                        }
                    }

                    binding.btnCamera -> {
                        logEvent("Camera button pressed")
                        if (!ensureBleReady("Camera")) return@setOnClickListener
                        LargeDataHandler.getInstance().glassesControl(
                            byteArrayOf(0x02, 0x01, 0x01)
                        ) { _, event ->
                            if (event != null) {
                                Log.d("MainActivity", "Camera control response")
                            }
                        }
                    }

                    binding.btnVideo -> {
                        logEvent("Video button pressed")
                        if (!ensureBleReady("Video")) return@setOnClickListener
                        val videoStart = true
                        val value = if (videoStart) 0x02 else 0x03
                        LargeDataHandler.getInstance().glassesControl(
                            byteArrayOf(0x02, 0x01, value.toByte())
                        ) { _, event ->
                            if (event != null) {
                                Log.d("MainActivity", "Video control response")
                            }
                        }
                    }

                    binding.btnRecord -> {
                        logEvent("Record button pressed")
                        if (!ensureBleReady("Record")) return@setOnClickListener
                        val recordStart = true
                        val value = if (recordStart) 0x08 else 0x0c
                        LargeDataHandler.getInstance().glassesControl(
                            byteArrayOf(0x02, 0x01, value.toByte())
                        ) { _, event ->
                            if (event != null) {
                                Log.d("MainActivity", "Record control response")
                            }
                        }
                    }

                    binding.btnThumbnail -> {
                        logEvent("Thumbnail button pressed")
                        if (!ensureBleReady("Thumbnail")) return@setOnClickListener
                        val thumbnailSize = 0x02
                        LargeDataHandler.getInstance().glassesControl(
                            byteArrayOf(
                                0x02,
                                0x01,
                                0x06,
                                thumbnailSize.toByte(),
                                thumbnailSize.toByte(),
                                0x02
                            )
                        ) { _, event ->
                            if (event != null) {
                                Log.d("MainActivity", "Thumbnail control response")
                            }
                        }
                    }

                    binding.btnBt -> {
                        logEvent("Bluetooth scan button pressed")
                        val bleManager = getBleManagerOrNull()
                        if (bleManager == null) {
                            logEvent("Classic Bluetooth scan skipped: BLE SDK unavailable")
                            showToast("Bluetooth SDK is not ready yet")
                            return@setOnClickListener
                        }
                        bleManager.classicBluetoothStartScan()
                    }

                    binding.btnBattery -> {
                        logEvent("Battery button pressed")
                        if (!ensureBleReady("Battery")) return@setOnClickListener
                        LargeDataHandler.getInstance().addBatteryCallBack("init") { _, response ->
                            // Battery callback
                        }
                        LargeDataHandler.getInstance().syncBattery()
                    }

                    binding.btnVolume -> {
                        logEvent("Volume button pressed")
                        if (!ensureBleReady("Volume")) return@setOnClickListener
                        LargeDataHandler.getInstance().getVolumeControl { _, response ->
                            if (response != null) {
                                Log.d("MainActivity", "Volume control response")
                            }
                        }
                    }

                    binding.btnMediaCount -> {
                        logEvent("Media count button pressed")
                        if (!ensureBleReady("Media count")) return@setOnClickListener
                        LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x04)) { _, event ->
                            if (event != null) {
                                Log.d("MainActivity", "Media count response")
                            }
                        }
                    }

                    binding.btnDataDownload -> {
                        logEvent("Data download button pressed")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNearbyWifiDevicesPermission(this@MainActivity, object : OnPermissionCallback {
                                override fun onGranted(permissions: MutableList<String>, all: Boolean) {
                                    if (all) {
                                        startDataDownload()
                                    }
                                }

                                override fun onDenied(permissions: MutableList<String>, never: Boolean) {
                                    super.onDenied(permissions, never)
                                    if (never) {
                                        XXPermissions.startPermissionActivity(this@MainActivity, permissions)
                                    }
                                }
                            })
                        } else {
                            startDataDownload()
                        }
                    }
                }
            } catch (e: Exception) {
                logEvent("Action failed: ${e.message}")
                Log.e("MainActivity", "Action failed", e)
                updateConnectionStatus()
                showToast("The requested action could not be completed")
            }
        }

        binding.btnAiToggle.setOnClickListener {
            try {
                logEvent("AI Vision button pressed")
                startActivity(Intent(this, VisionActivity::class.java))
            } catch (e: Exception) {
                logEvent("Unable to open AI Vision: ${e.message}")
                Log.e("MainActivity", "Unable to open AI Vision", e)
                Toast.makeText(this, "AI Vision is unavailable right now.", Toast.LENGTH_LONG).show()
            }
        }

        // Long-press camera button to toggle automatic photo capture (fake live)
        binding.btnCamera.setOnLongClickListener {
            try {
                if (!ensureBleReady("Auto-capture")) return@setOnLongClickListener true
                autoCaptureEnabled = !autoCaptureEnabled
                if (autoCaptureEnabled) {
                    startAutoCapture()
                } else {
                    stopAutoCapture()
                }
            } catch (e: Exception) {
                logEvent("Auto-capture toggle failed: ${e.message}")
            }
            true
        }
    }

    private fun startAutoCapture() {
        if (autoCaptureJob?.isActive == true) return
        logEvent("Starting auto-capture every ${autoCaptureIntervalMs}ms")
        showToast("Auto-capture started")
        autoCaptureJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                while (autoCaptureEnabled) {
                    if (ensureBleReady("Auto-capture") && getBleManagerOrNull()?.isConnected == true) {
                        LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x01)) { _, event ->
                            if (event != null) {
                                Log.d("MainActivity", "Auto-capture sent")
                            }
                        }
                    }
                    delay(autoCaptureIntervalMs)
                }
            } catch (t: Throwable) {
                logEvent("Auto-capture error: ${t.message}")
            }
        }
    }

    private fun stopAutoCapture() {
        logEvent("Stopping auto-capture")
        autoCaptureEnabled = false
        autoCaptureJob?.cancel()
        autoCaptureJob = null
        showToast("Auto-capture stopped")
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun startInitialHandshake() {
        if (getBleManagerOrNull()?.isConnected != true) return
        try {
            LargeDataHandler.getInstance().addOutDeviceListener(100, deviceNotifyListener)
            LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
                logEvent("Initial syncDeviceInfo response: ${response?.toString().orEmpty()}")
            }
            LargeDataHandler.getInstance().syncBattery()
            logEvent("Initial handshake requested")
        } catch (t: Throwable) {
            logEvent("Initial handshake failed: ${t.message}")
            Log.w("MainActivity", "Initial handshake failed", t)
        }
    }

    private fun getConfiguredDeviceIp(): String? {
        val fromIntent = intent?.getStringExtra("device_ip")?.trim().orEmpty()
        if (fromIntent.isNotBlank()) return fromIntent
        val fromPrefs = getSharedPreferences("app_state", MODE_PRIVATE)
            .getString("device_ip", null)
            ?.trim()
        return fromPrefs?.takeIf { it.isNotBlank() }
    }

    private fun ensureBleReady(actionName: String): Boolean {
        val bleManager = getBleManagerOrNull()
        if (bleManager == null) {
            logEvent("$actionName skipped: BLE SDK unavailable")
            showToast("Bluetooth SDK is not ready yet")
            return false
        }
        if (!bleManager.isConnected) {
            logEvent("$actionName skipped: glasses not connected")
            showToast("Connect to the glasses first")
            return false
        }
        return true
    }

    private fun logEvent(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val line = "$timestamp $message"
        Log.i("AppLog", line)
        try {
            FileOutputStream(logFile, true).bufferedWriter().use { writer ->
                writer.appendLine(line)
            }
        } catch (e: Exception) {
            Log.e("AppLog", "Failed to write log file", e)
        }
    }

    private fun startDataDownload() {
        if (activeDownloadJob?.isActive == true) {
            showToast("A download is already in progress")
            return
        }

        logEvent("Starting Wi-Fi Direct / P2P data download")
        Log.i("DataDownload", "Starting Wi-Fi Direct / P2P data download...")

        if (getBleManagerOrNull()?.isConnected != true) {
            logEvent("Data download skipped: Bluetooth not connected")
            Log.e("DataDownload", "Bluetooth not connected. Please connect to glasses first.")
            showToast("Connect to the glasses first before downloading media")
            return
        }

        val fallbackDeviceIp = getConfiguredDeviceIp().orEmpty()
        if (fallbackDeviceIp.isBlank()) {
            logEvent("Waiting for a P2P host address from the glasses")
            Log.i("DataDownload", "No saved glasses IP; waiting for Wi-Fi Direct host address")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!XXPermissions.isGranted(this, "android.permission.NEARBY_WIFI_DEVICES")) {
                logEvent("Data download skipped: nearby Wi-Fi permission missing")
                Log.e("DataDownload", "NEARBY_WIFI_DEVICES permission not granted")
                showToast("Nearby Wi-Fi permission is required for P2P transfers")
                return
            }
        }

        activeDownloadJob = lifecycleScope.launch(Dispatchers.IO) {
            // Use applicationContext so the singleton does not hold an Activity reference
            val wifiP2pManager = WifiP2pManagerSingleton.getInstance(applicationContext)
            val receiver = wifiP2pManager.registerReceiver()
            activeP2pReceiver = receiver
            activeP2pManager = wifiP2pManager
            val callback = object : WifiP2pManagerSingleton.WifiP2pCallback {
                override fun onWifiP2pEnabled() {
                    Log.i("DataDownload", "WiFi P2P enabled")
                }

                override fun onWifiP2pDisabled() {
                    Log.e("DataDownload", "WiFi P2P disabled")
                }

                override fun onPeersChanged(peers: Collection<android.net.wifi.p2p.WifiP2pDevice>) {
                    Log.i("DataDownload", "Found ${peers.size} P2P devices")
                }

                override fun onThisDeviceChanged(device: android.net.wifi.p2p.WifiP2pDevice) {
                    Log.i("DataDownload", "Device changed: ${device.deviceName}")
                }

                override fun onConnected(info: android.net.wifi.p2p.WifiP2pInfo) {
                    val host = info.groupOwnerAddress?.hostAddress?.trim().orEmpty()
                    if (host.isNotBlank()) {
                        p2pHostAddress = host
                        logEvent("Wi-Fi Direct host resolved: $host")
                    }
                    Log.i("DataDownload", "P2P connected: ${info.groupFormed}, host=$host")
                }

                override fun onDisconnected() {
                    p2pHostAddress = null
                    Log.i("DataDownload", "P2P disconnected")
                }

                override fun onPeerDiscoveryStarted() {
                    Log.i("DataDownload", "Peer discovery started")
                }

                override fun onPeerDiscoveryFailed(reason: Int) {
                    Log.e("DataDownload", "Peer discovery failed: $reason")
                }

                override fun onConnectRequestSent() {
                    Log.i("DataDownload", "Connect request sent")
                }

                override fun onConnectRequestFailed(reason: Int) {
                    Log.e("DataDownload", "Connect request failed: $reason")
                }

                override fun connecting() {
                    Log.i("DataDownload", "Connecting...")
                }

                override fun cancelConnect() {
                    Log.i("DataDownload", "Connect cancelled")
                }

                override fun cancelConnectFail(reason: Int) {
                    Log.e("DataDownload", "Cancel connect failed: $reason")
                }

                override fun retryAlsoFailed() {
                    Log.e("DataDownload", "Retry failed")
                }
            }

            try {
                showDownloadStatus("Preparing Wi‑Fi Direct transfer...")
                wifiP2pManager.addCallback(callback)
                val groupCreated = withTimeoutOrNull(10000L) {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        wifiP2pManager.createGroup { success ->
                            if (cont.isActive) {
                                cont.resume(success)
                            }
                        }
                    }
                } ?: false

                if (!groupCreated) {
                    logEvent("P2P group creation failed")
                    showDownloadStatus("Wi‑Fi Direct could not be started")
                    showToast("Wi‑Fi Direct could not be started")
                    return@launch
                }

                showDownloadStatus("Waiting for the glasses P2P host...")
                var finalHost = p2pHostAddress ?: fallbackDeviceIp
                repeat(6) {
                    finalHost = p2pHostAddress ?: fallbackDeviceIp
                    if (!finalHost.isNullOrBlank()) {
                        return@repeat
                    }
                    delay(1000)
                }

                if (finalHost.isNullOrBlank()) {
                    showDownloadError("The glasses P2P host address was not available")
                    return@launch
                }

                logEvent("Using P2P host: $finalHost")
                Log.i("DataDownload", "P2P host: $finalHost")
                showDownloadStatus("Downloading media from the glasses...")
                downloadMediaList(finalHost)
            } catch (e: Exception) {
                logEvent("Data download error: ${e.message}")
                Log.e("DataDownload", "Error: ${e.message}", e)
                showDownloadStatus("The download flow could not be started")
                showToast("The download flow could not be started")
            } finally {
                try {
                    wifiP2pManager.removeGroup { groupRemoved ->
                        Log.i("DataDownload", "P2P group removed: $groupRemoved")
                    }
                } catch (_: Exception) {
                }
                cleanupP2pResources()
                activeDownloadJob = null
            }
        }
    }

    private suspend fun downloadMediaList(deviceIp: String) {
        val downloader = AlbumDownloader(this)
        try {
            val mediaItems = downloader.fetchConfig(deviceIp)
            if (mediaItems.isEmpty()) {
                showDownloadError("The glasses device did not return any media entries")
                return
            }

            val downloadedFiles = mutableListOf<File>()
            mediaItems.forEach { item ->
                val file = downloader.fetchOne(deviceIp, item.fileName)
                if (file != null) {
                    downloadedFiles.add(file)
                }
            }

            if (downloadedFiles.isEmpty()) {
                showDownloadError("No media files could be downloaded")
            } else {
                showDownloadSuccess("Downloaded ${downloadedFiles.size} media files")
            }
        } catch (e: Exception) {
            Log.e("DataDownload", "Error: ${e.message}", e)
            showDownloadError("Download failed: ${e.message}")
        }
    }

    private suspend fun showDownloadStatus(message: String) {
        withContext(Dispatchers.Main) {
            binding.downloadSummary.text = "Download status: $message"
        }
    }

    private suspend fun showDownloadSuccess(message: String) {
        withContext(Dispatchers.Main) {
            Log.i("DataDownload", "SUCCESS: $message")
            binding.downloadSummary.text = "Download status: $message"
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun showDownloadError(message: String) {
        withContext(Dispatchers.Main) {
            Log.e("DataDownload", "ERROR: $message")
            binding.downloadSummary.text = "Download status: $message"
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun cleanupP2pResources() {
        try {
            activeP2pManager?.removeCallback()
        } catch (_: Exception) {
        }
        try {
            activeP2pReceiver?.let { activeP2pManager?.unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        try {
            // Release the WifiP2pManager.Channel to free framework resources.
            // This is safe to call even if no group was ever created.
            activeP2pManager?.releaseChannel()
        } catch (_: Exception) {
        }
        activeP2pReceiver = null
        activeP2pManager = null
    }

    inner class MyDeviceNotifyListener : GlassesDeviceNotifyListener() {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            logEvent("Device notification received: cmdType=$cmdType")
            Log.d("MainActivity", "Device notification: $cmdType")
        }
    }
}