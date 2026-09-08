package com.spamcut.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.spamcut.app.data.db.entities.MySpamNumber

@Dao
interface MySpamNumberDao {

    @Query("SELECT * FROM my_spam_numbers ORDER BY saved_at DESC")
    suspend fun getAll(): List<MySpamNumber>

    @Query("SELECT * FROM my_spam_numbers WHERE phone_number = :phoneNumber LIMIT 1")
    suspend fun findByPhoneNumber(phoneNumber: String): MySpamNumber?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MySpamNumber)

    @Query("DELETE FROM my_spam_numbers WHERE phone_number = :phoneNumber")
    suspend fun deleteByPhoneNumber(phoneNumber: String)

    @Query(
        """
        UPDATE my_spam_numbers
        SET shared_at = :sharedAt
        WHERE phone_number = :phoneNumber
        """,
    )
    suspend fun markShared(phoneNumber: String, sharedAt: Long)
}
