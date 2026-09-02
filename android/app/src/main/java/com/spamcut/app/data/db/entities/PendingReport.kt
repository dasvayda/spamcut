package com.spamcut.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// 오프라인 상태에서 신고한 항목 — 네트워크 복귀 시 PendingReportWorker가 재시도
@Entity(tableName = "pending_reports")
data class PendingReport(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,

    @ColumnInfo(name = "phone_number")
    val phoneNumber: String,

    @ColumnInfo(name = "tag_type")
    val tagType: String,

    val description: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,
)
