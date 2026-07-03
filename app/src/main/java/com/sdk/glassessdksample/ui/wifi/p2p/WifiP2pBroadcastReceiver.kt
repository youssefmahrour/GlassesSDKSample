package com.sdk.glassessdksample.ui.wifi.p2p

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log

/**
 * Broadcast receiver for WiFi P2P state changes.
 *
 * Handles all four WiFi Direct broadcast actions and delegates to
 * [WifiP2pManagerSingleton] for state management and callback dispatch.
 *
 * NetworkInfo note:
 *   NetworkInfo is deprecated in API 29 but there is no direct replacement for
 *   the isConnected flag in WIFI_P2P_CONNECTION_CHANGED_ACTION broadcasts.
 *   The workaround is to ALWAYS call requestConnectionInfo() and let
 *   WifiP2pInfo.groupFormed decide whether we're connected or not.
 *   This is more reliable than relying on NetworkInfo in any API level.
 */
class WifiP2pBroadcastReceiver(
    private val singleton: WifiP2pManagerSingleton
) : BroadcastReceiver() {

    companion object {
        private const val TAG = "WifiP2pBroadcastReceiver"
    }

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {

            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    Log.d(TAG, "WiFi P2P is enabled")
                    singleton.onWifiP2pEnabled()
                } else {
                    Log.d(TAG, "WiFi P2P is disabled")
                    singleton.onWifiP2pDisabled()
                }
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d(TAG, "Peers list changed — requesting peer list")
                singleton.requestPeers()
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                // NetworkInfo is deprecated in API 29+ but there is no direct replacement
                // in this broadcast. We check it for a quick log hint, then ALWAYS call
                // requestConnectionInfo() which uses WifiP2pInfo.groupFormed — the
                // authoritative source of truth.
                val networkInfo: NetworkInfo? =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    } else {
                        // On API 29+ getParcelableExtra for NetworkInfo may still work but
                        // returns deprecated data — we log it but don't rely on it.
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }

                Log.d(TAG, "Connection state changed: networkInfoConnected=${networkInfo?.isConnected} — always querying WifiP2pInfo")

                // Always request connection info regardless of NetworkInfo.isConnected.
                // WifiP2pInfo.groupFormed is the reliable flag — if the group is gone,
                // requestConnectionInfo() will call onDisconnected() via the singleton.
                singleton.requestConnectionInfo()
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device: WifiP2pDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                device?.let {
                    Log.d(TAG, "This device changed: ${it.deviceName} status=${it.status}")
                    singleton.onThisDeviceChanged(it)
                }
            }
        }
    }
}
