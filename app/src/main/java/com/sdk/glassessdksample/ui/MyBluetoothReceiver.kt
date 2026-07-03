package com.sdk.glassessdksample.ui

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.Constants
import com.oudmon.ble.base.communication.LargeDataHandler
import org.greenrobot.eventbus.EventBus

private fun saveConfiguredDeviceIp(ip: String) {
    try {
        MyApplication.getInstance().getSharedPreferences("app_state", Application.MODE_PRIVATE)
            .edit()
            .putString("device_ip", ip)
            .apply()
    } catch (_: Throwable) {
    }
}

class MyBluetoothReceiver : QCBluetoothCallbackCloneReceiver() {
    override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
        Log.e("connectStatue", "---connectStatue connected=$connected")
        if (device != null) {
            DeviceManager.getInstance().setDeviceAddress(device.address)
            if (!device.name.isNullOrBlank()) {
                DeviceManager.getInstance().setDeviceName(device.name)
            }
            if (connected) {
                EventBus.getDefault().post(BluetoothEvent(true))
            } else {
                EventBus.getDefault().post(BluetoothEvent(false))
            }
        } else {
            EventBus.getDefault().post(BluetoothEvent(false))
        }
    }

    override fun onServiceDiscovered() {
        try {
            val handler = LargeDataHandler.getInstance()
            handler.initEnable()
            handler.packageLength()
            handler.syncDeviceInfo { _, response ->
                val payload = response?.toString().orEmpty()
                Log.i("GlassesData", "Device info response: $payload")
                EventBus.getDefault().post(GlassesDataEvent("device-info", payload))
            }
            handler.syncBattery()
        } catch (t: Throwable) {
            Log.w("MyBluetoothReceiver", "LargeDataHandler init skipped", t)
        }
        EventBus.getDefault().post(BluetoothEvent(true))
        Log.e("onServiceDiscovered", "---onServiceDiscovered")
        try {
            BleOperateManager.getInstance(MyApplication.getInstance() as Application).setReady(true)
        } catch (t: Throwable) {
            Log.w("MyBluetoothReceiver", "BleOperateManager ready state update skipped", t)
        }
    }

    override fun onCharacteristicChange(address: String?, uuid: String?, data: ByteArray?) {
        if (data == null || data.isEmpty()) return
        val bridge = BleIpBridge()
        val parsedIp = bridge.parseIp(data)
        
        // Use StandardCharsets.UTF_8 as requested and eliminate the replacement characters explicitly
        val utf8String = String(data, java.nio.charset.StandardCharsets.UTF_8)
        val cleanString = utf8String.replace("\uFFFD", "").trim()
        val hexString = data.joinToString(" ") { "%02X".format(it) }
        
        val message = if (cleanString.isNotBlank()) cleanString else "HEX: $hexString"
        
        Log.i("GlassesData", "Incoming glasses data: $message (Raw: $hexString)")
        if (!parsedIp.isNullOrBlank()) {
            saveConfiguredDeviceIp(parsedIp)
            Log.i("GlassesData", "Saved discovered glasses IP: $parsedIp")
        }
        EventBus.getDefault().post(GlassesDataEvent("characteristic", message, parsedIp))
    }

    override fun onCharacteristicRead(uuid: String?, data: ByteArray?) {
        if (uuid != null && data != null) {
            val version = String(data, java.nio.charset.StandardCharsets.UTF_8).replace("\uFFFD", "").trim()
            when (uuid) {
                Constants.CHAR_FIRMWARE_REVISION.toString() -> {
                    Log.e("rom----", version)
                    MyApplication.getInstance().firmwareVersion = version
                    EventBus.getDefault().post(GlassesDataEvent("firmware", version))
                }
                Constants.CHAR_HW_REVISION.toString() -> {
                    Log.e("hardware----", version)
                    MyApplication.getInstance().hardwareVersion = version
                    EventBus.getDefault().post(GlassesDataEvent("hardware", version))
                }
            }
        }
    }
}
