package com.spamcut.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// 내가 직접 넣은 스팸 전화번호부 — 기기 안에만 있는 원본.
// 서버 등록은 별도 체크가 켜져 있을 때만 일어난다.
@Entity(tableName = "my_spam_numbers")
data class MySpamNumber(
    @PrimaryKey
    @ColumnInfo(name = "phone_number")
    val phoneNumber: String,

    @ColumnInfo(name = "tag_type")
    val tagType: String,

    @ColumnInfo(name = "note")
    val note: String? = null,

    @ColumnInfo(name = "saved_at")
    val savedAt: Long = System.currentTimeMillis(),

    // null 이면 아직 서버에 올리지 않음. 값이 있으면 올린 시각.
    @ColumnInfo(name = "shared_at")
    val sharedAt: Long? = null,
)
