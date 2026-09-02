package com.spamcut.app.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_subscription")
data class UserSubscription(
    @PrimaryKey
    val id: Int = 1, // single-row table

    @ColumnInfo(name = "is_alert_active")
    val isAlertActive: Boolean = false,

    @ColumnInfo(name = "expiry_timestamp")
    val expiryTimestamp: Long = 0L, // epoch millis
) {
    fun isValid(): Boolean = isAlertActive && expiryTimestamp > System.currentTimeMillis()
}
