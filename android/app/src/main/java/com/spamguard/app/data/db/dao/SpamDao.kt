package com.spamguard.app.data.db.dao

import androidx.room.*
import com.spamguard.app.data.db.entities.SpamNumber
import com.spamguard.app.data.db.entities.UserSubscription

@Dao
interface SpamDao {

    @Query("SELECT * FROM spam_numbers WHERE phone_number = :phoneNumber LIMIT 1")
    suspend fun findByPhoneNumber(phoneNumber: String): SpamNumber?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(spamNumber: SpamNumber)

    // Remove cache entries older than 24 hours to keep the DB lean
    @Query("DELETE FROM spam_numbers WHERE cached_at < :cutoff")
    suspend fun evictStale(cutoff: Long)

    @Query("SELECT * FROM user_subscription WHERE id = 1 LIMIT 1")
    suspend fun getSubscription(): UserSubscription?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSubscription(subscription: UserSubscription)
}
