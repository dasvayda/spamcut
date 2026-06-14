package com.spamguard.app.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

// 오프라인 상태에서 신고한 항목 — 네트워크 복귀 시 PendingReportWorker가 재시도
@Entity(tableName = "pending_reports")
data class PendingReport(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val phoneNumber: String,
    val tagType: String,
    val description: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
)
