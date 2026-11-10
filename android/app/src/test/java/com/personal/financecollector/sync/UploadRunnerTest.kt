package com.personal.financecollector.sync

import com.personal.financecollector.ConfigKeys
import com.personal.financecollector.database.UploadStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class UploadRunnerTest {

    private var now = 1_000_000L
    private val paired = mapOf(
        ConfigKeys.PAIRED to "true",
        ConfigKeys.BACKEND_URL to "https://backend.example.com",
    )
    private val config = FakeConfig(paired)
    private val store = FakeStore()

    private fun runner(api: UploadApi, token: String? = "token") = UploadRunner(
        store = store,
        api = api,
        config = config,
        credentials = object : TokenSource {
            override suspend fun token() = token
        },
        clock = { now },
        random = Random(42),
    )

    private fun events(vararg ids: String) = ids.forEachIndexed { i, id -> store.add(testEvent(id, capturedAt = 100L + i)) }

    @Test
    fun nothingIsUploadedUntilThePhoneIsPaired() = runBlocking {
        events("a")
        val api = FakeApi(ackAll())
        val unpaired = UploadRunner(store, api, FakeConfig(), object : TokenSource {
            override suspend fun token() = null
        }, { now })
        assertEquals(RunResult.NotPaired, unpaired.run())
        assertTrue(api.calls.isEmpty())
        assertEquals(UploadStatus.PENDING, store.status("a"))
    }

    @Test
    fun acceptedAndDuplicateEventsBecomeSyncedOnlyAfterTheAcknowledgement() = runBlocking {
        events("a", "b")
        val api = FakeApi({ events ->
            BatchOutcome.Acked(listOf(ItemResult("a", "ACCEPTED", null), ItemResult("b", "DUPLICATE", null)))
        })
        assertEquals(RunResult.Idle, runner(api).run())
        assertEquals(UploadStatus.SYNCED, store.status("a"))
        assertEquals(UploadStatus.SYNCED, store.status("b"))
        assertEquals(now, store.rows.getValue("a").ackAt)
        assertEquals(now.toString(), config.values[ConfigKeys.SYNC_LAST_OK_AT])
        assertEquals("", config.values[ConfigKeys.SYNC_LAST_ERROR])
    }

    @Test
    fun aBackendOutageKeepsEventsQueuedAndSchedulesAJitteredRetry() = runBlocking {
        events("a", "b")
        val api = FakeApi({ BatchOutcome.Transient("HTTP 503") })
        val result = runner(api).run()

        assertTrue(result is RunResult.RetryAt)
        val retryAt = (result as RunResult.RetryAt).atMillis
        assertTrue("first retry within 15-30s, was ${retryAt - now}", retryAt - now in 15_000..30_000)
        assertEquals(UploadStatus.RETRY, store.status("a"))
        assertEquals(UploadStatus.RETRY, store.status("b"))
        assertEquals("HTTP 503", config.values[ConfigKeys.SYNC_LAST_ERROR])
        assertNull(config.values[ConfigKeys.SYNC_LAST_OK_AT])
        assertTrue("not retried before its time", store.dueForUpload(now, 50).isEmpty())
    }

    @Test
    fun theSameEventsUploadOnceTheBackendRecovers() = runBlocking {
        events("a")
        val api = FakeApi({ BatchOutcome.Transient("IOException") }, ackAll())
        val first = runner(api).run() as RunResult.RetryAt
        assertEquals(UploadStatus.RETRY, store.status("a"))

        now = first.atMillis + 1
        assertEquals(RunResult.Idle, runner(api).run())
        assertEquals(UploadStatus.SYNCED, store.status("a"))
        assertEquals(2, api.calls.size)
        assertEquals("", config.values[ConfigKeys.SYNC_LAST_ERROR])
    }

    @Test
    fun repeatedFailuresBackOffFurtherEachTime() = runBlocking {
        events("a")
        val api = FakeApi({ BatchOutcome.Transient("HTTP 500") })
        var previousDelay = 0L
        repeat(4) {
            val retryAt = (runner(api).run() as RunResult.RetryAt).atMillis
            val delay = retryAt - now
            assertTrue("delay $delay should not shrink below half of the previous ($previousDelay)", delay >= previousDelay / 2)
            previousDelay = delay
            now = retryAt + 1
        }
        assertTrue("fourth delay should be at least 120s*0.5, was $previousDelay", previousDelay >= 120_000 / 2)
    }

    @Test
    fun anUnauthorizedAnswerNeverDropsOrBurnsEvents() = runBlocking {
        events("a")
        val api = FakeApi({ BatchOutcome.Unauthorized })
        assertEquals(RunResult.Unauthorized, runner(api).run())
        assertEquals(UploadStatus.PENDING, store.status("a"))
        assertEquals(0, store.rows.getValue("a").attempts)
        assertEquals(UploadRunner.UNAUTHORIZED, config.values[ConfigKeys.SYNC_LAST_ERROR])
    }

    @Test
    fun aRejectedItemIsParkedForReviewAndOthersAreStillAcknowledged() = runBlocking {
        events("a", "b")
        val api = FakeApi({
            BatchOutcome.Acked(listOf(ItemResult("a", "REJECTED", "invalid fields: payload"), ItemResult("b", "ACCEPTED", null)))
        })
        runner(api).run()
        assertEquals(UploadStatus.NEEDS_REVIEW, store.status("a"))
        assertEquals("invalid fields: payload", store.rows.getValue("a").error)
        assertEquals(UploadStatus.SYNCED, store.status("b"))
        assertTrue(store.dueForUpload(now + 10_000_000, 50).isEmpty())
    }

    @Test
    fun anEventMissingFromTheResponseIsNotTreatedAsStored() = runBlocking {
        events("a", "b")
        val api = FakeApi({ BatchOutcome.Acked(listOf(ItemResult("a", "ACCEPTED", null))) })
        val result = runner(api).run()
        assertEquals(UploadStatus.SYNCED, store.status("a"))
        assertEquals(UploadStatus.RETRY, store.status("b"))
        assertTrue(result is RunResult.RetryAt)
        assertNull(config.values[ConfigKeys.SYNC_LAST_OK_AT])
    }

    @Test
    fun eventsLeftUploadingByAKilledRunAreSentAgain() = runBlocking {
        store.add(testEvent("a"), UploadStatus.UPLOADING)
        val api = FakeApi(ackAll("DUPLICATE"))
        runner(api).run()
        assertEquals(listOf(listOf("a")), api.calls)
        assertEquals(UploadStatus.SYNCED, store.status("a"))
    }

    @Test
    fun aClientErrorParksEventsAsFailedWithoutDeletingThem() = runBlocking {
        events("a", "b")
        val api = FakeApi({ BatchOutcome.Rejected("HTTP 400") })
        runner(api).run()
        assertEquals(UploadStatus.FAILED_PERMANENTLY, store.status("a"))
        assertEquals(UploadStatus.FAILED_PERMANENTLY, store.status("b"))
        assertNotNull(store.rows["a"])
        assertEquals("HTTP 400", store.rows.getValue("a").error)
    }

    @Test
    fun largeBacklogsAreSentInBatchesOfTen() = runBlocking {
        (1..25).forEach { store.add(testEvent("e$it", capturedAt = it.toLong())) }
        val api = FakeApi(ackAll())
        assertEquals(RunResult.Idle, runner(api).run())
        assertEquals(listOf(10, 10, 5), api.calls.map { it.size })
        assertTrue(store.rows.values.all { it.status == UploadStatus.SYNCED })
    }

    @Test
    fun oldestEventsAreSentFirst() = runBlocking {
        store.add(testEvent("late", capturedAt = 500))
        store.add(testEvent("early", capturedAt = 100))
        val api = FakeApi(ackAll())
        runner(api).run()
        assertEquals(listOf("early", "late"), api.calls.single())
    }
}
