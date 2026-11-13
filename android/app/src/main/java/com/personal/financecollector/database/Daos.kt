package com.personal.financecollector.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertEvent(event: RawFinancialEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPending(pending: PendingUploadEntity): Long

    /** Stores the event and its upload-queue row atomically. Returns false if the event already exists. */
    @Transaction
    open suspend fun insertWithQueue(event: RawFinancialEventEntity): Boolean {
        if (insertEvent(event) == -1L) return false
        insertPending(
            PendingUploadEntity(
                localEventId = event.localEventId,
                enqueuedAt = event.capturedTimestamp,
                nextAttemptAt = event.capturedTimestamp,
            ),
        )
        return true
    }

    @Query(
        "SELECT COUNT(*) FROM raw_financial_events WHERE sourceType = :type AND sourceApplication = :app " +
            "AND payloadHash = :hash AND eventTimestamp BETWEEN :from AND :to",
    )
    abstract suspend fun countNear(type: SourceType, app: String, hash: String, from: Long, to: Long): Int

    @Query("SELECT * FROM raw_financial_events WHERE sourceType = 'NOTIFICATION' ORDER BY capturedTimestamp ASC")
    abstract suspend fun notificationEvents(): List<RawFinancialEventEntity>

    /** Newest message first by when it was sent, not when this phone read it (a history scan reads old messages today). */
    @Query("SELECT * FROM raw_financial_events ORDER BY eventTimestamp DESC, capturedTimestamp DESC LIMIT :limit")
    abstract fun observeRecent(limit: Int): Flow<List<RawFinancialEventEntity>>

    @Query("SELECT * FROM raw_financial_events ORDER BY eventTimestamp DESC, capturedTimestamp DESC LIMIT :limit")
    abstract suspend fun recentList(limit: Int): List<RawFinancialEventEntity>

    /** Messages that were captured but could not be read as a transaction. */
    @Query(
        "SELECT * FROM raw_financial_events WHERE uploadStatus = 'NEEDS_REVIEW' AND sourceType != 'NOTIFICATION' " +
            "AND IFNULL(lastError, '') = 'message format not recognised yet' " +
            "ORDER BY eventTimestamp DESC, capturedTimestamp DESC LIMIT :limit",
    )
    abstract fun observeNotUnderstood(limit: Int): Flow<List<RawFinancialEventEntity>>

    @Query("DELETE FROM raw_financial_events WHERE sourceType = 'NOTIFICATION' AND sourceApplication = :app")
    abstract suspend fun deleteEventsFromApp(app: String)

    @Query("SELECT COUNT(*) FROM raw_financial_events WHERE uploadStatus IN ('PENDING','UPLOADING','RETRY')")
    abstract fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM raw_financial_events WHERE uploadStatus IN ('FAILED_PERMANENTLY','NEEDS_REVIEW') AND sourceType != 'NOTIFICATION'")
    abstract fun observeFailedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM raw_financial_events WHERE parserStatus = 'NEEDS_REVIEW' AND sourceType != 'NOTIFICATION'")
    abstract fun observeNeedsReviewCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM raw_financial_events")
    abstract fun observeTotalCount(): Flow<Int>

    @Query("SELECT MAX(ackTimestamp) FROM raw_financial_events")
    abstract fun observeLastAckTimestamp(): Flow<Long?>

    @Query("DELETE FROM raw_financial_events")
    abstract suspend fun deleteAllEvents()

    @Query(
        "SELECT e.* FROM raw_financial_events e INNER JOIN pending_uploads p ON p.localEventId = e.localEventId " +
            "WHERE e.uploadStatus IN ('PENDING','RETRY') AND p.nextAttemptAt <= :now " +
            "ORDER BY e.capturedTimestamp ASC LIMIT :limit",
    )
    abstract suspend fun dueForUpload(now: Long, limit: Int): List<RawFinancialEventEntity>

    @Query("UPDATE raw_financial_events SET uploadStatus = 'UPLOADING' WHERE localEventId IN (:ids)")
    abstract suspend fun markUploading(ids: List<String>)

    @Query("UPDATE raw_financial_events SET uploadStatus = 'SYNCED', ackTimestamp = :ackAt, lastError = NULL WHERE localEventId = :id")
    abstract suspend fun markSynced(id: String, ackAt: Long)

    @Query("DELETE FROM pending_uploads WHERE localEventId = :id")
    abstract suspend fun deletePending(id: String)

    @Query("UPDATE raw_financial_events SET uploadStatus = :status, lastError = :error WHERE localEventId = :id")
    abstract suspend fun setUploadResult(id: String, status: UploadStatus, error: String?)

    @Query("UPDATE raw_financial_events SET uploadStatus = 'RETRY', retryCount = retryCount + 1, lastError = :error WHERE localEventId = :id")
    abstract suspend fun markRetry(id: String, error: String)

    @Query("UPDATE raw_financial_events SET uploadStatus = 'PENDING' WHERE localEventId IN (:ids)")
    abstract suspend fun revertToPending(ids: List<String>)

    /** Only one upload worker runs at a time, so any UPLOADING row at the start of a run is left over from a killed run. */
    @Query("UPDATE raw_financial_events SET uploadStatus = 'RETRY' WHERE uploadStatus = 'UPLOADING'")
    abstract suspend fun resetUploading()

    @Query("SELECT attempts FROM pending_uploads WHERE localEventId = :id")
    abstract suspend fun attemptsOf(id: String): Int?

    @Query("UPDATE pending_uploads SET attempts = attempts + 1, nextAttemptAt = :next WHERE localEventId = :id")
    abstract suspend fun scheduleRetry(id: String, next: Long)

    @Query("UPDATE raw_financial_events SET uploadStatus = 'PENDING', retryCount = 0, lastError = NULL WHERE uploadStatus IN ('FAILED_PERMANENTLY','NEEDS_REVIEW')")
    abstract suspend fun requeueFailedEvents()

    @Query(
        "UPDATE pending_uploads SET attempts = 0, nextAttemptAt = :now WHERE localEventId IN " +
            "(SELECT localEventId FROM raw_financial_events WHERE uploadStatus IN ('PENDING','RETRY'))",
    )
    abstract suspend fun resetBackoff(now: Long)

    @Query(
        "SELECT MIN(p.nextAttemptAt) FROM pending_uploads p INNER JOIN raw_financial_events e " +
            "ON e.localEventId = p.localEventId WHERE e.uploadStatus IN ('PENDING','RETRY')",
    )
    abstract suspend fun minNextAttemptAt(): Long?

    @Query(
        "INSERT OR IGNORE INTO pending_uploads (localEventId, enqueuedAt, nextAttemptAt, attempts) " +
            "SELECT localEventId, :now, :now, 0 FROM raw_financial_events " +
            "WHERE uploadStatus IN ('FAILED_PERMANENTLY','NEEDS_REVIEW')",
    )
    abstract suspend fun enqueueFailedForRetry(now: Long)

    @Transaction
    open suspend fun requeueFailed(now: Long) {
        enqueueFailedForRetry(now)
        requeueFailedEvents()
        resetBackoff(now)
    }

    @Query(
        "INSERT OR IGNORE INTO pending_uploads (localEventId, enqueuedAt, nextAttemptAt, attempts) " +
            "SELECT localEventId, :now, :now, 0 FROM raw_financial_events WHERE uploadStatus = 'SYNCED'",
    )
    abstract suspend fun enqueueSyncedForResend(now: Long)

    @Query("UPDATE raw_financial_events SET uploadStatus = 'PENDING', retryCount = 0, lastError = NULL, ackTimestamp = NULL WHERE uploadStatus = 'SYNCED'")
    abstract suspend fun requeueSynced()

    /** Queues every stored event again; the backend ignores any it already has. */
    @Transaction
    open suspend fun resendAll(now: Long) {
        enqueueSyncedForResend(now)
        requeueSynced()
        requeueFailed(now)
    }

    @Transaction
    open suspend fun acknowledge(id: String, ackAt: Long) {
        markSynced(id, ackAt)
        deletePending(id)
    }

    @Transaction
    open suspend fun finishWithStatus(id: String, status: UploadStatus, reason: String) {
        setUploadResult(id, status, reason)
        deletePending(id)
    }
}

@Dao
abstract class SourceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(source: ConnectedSourceEntity): Long

    @Query("UPDATE connected_sources SET enabled = :enabled WHERE sourceType = :type AND identifier = :identifier")
    abstract suspend fun setEnabled(type: SourceType, identifier: String, enabled: Boolean)

    @Query("SELECT * FROM connected_sources WHERE sourceType = :type ORDER BY displayName COLLATE NOCASE")
    abstract fun observeByType(type: SourceType): Flow<List<ConnectedSourceEntity>>

    @Query("SELECT identifier FROM connected_sources WHERE sourceType = :type AND enabled = 1")
    abstract suspend fun enabledIdentifiers(type: SourceType): List<String>

    @Query("DELETE FROM connected_sources WHERE sourceType = :type AND identifier = :identifier")
    abstract suspend fun delete(type: SourceType, identifier: String)

    @Transaction
    open suspend fun upsert(type: SourceType, identifier: String, displayName: String, enabled: Boolean, now: Long) {
        insertIgnore(ConnectedSourceEntity(sourceType = type, identifier = identifier, displayName = displayName, enabled = enabled, addedAt = now))
        setEnabled(type, identifier, enabled)
    }
}

@Dao
abstract class StatusDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(status: CollectionStatusEntity): Long

    @Query("SELECT * FROM collection_status WHERE `key` = :key")
    abstract fun observe(key: String): Flow<CollectionStatusEntity?>

    @Query("UPDATE collection_status SET lastEventAt = :at WHERE `key` = :key")
    abstract suspend fun setLastEvent(key: String, at: Long)

    @Query("UPDATE collection_status SET lastConnectedAt = :at WHERE `key` = :key")
    abstract suspend fun setConnected(key: String, at: Long)

    @Query("UPDATE collection_status SET lastDisconnectedAt = :at WHERE `key` = :key")
    abstract suspend fun setDisconnected(key: String, at: Long)

    @Query("UPDATE collection_status SET droppedHiddenContent = droppedHiddenContent + 1 WHERE `key` = :key")
    abstract suspend fun incrementHidden(key: String)

    @Query("UPDATE collection_status SET droppedFiltered = droppedFiltered + 1 WHERE `key` = :key")
    abstract suspend fun incrementFiltered(key: String)

    @Query("DELETE FROM collection_status")
    abstract suspend fun deleteAll()

    @Transaction
    open suspend fun ensure(key: String) {
        insertIgnore(CollectionStatusEntity(key = key))
    }
}

@Dao
interface ConfigDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(config: DeviceConfigurationEntity)

    @Query("SELECT value FROM device_configuration WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM device_configuration WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Query("DELETE FROM device_configuration WHERE `key` IN (:keys)")
    suspend fun deleteKeys(keys: List<String>)
}

suspend fun ConfigDao.set(key: String, value: String) =
    put(DeviceConfigurationEntity(key, value, System.currentTimeMillis()))

@Dao
interface CheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(checkpoint: SourceCheckpointEntity)

    @Query("SELECT * FROM source_checkpoints WHERE sourceKey = :key")
    suspend fun get(key: String): SourceCheckpointEntity?

    @Query("DELETE FROM source_checkpoints")
    suspend fun deleteAll()
}
