package com.personal.financecollector.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** What the user says a transaction was (for example "milk"), and optionally a corrected category. One row per captured event. */
@Entity(tableName = "transaction_labels")
data class TransactionLabelEntity(
    @PrimaryKey val eventId: String,
    val category: String?,
    val label: String?,
    val updatedAt: Long,
)

/** "Always treat payments to this payee as this category." */
@Entity(tableName = "payee_rules")
data class PayeeRuleEntity(
    @PrimaryKey val payeeKey: String,
    val category: String,
    val updatedAt: Long,
)

/** Accounts typed in by hand (kind ACCOUNT, value = last digits) and names the user's own accounts appear under (kind NAME). */
@Entity(tableName = "own_accounts")
data class OwnAccountEntity(
    @PrimaryKey val accountKey: String,
    val kind: String,
    val value: String,
    val display: String,
    val addedAt: Long,
)

@Dao
abstract class LabelDao {
    @Query("SELECT * FROM transaction_labels")
    abstract fun observeLabels(): Flow<List<TransactionLabelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertLabel(label: TransactionLabelEntity)

    @Query("DELETE FROM transaction_labels WHERE eventId IN (:ids)")
    abstract suspend fun deleteLabels(ids: List<String>)

    @Query("SELECT * FROM payee_rules")
    abstract fun observeRules(): Flow<List<PayeeRuleEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertRule(rule: PayeeRuleEntity)

    @Query("DELETE FROM payee_rules WHERE payeeKey = :key")
    abstract suspend fun deleteRule(key: String)

    @Query("SELECT * FROM own_accounts ORDER BY addedAt")
    abstract fun observeOwn(): Flow<List<OwnAccountEntity>>

    @Query("SELECT * FROM own_accounts")
    abstract suspend fun ownAccounts(): List<OwnAccountEntity>

    @Query("SELECT * FROM transaction_labels")
    abstract suspend fun labels(): List<TransactionLabelEntity>

    @Query("SELECT * FROM payee_rules")
    abstract suspend fun rules(): List<PayeeRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertOwn(entry: OwnAccountEntity)

    @Query("DELETE FROM own_accounts WHERE accountKey = :key")
    abstract suspend fun deleteOwn(key: String)
}
