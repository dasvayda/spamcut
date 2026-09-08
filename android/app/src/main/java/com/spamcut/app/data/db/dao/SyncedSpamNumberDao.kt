package com.spamcut.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spamcut.app.data.db.entities.SyncedSpamNumber

@Dao
interface SyncedSpamNumberDao {

    @Query("SELECT * FROM synced_spam_numbers ORDER BY server_updated_at DESC")
    suspend fun getAll(): List<SyncedSpamNumber>

    @Query("SELECT * FROM synced_spam_numbers WHERE phone_number = :phoneNumber LIMIT 1")
    suspend fun findByPhoneNumber(phoneNumber: String): SyncedSpamNumber?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SyncedSpamNumber>)

    @Query("DELETE FROM synced_spam_numbers")
    suspend fun deleteAll()
}
