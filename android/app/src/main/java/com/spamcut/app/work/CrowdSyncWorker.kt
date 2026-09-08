package com.spamcut.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.spamcut.app.data.AppSettings
import com.spamcut.app.data.SessionManager
import com.spamcut.app.data.api.RetrofitClient
import com.spamcut.app.data.db.AppDatabase
import com.spamcut.app.data.db.entities.SyncedSpamNumber
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import java.util.concurrent.TimeUnit

// 확정된 공용 목록을 폰으로 받는다. 신고 점수는 건드리지 않는다.
@HiltWorker
class CrowdSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val settings = AppSettings(applicationContext)
        if (!settings.isCrowdSyncEnabled()) return Result.success()

        val token = SessionManager(applicationContext).getToken() ?: return Result.retry()
        val dao = AppDatabase.getInstance(applicationContext).syncedSpamNumberDao()
        val bearer = "Bearer $token"

        var since = settings.getSyncCursor()
        var pages = 0

        try {
            while (pages < MAX_PAGES) {
                val response = RetrofitClient.service.getConfirmedSpam(
                    bearer = bearer,
                    since = since,
                    limit = PAGE_SIZE,
                )
                val now = System.currentTimeMillis()
                val rows = response.items.map { item ->
                    SyncedSpamNumber(
                        phoneNumber = item.phone_number,
                        tagType = item.tag_type,
                        serverUpdatedAt = parseIso(item.updated_at),
                        syncedAt = now,
                    )
                }
                if (rows.isNotEmpty()) {
                    dao.upsertAll(rows)
                }
                if (response.next_since == null) {
                    if (response.items.isNotEmpty()) {
                        settings.setSyncCursor(response.items.last().updated_at)
                    }
                    break
                }
                since = response.next_since
                settings.setSyncCursor(since)
                pages += 1
            }
            return Result.success()
        } catch (_: Exception) {
            return Result.retry()
        }
    }

    private fun parseIso(value: String): Long =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

    companion object {
        const val WORK_NAME = "crowd_sync"
        private const val PAGE_SIZE = 500
        private const val MAX_PAGES = 20

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<CrowdSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME + "_once",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<CrowdSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(WORK_NAME)
            wm.cancelUniqueWork(WORK_NAME + "_once")
        }
    }
}
