package com.spamcut.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.spamcut.app.data.db.dao.PendingReportDao
import com.spamcut.app.data.db.dao.RecentContactDao
import com.spamcut.app.data.db.dao.SpamDao
import com.spamcut.app.data.db.entities.PendingReport
import com.spamcut.app.data.db.entities.RecentContact
import com.spamcut.app.data.db.entities.SpamNumber
import com.spamcut.app.data.db.entities.UserSubscription

@Database(
    entities = [
        SpamNumber::class,
        UserSubscription::class,
        PendingReport::class,
        RecentContact::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun spamDao(): SpamDao
    abstract fun pendingReportDao(): PendingReportDao
    abstract fun recentContactDao(): RecentContactDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "spamcut.db",
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
