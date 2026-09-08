package com.spamcut.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// 서버에서 받아 온 확정 목록 복사본.
// 내 목록과 섞지 않는다. 동기화를 끄면 화면에서만 숨긴다.
@Entity(tableName = "synced_spam_numbers")
data class SyncedSpamNumber(
    @PrimaryKey
    @ColumnInfo(name = "phone_number")
    val phoneNumber: String,

    @ColumnInfo(name = "tag_type")
    val tagType: String,

    @ColumnInfo(name = "server_updated_at")
    val serverUpdatedAt: Long,

    @ColumnInfo(name = "synced_at")
    val syncedAt: Long = System.currentTimeMillis(),
)
