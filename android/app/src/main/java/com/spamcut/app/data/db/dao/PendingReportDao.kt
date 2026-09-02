package com.spamcut.app.data.db.dao

import androidx.room.*
import com.spamcut.app.data.db.entities.PendingReport

@Dao
interface PendingReportDao {

    @Query("SELECT * FROM pending_reports ORDER BY created_at ASC")
    suspend fun getAll(): List<PendingReport>

    @Insert
    suspend fun insert(report: PendingReport)

    @Delete
    suspend fun delete(report: PendingReport)

    @Query("UPDATE pending_reports SET retry_count = retry_count + 1 WHERE id = :id")
    suspend fun incrementRetry(id: Int)

    // 3회 초과 실패 항목은 포기 (허위 신고 방지)
    @Query("DELETE FROM pending_reports WHERE retry_count >= 3")
    suspend fun evictFailed()
}
