package com.spamcut.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "spam_numbers")
data class SpamNumber(
    @PrimaryKey
    @ColumnInfo(name = "phone_number")
    val phoneNumber: String,

    @ColumnInfo(name = "tag_type")
    val tagType: String, // "RED" or "YELLOW"

    @ColumnInfo(name = "validation_score")
    val validationScore: Int = 0,

    @ColumnInfo(name = "cached_at")
    val cachedAt: Long = System.currentTimeMillis(),
)
