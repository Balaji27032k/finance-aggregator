package com.personal.financecollector.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudHealthTest {

    @Test
    fun unpairedIsNotActive() {
        assertEquals(SourceState.DISCONNECTED, CollectionHealth.cloud(false, 5, null, null).state)
    }

    @Test
    fun pairedButNeverUploadedIsPendingNotActive() {
        assertEquals(SourceState.SYNC_PENDING, CollectionHealth.cloud(true, 0, null, null).state)
    }

    @Test
    fun waitingEventsAreShownAsPending() {
        assertEquals(SourceState.SYNC_PENDING, CollectionHealth.cloud(true, 12, 1000L, null).state)
    }

    @Test
    fun aFailedUploadIsAnErrorEvenIfAnEarlierOneSucceeded() {
        val h = CollectionHealth.cloud(true, 3, 1000L, "HTTP 503")
        assertEquals(SourceState.ERROR, h.state)
    }

    @Test
    fun aRejectedDeviceAsksToPairAgain() {
        val h = CollectionHealth.cloud(true, 3, 1000L, "unauthorized")
        assertEquals(SourceState.ERROR, h.state)
        assertEquals("The backend rejected this device. Pair again.", h.detail)
    }

    @Test
    fun caughtUpAfterASuccessfulUploadIsActive() {
        assertEquals(SourceState.ACTIVE, CollectionHealth.cloud(true, 0, 1000L, "").state)
    }
}
