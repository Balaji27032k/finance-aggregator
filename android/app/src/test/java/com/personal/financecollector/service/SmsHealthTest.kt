package com.personal.financecollector.service

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsHealthTest {

    private fun health(
        granted: Boolean = true,
        paused: Boolean = false,
        scan: SmsScanState = SmsScanState.COMPLETE,
        senders: Int = 4,
        error: String? = null,
    ) = CollectionHealth.sms(granted, paused, scan, senders, error)

    @Test
    fun missingPermissionIsReportedNotActive() {
        assertEquals(SourceState.PERMISSION_REQUIRED, health(granted = false).state)
    }

    @Test
    fun pauseOverridesPermissionState() {
        assertEquals(SourceState.DISABLED, health(granted = false, paused = true).state)
    }

    @Test
    fun noEnabledSendersIsDisabled() {
        assertEquals(SourceState.DISABLED, health(senders = 0).state)
    }

    @Test
    fun scanProgressIsNotShownAsActive() {
        assertEquals(SourceState.SYNC_PENDING, health(scan = SmsScanState.RUNNING).state)
        assertEquals(SourceState.SYNC_PENDING, health(scan = SmsScanState.NOT_STARTED).state)
    }

    @Test
    fun failedScanIsAnError() {
        assertEquals(SourceState.ERROR, health(scan = SmsScanState.FAILED, error = "IllegalStateException").state)
    }

    @Test
    fun completedScanWithPermissionIsActive() {
        assertEquals(SourceState.ACTIVE, health().state)
    }

    @Test
    fun unknownStoredStateFallsBackToNotStarted() {
        assertEquals(SmsScanState.NOT_STARTED, SmsScanState.parse(null))
        assertEquals(SmsScanState.NOT_STARTED, SmsScanState.parse("garbage"))
        assertEquals(SmsScanState.COMPLETE, SmsScanState.parse("COMPLETE"))
    }
}
