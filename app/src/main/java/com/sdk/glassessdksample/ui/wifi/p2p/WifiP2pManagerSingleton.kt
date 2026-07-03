package com.sdk.glassessdksample.ui.wifi.p2p

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Singleton manager for WiFi P2P (WiFi Direct) operations.
 *
 * Design notes:
 *  - Uses applicationContext internally to prevent Activity memory leaks.
 *  - Re-initializes the WifiP2pManager.Channel before creating a new group
 *    because a stale channel (from a previous session or after WiFi toggle)
 *    will cause createGroup() to fail with reason BUSY or ERROR.
 *  - The channel is closed on [releaseChannel] to free framework resources.
 */
class WifiP2pManagerSingleton private constructor(context: Context) {

    private val TAG = "WifiP2pManagerSingleton"

    // Use applicationContext so this singleton never leaks an Activity
    private val appContext: Context = context.applicationContext

    private val wifiP2pManager: WifiP2pManager =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager

    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var callback: WifiP2pCallback? = null

    @Suppress("unused")
    private val handler = Handler(Looper.getMainLooper())

    private var connected = false
    private var connecting = false

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    companion object {
        @Volatile
        private var instance: WifiP2pManagerSingleton? = null

        fun getInstance(context: Context): WifiP2pManagerSingleton {
            return instance ?: synchronized(this) {
                instance ?: WifiP2pManagerSingleton(context.applicationContext).also { instance = it }
            }
        }

        /** Call this to force a fresh singleton on next [getInstance] (e.g. after WiFi is toggled). */
        fun reset() {
            synchronized(this) {
                instance?.releaseChannel()
                instance = null
            }
        }
    }

    init {
        Log.d(TAG, "WifiP2pManagerSingleton initialized")
        initP2P()
    }

    // ─── Channel management ──────────────────────────────────────────────────

    private fun initP2P() {
        Log.d(TAG, "Initializing P2P channel...")
        try {
            wifiP2pChannel?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing old channel", e)
        }
        wifiP2pChannel = wifiP2pManager.initialize(appContext, Looper.getMainLooper()) {
            Log.w(TAG, "P2P channel disconnected — will reinit on next use")
            wifiP2pChannel = null
        }
    }

    /**
     * Re-initializes the channel. Call this before [createGroup] if the channel
     * might be stale (e.g. after WiFi was toggled or after a previous P2P session).
     */
    fun reinitChannel() {
        Log.d(TAG, "Reinitializing P2P channel")
        initP2P()
    }

    /**
     * Closes and releases the P2P channel. Call this when the component using
     * WiFi P2P is being destroyed and won't need it again soon.
     */
    fun releaseChannel() {
        try {
            wifiP2pChannel?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing channel", e)
        }
        wifiP2pChannel = null
    }

    // ─── Callback management ─────────────────────────────────────────────────

    fun addCallback(callback: WifiP2pCallback) {
        this.callback = callback
    }

    fun removeCallback() {
        callback = null
    }

    // ─── Receiver management ─────────────────────────────────────────────────

    fun registerReceiver(): BroadcastReceiver {
        val receiver = WifiP2pBroadcastReceiver(this)
        try {
            appContext.registerReceiver(receiver, intentFilter, Context.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering P2P broadcast receiver", e)
        }
        return receiver
    }

    fun unregisterReceiver(receiver: BroadcastReceiver) {
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receiver (may already be unregistered)", e)
        }
    }

    // ─── P2P Operations ──────────────────────────────────────────────────────

    /**
     * Creates a WiFi P2P group (this device becomes the Group Owner).
     *
     * Automatically reinitializes the channel first to avoid BUSY errors from
     * stale channels left over from a previous session.
     */
    fun createGroup(onResult: (Boolean) -> Unit) {
        // Always reinit the channel before creating a group to avoid using a stale one
        reinitChannel()
        val channel = wifiP2pChannel
        if (channel == null) {
            Log.e(TAG, "Cannot create group: channel is null after reinit")
            onResult(false)
            return
        }
        wifiP2pManager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "P2P group created successfully")
                onResult(true)
            }
            override fun onFailure(reason: Int) {
                val reasonStr = when (reason) {
                    WifiP2pManager.ERROR -> "ERROR"
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
                    WifiP2pManager.BUSY -> "BUSY"
                    else -> "UNKNOWN($reason)"
                }
                Log.e(TAG, "Failed to create P2P group: $reasonStr")
                onResult(false)
            }
        })
    }

    fun removeGroup(onResult: (Boolean) -> Unit) {
        val channel = wifiP2pChannel
        if (channel == null) {
            Log.w(TAG, "removeGroup skipped: channel is null")
            onResult(false)
            return
        }
        wifiP2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "P2P group removed successfully")
                connected = false
                connecting = false
                onResult(true)
            }
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to remove P2P group: $reason")
                onResult(false)
            }
        })
    }

    // ─── State event dispatchers (called by WifiP2pBroadcastReceiver) ────────

    fun onWifiP2pEnabled() {
        Log.d(TAG, "WiFi P2P enabled")
        callback?.onWifiP2pEnabled()
    }

    fun onWifiP2pDisabled() {
        Log.d(TAG, "WiFi P2P disabled")
        connected = false
        connecting = false
        callback?.onWifiP2pDisabled()
    }

    fun requestPeers() {
        val channel = wifiP2pChannel ?: return
        wifiP2pManager.requestPeers(channel, object : WifiP2pManager.PeerListListener {
            override fun onPeersAvailable(peers: WifiP2pDeviceList) {
                Log.d(TAG, "Peers available: ${peers.deviceList.size}")
                callback?.onPeersChanged(peers.deviceList)
            }
        })
    }

    fun requestConnectionInfo() {
        val channel = wifiP2pChannel ?: return
        wifiP2pManager.requestConnectionInfo(channel, object : WifiP2pManager.ConnectionInfoListener {
            override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
                Log.d(TAG, "Connection info: groupFormed=${info.groupFormed}, isGroupOwner=${info.isGroupOwner}, goAddr=${info.groupOwnerAddress?.hostAddress}")
                if (info.groupFormed) {
                    onConnected(info)
                } else {
                    onDisconnected()
                }
            }
        })
    }

    fun onConnected(info: WifiP2pInfo) {
        connected = info.groupFormed
        connecting = false
        callback?.onConnected(info)
    }

    fun onDisconnected() {
        connected = false
        connecting = false
        callback?.onDisconnected()
    }

    fun onThisDeviceChanged(device: WifiP2pDevice) {
        Log.d(TAG, "This device changed: ${device.deviceName} status=${device.status}")
        callback?.onThisDeviceChanged(device)
    }

    // Legacy stubs kept for API compat
    fun resetPeerDiscovery() {}
    fun resetFailCount() {}
    fun setConnect(connected: Boolean) {
        this.connected = connected
    }

    // ─── Callback interface ───────────────────────────────────────────────────

    interface WifiP2pCallback {
        fun onWifiP2pEnabled()
        fun onWifiP2pDisabled()
        fun onPeersChanged(peers: Collection<WifiP2pDevice>)
        fun onThisDeviceChanged(device: WifiP2pDevice)
        fun onConnected(info: WifiP2pInfo)
        fun onDisconnected()
        fun onPeerDiscoveryStarted()
        fun onPeerDiscoveryFailed(reason: Int)
        fun onConnectRequestSent()
        fun onConnectRequestFailed(reason: Int)
        fun connecting()
        fun cancelConnect()
        fun cancelConnectFail(reason: Int)
        fun retryAlsoFailed()
    }
}
