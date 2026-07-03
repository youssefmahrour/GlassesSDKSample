package com.sdk.glassessdksample.ui

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.charset.StandardCharsets

class BleIpBridge {
    private val _ip = MutableStateFlow<String?>(null)
    val ip = _ip.asStateFlow()

    fun parseIp(value: ByteArray): String? {
        val msg = String(value, StandardCharsets.UTF_8)
        val pattern = Regex("""(?:https?://)?(?:ip[:=])?(\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b)""")
        return pattern.find(msg)?.groupValues?.getOrNull(1)
    }

    fun onCharacteristicChanged(value: ByteArray): String? {
        val msg = String(value, StandardCharsets.UTF_8)
        Log.d("BleIpBridge", "Received BLE message: $msg")

        val logMessage = """
        ╔═══════════════════════════════════════════════════════════════════════════════════════════════════
        ║Thread: ${Thread.currentThread().name}
        ╟───────────────────────────────────────────────────────────────────────────────────────────────────
        ║	─ com.sdk.glassessdksample.ui.BleIpBridge.onCharacteristicChanged(BleIpBridge.kt:XX) <XX will be the line number>
        ╟───────────────────────────────────────────────────────────────────────────────────────────────────
        ║Received BLE message: $msg
        ╚═══════════════════════════════════════════════════════════════════════════════════════════════════════════
        """.trimIndent()
        Log.i("Glass", logMessage)

        val foundIp = parseIp(value)
        if (foundIp != null) {
            Log.i("BLE", "Got device IP via BLE: $foundIp")
            _ip.value = foundIp
        }
        return foundIp
    }
}
