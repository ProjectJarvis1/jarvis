package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingPermissionsTest {
    @Test fun notificationsAreAskedOnlyFromAndroid13() {
        assertEquals(listOf("mic", "camera", "contacts", "calendar"), OnboardingPermissions.list(32).map { it.key })
        assertEquals(listOf("mic", "camera", "contacts", "calendar", "notify"), OnboardingPermissions.list(33).map { it.key })
    }

    @Test fun everyPermissionSaysWhereItIsUsed() {
        OnboardingPermissions.list(36).forEach { assertTrue(it.key, it.use.isNotBlank() && it.permissions.isNotEmpty()) }
    }

    @Test fun contactsAskForCallingTooSinceCallsUseThem() {
        assertTrue("android.permission.CALL_PHONE" in OnboardingPermissions.list(36).first { it.key == "contacts" }.permissions)
    }
}
