package com.sdk.glassessdksample.ui

import android.Manifest
import android.app.AlertDialog
import android.app.ProgressDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.*
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.hjq.permissions.OnPermissionCallback  // ← Use OnPermissionCallback, not PermissionResultCallback
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.Constants
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.OnTheScanResult
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.databinding.ActivityDeviceBindBinding  // ← This was broken
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class DeviceBindActivity : BaseActivity() {
    private lateinit var binding: ActivityDeviceBindBinding
    private lateinit var adapter: DeviceListAdapter
    private var scanSize: Int = 0
    private val runnable = MyRunnable()

    private var loadingDialog: ProgressDialog? = null
    private var isScanning = false
    private var isConnectingToSelectedDevice = false
    private val classicBluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    if (device != null) {
                        Log.i(TAG, "Classic Bluetooth device found: name=${device.name ?: "<none>"} addr=${device.address} rssi=$rssi")
                        addDiscoveredDevice(device, rssi)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.i(TAG, "Classic Bluetooth discovery finished")
                }
            }
        }
    }

    private val myHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
        }
    }

    val deviceList = mutableListOf<SmartWatch>()
    val bleScanCallback: BleCallback = BleCallback()
    private var nativeScanner: BluetoothLeScanner? = null
    private var nativeScanCallback: ScanCallback? = null
    private val directScanCallback = object : OnTheScanResult {
        override fun onResult(device: BluetoothDevice) {
            val displayName = device.name?.takeIf { it.isNotBlank() } ?: "Unknown device"
            val smartWatch = SmartWatch(displayName, device.address, 0)
            if (!deviceList.contains(smartWatch)) {
                deviceList.add(0, smartWatch)
                deviceList.sortByDescending { it.rssi }
                adapter.notifyDataSetChanged()
            }
            Log.i(TAG, "Direct scan result: $displayName --- ${device.address}")
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Direct scan failed with code $errorCode")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceBindBinding.inflate(layoutInflater)
        EventBus.getDefault().register(this)
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(classicBluetoothReceiver, filter)
        setContentView(binding.root)
    }

    override fun onResume() {
        super.onResume()
        hideLoadingDialog()
        startBleScanIfPossible()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(messageEvent: BluetoothEvent) {
        Log.i(TAG, "onMessageEvent: connect=${messageEvent.connect}, pendingSelection=$isConnectingToSelectedDevice")
        if (messageEvent.connect && shouldFinishForConnectionEvent(isConnectingToSelectedDevice, true)) {
            hideLoadingDialog()
            isConnectingToSelectedDevice = false
            finish()
            return
        }

        if (!messageEvent.connect) {
            hideLoadingDialog()
        }
    }

    override fun setupViews() {
        super.setupViews()
        adapter = DeviceListAdapter(this, deviceList)
        binding.run {
            deviceRcv.layoutManager = LinearLayoutManager(this@DeviceBindActivity)
            deviceRcv.adapter = adapter
            titleBar.tvTitle.text = getString(R.string.scan_devices)
            titleBar.ivNavigateBefore.setOnClickListener {
                finish()
            }
        }

        adapter.notifyDataSetChanged()

        adapter.run {
            setOnItemClickListener { _, _, position ->
                myHandler.removeCallbacks(runnable)
                val smartWatch: SmartWatch = deviceList[position]
                val deviceAddress = smartWatch.deviceAddress
                if (deviceAddress.isNullOrBlank()) {
                    Toast.makeText(this@DeviceBindActivity, "No device address was found for the selected glasses", Toast.LENGTH_SHORT).show()
                    return@setOnItemClickListener
                }
                saveSelectedDeviceAddress(deviceAddress)
                val bleManager = getBleManagerOrNull()
                if (bleManager == null) {
                    Toast.makeText(this@DeviceBindActivity, "Bluetooth SDK is not ready yet", Toast.LENGTH_SHORT).show()
                    return@setOnItemClickListener
                }
                isConnectingToSelectedDevice = true
                finishScan()
                promptForGlassesIp(deviceAddress) {
                    bleManager.connectDirectly(deviceAddress)
                    showLoadingDialog(getString(R.string.text_22))
                }
            }
        }

        setOnClickListener(binding.startScan) {
            startBleScanIfPossible()
        }
    }

    private fun startBleScanIfPossible() {
        if (isScanning) {
            Log.i(TAG, "Scan already in progress; skipping duplicate start")
            return
        }
        Log.i(TAG, "Starting BLE scan check: bluetooth=${hasBluetooth(this)} location=${hasLocationPermission(this)}")
        if (!hasBluetooth(this)) {
            Log.w(TAG, "Bluetooth permissions not granted; requesting them")
            requestBluetoothPermission(this, PermissionCallback())
            return
        }
        if (!hasLocationPermission(this)) {
            Log.w(TAG, "Location permission not granted; requesting it")
            requestLocationPermission(this, PermissionCallback())
            return
        }
        val locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager
        val locationEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        if (!locationEnabled) {
            Log.w(TAG, "Location services disabled; scan blocked")
            Toast.makeText(this, "Please enable location services to scan for nearby Bluetooth devices", Toast.LENGTH_LONG).show()
            return
        }
        deviceList.clear()
        adapter.notifyDataSetChanged()
        isScanning = true
        Log.i(TAG, "Starting scan helper with bluetooth=${BluetoothUtils.isEnabledBluetooth(this@DeviceBindActivity)}")
        BleScannerHelper.getInstance().reSetCallback()
        if (!BluetoothUtils.isEnabledBluetooth(this@DeviceBindActivity)) {
            Log.w(TAG, "Bluetooth disabled; requesting enable")
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            activity?.startActivityForResult(intent, 300)
        } else {
            scanSize = 0
            try {
                BleScannerHelper.getInstance().scanDevice(this@DeviceBindActivity, null, bleScanCallback)
                Log.i(TAG, "scanDevice() called successfully")
            } catch (t: Throwable) {
                Log.e(TAG, "scanDevice() threw", t)
            }
            try {
                BleScannerHelper.getInstance().scanTheDevice(this@DeviceBindActivity, "", directScanCallback)
                Log.i(TAG, "scanTheDevice() called successfully")
            } catch (t: Throwable) {
                Log.e(TAG, "scanTheDevice() threw", t)
            }
            try {
                val manager = getBleManagerOrNull()
                if (manager != null) {
                    manager.classicBluetoothStartScan()
                    Log.i(TAG, "classicBluetoothStartScan() called successfully")
                } else {
                    Log.w(TAG, "BleOperateManager unavailable for classic Bluetooth scan")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "classicBluetoothStartScan() threw", t)
            }
            startNativeBleScan()
            myHandler.removeCallbacks(runnable)
            myHandler.postDelayed(runnable, 15 * 1000)
        }
    }

    private fun startNativeBleScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "BLUETOOTH_SCAN permission missing")
            return
        }
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Bluetooth adapter is disabled or unavailable")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BluetoothLeScanner is unavailable")
            return
        }
        nativeScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                result.device?.let { device ->
                    val scanRecord = result.scanRecord
                    Log.i(TAG, "Native scan result: name=${device.name ?: "<none>"} addr=${device.address} rssi=${result.rssi} adv=${scanRecord?.bytes?.size ?: 0} bytes")
                    if (scanRecord != null) {
                        Log.i(TAG, "Advertised UUIDs: ${scanRecord.serviceUuids?.joinToString { it.toString() } ?: "<none>"}")
                        Log.i(TAG, "Raw adv data: ${scanRecord.bytes?.joinToString(separator = " ") { "%02X".format(it) } ?: "<none>"}")
                    }
                    addDiscoveredDevice(device, result.rssi)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result -> result.device?.let { addDiscoveredDevice(it, result.rssi) } }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Native BLE scan failed with code $errorCode")
            }
        }
        nativeScanner = scanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val serviceUuid = Constants.UUID_SERVICE
        Log.i(TAG, "Native BLE scan started with service UUID ${serviceUuid ?: "<none>"}; scanning all advertisements for debugging")
        scanner.startScan(emptyList(), settings, nativeScanCallback)
    }

    private fun stopNativeBleScan() {
        val callback = nativeScanCallback
        if (callback != null) {
            nativeScanner?.stopScan(callback)
        }
        nativeScanCallback = null
        nativeScanner = null
    }

    private fun finishScan() {
        isScanning = false
        myHandler.removeCallbacks(runnable)
        BleScannerHelper.getInstance().stopScan(this@DeviceBindActivity)
        stopNativeBleScan()
    }

    private fun addDiscoveredDevice(device: BluetoothDevice, rssi: Int) {
        val displayName = device.name?.takeIf { it.isNotBlank() } ?: "Unknown device"
        val smartWatch = SmartWatch(displayName, device.address, rssi)
        if (!deviceList.contains(smartWatch)) {
            scanSize++
            deviceList.add(0, smartWatch)
            deviceList.sortByDescending { it.rssi }
            adapter.notifyDataSetChanged()
            if (scanSize > 30) {
                finishScan()
            }
        }
    }

    private fun getBleManagerOrNull(): BleOperateManager? = try {
        BleOperateManager.getInstance(application)
    } catch (t: Throwable) {
        Log.w(TAG, "BleOperateManager unavailable", t)
        null
    }

    private fun saveSelectedDeviceAddress(address: String) {
        getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .edit()
            .putString("device_address", address)
            .apply()
    }

    private fun saveConfiguredDeviceIp(ip: String) {
        getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .edit()
            .putString("device_ip", ip)
            .apply()
    }

    private fun promptForGlassesIp(deviceAddress: String, onConfirmed: () -> Unit) {
        val currentIp = getSharedPreferences("app_state", Context.MODE_PRIVATE)
            .getString("device_ip", null)
            ?.trim()
            .orEmpty()
        if (currentIp.isNotBlank()) {
            onConfirmed()
            return
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "e.g. 192.168.1.50"
            setText(currentIp)
            setSingleLine(true)
        }

        AlertDialog.Builder(this)
            .setTitle("Glasses P2P host")
            .setMessage("Enter the Wi-Fi Direct / P2P host address for $deviceAddress if the glasses shows one, or use the IP discovered from the BLE bridge as a fallback.")
            .setView(input)
            .setPositiveButton("Save and connect") { _, _ ->
                val enteredIp = input.text.toString().trim()
                if (enteredIp.isNotBlank()) {
                    saveConfiguredDeviceIp(enteredIp)
                }
                onConfirmed()
            }
            .setNegativeButton("Skip") { _, _ -> onConfirmed() }
            .setCancelable(false)
            .show()
    }

    private fun showLoadingDialog(message: String) {
        hideLoadingDialog()
        loadingDialog = ProgressDialog(this).apply {
            setMessage(message)
            setCancelable(false)
            show()
        }
    }

    internal fun shouldFinishForConnectionEvent(isConnecting: Boolean, connect: Boolean): Boolean {
        return isConnecting && connect
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    inner class MyRunnable : Runnable {
        override fun run() {
            finishScan()
            if (deviceList.isEmpty()) {
                Log.w(TAG, "Scan finished without discovering any device")
                Toast.makeText(
                    this@DeviceBindActivity,
                    "No glasses were found. Make sure the glasses are powered on, nearby, and advertising.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        finishScan()
        hideLoadingDialog()
        try {
            unregisterReceiver(classicBluetoothReceiver)
        } catch (_: IllegalArgumentException) {
            // Receiver was not registered or already unregistered
        }
        EventBus.getDefault().unregister(this)
    }

    // ← Use OnPermissionCallback, not PermissionResultCallback
    inner class PermissionCallback : OnPermissionCallback {
        override fun onGranted(permissions: MutableList<String>, all: Boolean) {
            if (all) {
                startBleScanIfPossible()
            }
        }

        override fun onDenied(permissions: MutableList<String>, never: Boolean) {
            if (never) {
                XXPermissions.startPermissionActivity(this@DeviceBindActivity, permissions)
            }
        }
    }

    inner class BleCallback : ScanWrapperCallback {
        override fun onStart() {
        }

        override fun onStop() {
        }

        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            if (device != null) {
                addDiscoveredDevice(device, rssi)
            }
        }

        override fun onScanFailed(errorCode: Int) {
        }

        override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
        }
    }
}