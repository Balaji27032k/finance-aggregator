package com.personal.financecollector.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        RawFinancialEventEntity::class,
        PendingUploadEntity::class,
        SourceCheckpointEntity::class,
        ConnectedSourceEntity::class,
        CollectionStatusEntity::class,
        DeviceConfigurationEntity::class,
        TransactionLabelEntity::class,
        PayeeRuleEntity::class,
        OwnAccountEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun sourceDao(): SourceDao
    abstract fun statusDao(): StatusDao
    abstract fun configDao(): ConfigDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun labelDao(): LabelDao

    companion object {
        const val NAME = "finance_collector.db"

        /** Only adds tables; the captured events are untouched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `transaction_labels` (`eventId` TEXT NOT NULL, `category` TEXT, `label` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`eventId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `payee_rules` (`payeeKey` TEXT NOT NULL, `category` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`payeeKey`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `own_accounts` (`accountKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `value` TEXT NOT NULL, `display` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`accountKey`))")
            }
        }
    }
}
