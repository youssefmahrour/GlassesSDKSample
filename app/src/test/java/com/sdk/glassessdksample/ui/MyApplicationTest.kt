package com.sdk.glassessdksample.ui

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = MyApplication::class, sdk = [28])
class MyApplicationTest {

    @Test
    fun `application singleton should be initialized safely`() {
        val app = ApplicationProvider.getApplicationContext<MyApplication>()
        assertSame(app, MyApplication.getInstance())
    }

    @Test
    fun `connection event should only finish scan when a pending selection is connecting`() {
        val activity = DeviceBindActivity()
        assertFalse(activity.shouldFinishForConnectionEvent(false, true))
        assertFalse(activity.shouldFinishForConnectionEvent(true, false))
        assertTrue(activity.shouldFinishForConnectionEvent(true, true))
    }
}
