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

    @Query("SELECT * FROM raw_financial_events ORDER BY capturedTimestamp DESC LIMIT :limit")
    abstract fun observeRecent(limit: Int): Flow<List<RawFinancialEventEntity>>

    @Query("SELECT COUNT(*) FROM raw_financial_events WHERE uploadStatus IN ('PENDING','UPLOADING','RETRY')")
    abstract fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM raw_financial_events WHERE uploadStatus = 'FAILED_PERMANENTLY'")
    abstract fun observeFailedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM raw_financial_events WHERE parserStatus = 'NEEDS_REVIEW'")
    abstract fun observeNeedsReviewCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM raw_financial_events")
    abstract fun observeTotalCount(): Flow<Int>

    @Query("SELECT MAX(ackTimestamp) FROM raw_financial_events")
    abstract fun observeLastAckTimestamp(): Flow<Long?>

    @Query("DELETE FROM raw_financial_events")
    abstract suspend fun deleteAllEvents()
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
