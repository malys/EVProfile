package com.evsuite.profile.service

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-910] Starting with API 34, a foreground service without a foregroundServiceType does
 * throw MissingForegroundServiceTypeException by startForeground(). No effect on AAOS 9,
 * but the lack would revert to a crash at the first SDK bump — hence this guardrail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForegroundServiceTypeTest {

    @Test
    fun `service declares a foregroundServiceType`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, EVProfileService::class.java),
            PackageManager.GET_META_DATA
        )
        assertNotEquals(
            "EVProfileService must declare a foregroundServiceType",
            0, info.foregroundServiceType
        )
    }

    @Test
    fun `declared type is connectedDevice`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, EVProfileService::class.java),
            PackageManager.GET_META_DATA
        )
        // The type must remain consistent with the FOREGROUND_SERVICE_CONNECTED_DEVICE permission
        // declared in the Manifest: changing one without the other crashes at startup on API 34+.
        assertEquals(
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            info.foregroundServiceType
        )
    }
}
