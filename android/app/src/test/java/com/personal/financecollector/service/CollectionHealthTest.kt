package com.personal.financecollector.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionHealthTest {

    @Test
    fun grantedPermissionAloneIsNotActive() {
        val h = CollectionHealth.notificationListener(permissionGranted = true, paused = false, connected = false, enabledAppCount = 2)
        assertEquals(SourceState.DISCONNECTED, h.state)
    }

    @Test
    fun missingPermissionIsReported() {
        val h = CollectionHealth.notificationListener(false, false, false, 2)
        assertEquals(SourceState.PERMISSION_REQUIRED, h.state)
    }

    @Test
    fun pauseOverridesEverything() {
        val h = CollectionHealth.notificationListener(true, true, true, 2)
        assertEquals(SourceState.DISABLED, h.state)
    }

    @Test
    fun connectedWithPermissionIsActive() {
        val h = CollectionHealth.notificationListener(true, false, true, 1)
        assertEquals(SourceState.ACTIVE, h.state)
    }
}
