package com.personal.financecollector.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "raw_financial_events",
    indices = [
        Index(value = ["sourceType", "sourceApplication", "sourceEventId"], unique = true),
        Index("uploadStatus"),
        Index("capturedTimestamp"),
    ],
)
data class RawFinancialEventEntity(
    @PrimaryKey val localEventId: String,
    val sourceType: SourceType,
    /** Package name (notifications) or sender id (SMS/Gmail). */
    val sourceApplication: String,
    val sourceSender: String?,
    val sourceEventId: String,
    val eventTimestamp: Long,
    val capturedTimestamp: Long,
    val rawPayload: String,
    val payloadHash: String,
    val parserStatus: ParserStatus,
    val parserVersion: String,
    val uploadStatus: UploadStatus,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val ackTimestamp: Long? = null,
)

@Entity(
    tableName = "pending_uploads",
    foreignKeys = [
        ForeignKey(
            entity = RawFinancialEventEntity::class,
            parentColumns = ["localEventId"],
            childColumns = ["localEventId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("localEventId", unique = true), Index("nextAttemptAt")],
)
data class PendingUploadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val localEventId: String,
    val enqueuedAt: Long,
    val nextAttemptAt: Long,
    val attempts: Int = 0,
)

@Entity(tableName = "source_checkpoints")
data class SourceCheckpointEntity(
    @PrimaryKey val sourceKey: String,
    val checkpointValue: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "connected_sources",
    indices = [Index(value = ["sourceType", "identifier"], unique = true)],
)
data class ConnectedSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceType: SourceType,
    val identifier: String,
    val displayName: String,
    val enabled: Boolean,
    val addedAt: Long,
)

@Entity(tableName = "collection_status")
data class CollectionStatusEntity(
    @PrimaryKey val key: String,
    val lastEventAt: Long? = null,
    val lastConnectedAt: Long? = null,
    val lastDisconnectedAt: Long? = null,
    val droppedHiddenContent: Int = 0,
    val droppedFiltered: Int = 0,
    val lastError: String? = null,
)

@Entity(tableName = "device_configuration")
data class DeviceConfigurationEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)
