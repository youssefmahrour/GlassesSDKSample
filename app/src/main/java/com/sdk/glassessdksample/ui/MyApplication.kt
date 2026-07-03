package com.sdk.glassessdksample.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oudmon.ble.base.bluetooth.BleAction
import com.oudmon.ble.base.bluetooth.BleBaseControl
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import java.io.File
import kotlin.properties.Delegates

class MyApplication : Application() {

    var hardwareVersion: String = ""
    var firmwareVersion: String = ""

    /**
     * Holds a reference to the system Bluetooth state receiver so it can be
     * properly unregistered in [onTerminate]. Without storing it, the receiver
     * leaks for the lifetime of the process.
     */
    private var deviceReceiver: BluetoothReceiver? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        CONTEXT = applicationContext
        initBle()
    }

    private fun initBle() {
        initReceiver()
        val intentFilter = BleAction.getIntentFilter()
        val myBleReceiver = MyBluetoothReceiver()
        LocalBroadcastManager.getInstance(CONTEXT)
            .registerReceiver(myBleReceiver, intentFilter)
        BleBaseControl.getInstance(CONTEXT).setmContext(this)
    }

    private fun initReceiver() {
        try {
            LargeDataHandler.getInstance()
            val bleManager = BleOperateManager.getInstance(this)
            bleManager.setApplication(this)
            bleManager.init()
            bleManager.setReady(true)
        } catch (t: Throwable) {
            Log.w(TAG, "SDK Bluetooth initialization skipped", t)
        }

        val receiver = BluetoothReceiver()
        deviceReceiver = receiver

        val deviceFilter: IntentFilter = BleAction.getDeviceIntentFilter()
        // Also listen for ACTION_FOUND so nearby classic Bluetooth devices trigger pairing
        deviceFilter.addAction(BluetoothDevice.ACTION_FOUND)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(receiver, deviceFilter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, deviceFilter)
            }
            Log.d(TAG, "BluetoothReceiver registered successfully")
        } catch (t: Throwable) {
            Log.w(TAG, "Device receiver registration skipped", t)
        }
    }

    override fun onTerminate() {
        // Unregister the Bluetooth state receiver to avoid receiver leaks.
        // Note: onTerminate() is not guaranteed to be called on real devices
        // (it is called in emulators and test environments). For production,
        // the OS reclaims resources when the process dies, but this is still
        // best-practice for clean teardown.
        deviceReceiver?.let {
            try {
                unregisterReceiver(it)
                Log.d(TAG, "BluetoothReceiver unregistered")
            } catch (t: Throwable) {
                Log.w(TAG, "BluetoothReceiver unregister skipped", t)
            }
        }
        deviceReceiver = null
        super.onTerminate()
    }

    fun getDeviceIntentFilter(): IntentFilter? {
        val intentFilter = IntentFilter()
        intentFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        intentFilter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        intentFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        return intentFilter
    }

    fun getAppRootFile(context: Context): File {
        return if (context.getExternalFilesDir("") != null) {
            context.getExternalFilesDir("")!!
        } else {
            context.externalCacheDir ?: context.cacheDir
        }
    }

    companion object {
        private const val TAG = "MyApplication"
        var CONTEXT: Context by Delegates.notNull()
        private var instance: MyApplication? = null

        fun getInstance(): MyApplication {
            return instance ?: throw IllegalStateException("MyApplication has not been initialized")
        }
    }
}
