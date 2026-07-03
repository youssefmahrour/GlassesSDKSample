package com.sdk.glassessdksample.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BleIpBridgeTest {
    @Test
    fun parsesIpv4FromBlePayload() {
        val bridge = BleIpBridge()
        val payload = "ip:192.168.49.79"
        val parsed = bridge.parseIp(payload.toByteArray())
        assertEquals("192.168.49.79", parsed)
    }

    @Test
    fun parsesIpv4FromUrlStylePayload() {
        val bridge = BleIpBridge()
        val payload = "http://192.168.1.42/files/media.config"
        val parsed = bridge.parseIp(payload.toByteArray())
        assertEquals("192.168.1.42", parsed)
    }
}
