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
        LabelSyncEntity::class,
        CategoryBudgetEntity::class,
        TxnSplitEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun sourceDao(): SourceDao
    abstract fun statusDao(): StatusDao
    abstract fun configDao(): ConfigDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun labelDao(): LabelDao
    abstract fun planDao(): PlanDao

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

        /** Adds budgets, split payments and the record of what was sent to the server. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `label_sync` (`eventId` TEXT NOT NULL, `signature` TEXT NOT NULL, PRIMARY KEY(`eventId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `category_budgets` (`category` TEXT NOT NULL, `limitRupees` INTEGER NOT NULL, PRIMARY KEY(`category`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `txn_splits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `eventId` TEXT NOT NULL, `category` TEXT NOT NULL, `amount` TEXT NOT NULL)")
            }
        }
    }
}
