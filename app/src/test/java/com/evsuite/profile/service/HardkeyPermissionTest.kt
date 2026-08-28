package com.evsuite.profile.service

import android.content.pm.PermissionInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-902] The broadcast hardkey controls vehicle entries. The only barrier against
 * a third-party issuer is the level of protection of the permission required: if it
 * falls back to "normal", any app can request it and forge the action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HardkeyPermissionTest {

    @Test
    fun `hardkey permission is declared as signature`() {
        val pm = ApplicationProvider.getApplicationContext<android.content.Context>().packageManager
        val info = pm.getPermissionInfo(EVProfileService.HARDKEY_PERMISSION, 0)
        assertEquals(
            "The hardkey permission must remain signature-only",
            PermissionInfo.PROTECTION_SIGNATURE,
            info.protection
        )
    }

    @Test
    fun `permission name matches the Manifest`() {
        assertEquals("com.evsuite.profile.permission.RECEIVE_HARDKEY",
            EVProfileService.HARDKEY_PERMISSION)
    }
}
