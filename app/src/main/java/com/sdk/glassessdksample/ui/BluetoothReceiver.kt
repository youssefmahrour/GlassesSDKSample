package com.sdk.glassessdksample.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import org.greenrobot.eventbus.EventBus

/**
 * System-level Bluetooth state broadcast receiver.
 *
 * Handles:
 *  - Bluetooth adapter ON/OFF state changes → reconnect or disconnect BLE
 *  - Classic Bluetooth device discovery (ACTION_FOUND) → trigger JieLi pairing
 *
 * Registered in [MyApplication.initReceiver] and stored so it can be
 * unregistered in [MyApplication.onTerminate].
 */
class BluetoothReceiver : BroadcastReceiver() {

    private fun getBleManagerOrNull(context: Context): BleOperateManager? = try {
        val app = context.applicationContext as? Application ?: MyApplication.getInstance()
        BleOperateManager.getInstance(app)
    } catch (t: Throwable) {
        Log.w(TAG, "BleOperateManager unavailable", t)
        null
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {

            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val connectState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                val bleManager = getBleManagerOrNull(context)
                when (connectState) {
                    BluetoothAdapter.STATE_OFF -> {
                        Log.i(TAG, "Bluetooth turned OFF")
                        bleManager?.setBluetoothTurnOff(false)
                        bleManager?.disconnect()
                        EventBus.getDefault().post(BluetoothEvent(false))
                    }
                    BluetoothAdapter.STATE_ON -> {
                        Log.i(TAG, "Bluetooth turned ON")
                        bleManager?.setBluetoothTurnOff(true)
                        val address = try {
                            DeviceManager.getInstance().deviceAddress
                        } catch (t: Throwable) {
                            Log.w(TAG, "DeviceManager unavailable while reconnecting", t)
                            null
                        }
                        if (!address.isNullOrBlank()) {
                            Log.i(TAG, "Reconnecting to saved device: $address")
                            bleManager?.reConnectMac = address
                            bleManager?.connectDirectly(address)
                        } else {
                            Log.w(TAG, "Bluetooth turned ON but no saved device address")
                            EventBus.getDefault().post(BluetoothEvent(false))
                        }
                    }
                    else -> {
                        // Transitional states (TURNING_ON, TURNING_OFF) — no action needed
                        Log.v(TAG, "Bluetooth transitional state: $connectState")
                    }
                }
            }

            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                // Future: handle bond state transitions if needed
            }

            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                // Future: handle ACL connection events if needed
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                // Future: handle ACL disconnection events if needed
            }

            BluetoothDevice.ACTION_FOUND -> {
                // Use type-safe getParcelableExtra on API 33+
                val device: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                if (device != null) {
                    Log.d(TAG, "Classic BT device found: name=${device.name ?: "<none>"} addr=${device.address}")
                    // 发现设备，当蓝牙地址和当前 BLE 地址相等时调用配对
                    getBleManagerOrNull(context)?.createBondBluetoothJieLi(device)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BluetoothReceiver"
    }
}
