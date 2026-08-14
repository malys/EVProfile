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
 * [T-910] À partir d'API 34, un service de premier plan sans foregroundServiceType fait
 * lever MissingForegroundServiceTypeException par startForeground(). Sans effet sur AAOS 9,
 * mais le manque redeviendrait un crash au premier bump de SDK — d'où ce garde-fou.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForegroundServiceTypeTest {

    @Test
    fun `le service declare un foregroundServiceType`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, EVProfileService::class.java),
            PackageManager.GET_META_DATA
        )
        assertNotEquals(
            "EVProfileService doit déclarer un foregroundServiceType",
            0, info.foregroundServiceType
        )
    }

    @Test
    fun `le type declare est connectedDevice`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, EVProfileService::class.java),
            PackageManager.GET_META_DATA
        )
        // Le type doit rester cohérent avec la permission FOREGROUND_SERVICE_CONNECTED_DEVICE
        // déclarée au Manifest : changer l'un sans l'autre crashe au démarrage sur API 34+.
        assertEquals(
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            info.foregroundServiceType
        )
    }
}
