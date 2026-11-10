package com.personal.financecollector.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        RawFinancialEventEntity::class,
        PendingUploadEntity::class,
        SourceCheckpointEntity::class,
        ConnectedSourceEntity::class,
        CollectionStatusEntity::class,
        DeviceConfigurationEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun sourceDao(): SourceDao
    abstract fun statusDao(): StatusDao
    abstract fun configDao(): ConfigDao
    abstract fun checkpointDao(): CheckpointDao

    companion object {
        const val NAME = "finance_collector.db"
    }
}
