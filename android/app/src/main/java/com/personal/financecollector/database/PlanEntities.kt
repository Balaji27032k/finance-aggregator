package com.personal.financecollector.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** What was last sent to the server for an event, so only changes are sent again. */
@Entity(tableName = "label_sync")
data class LabelSyncEntity(
    @PrimaryKey val eventId: String,
    val signature: String,
)

/** A monthly spending limit for one category, in whole rupees. */
@Entity(tableName = "category_budgets")
data class CategoryBudgetEntity(
    @PrimaryKey val category: String,
    val limitRupees: Long,
)

/** One part of a payment that the user split between categories; the amount is stored as plain text. */
@Entity(tableName = "txn_splits")
data class TxnSplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val category: String,
    val amount: String,
)

@Dao
abstract class PlanDao {
    @Query("SELECT * FROM category_budgets")
    abstract fun observeBudgets(): Flow<List<CategoryBudgetEntity>>

    @Query("SELECT * FROM category_budgets")
    abstract suspend fun budgets(): List<CategoryBudgetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertBudget(budget: CategoryBudgetEntity)

    @Query("DELETE FROM category_budgets WHERE category = :category")
    abstract suspend fun deleteBudget(category: String)

    @Query("SELECT * FROM txn_splits")
    abstract fun observeSplits(): Flow<List<TxnSplitEntity>>

    @Query("SELECT * FROM txn_splits")
    abstract suspend fun splits(): List<TxnSplitEntity>

    @Insert
    abstract suspend fun insertSplits(parts: List<TxnSplitEntity>)

    @Query("DELETE FROM txn_splits WHERE eventId IN (:ids)")
    abstract suspend fun deleteSplits(ids: List<String>)

    @Query("SELECT * FROM label_sync")
    abstract suspend fun syncState(): List<LabelSyncEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSync(rows: List<LabelSyncEntity>)

    @Query("DELETE FROM label_sync WHERE eventId IN (:ids)")
    abstract suspend fun deleteSync(ids: List<String>)
}
