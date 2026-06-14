package com.spamguard.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.spamguard.app.data.db.AppDatabase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

// 매일 자정 실행 — 24시간 지난 스팸 캐시 정리로 DB 크기 관리
@HiltWorker
class CacheEvictionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        AppDatabase.getInstance(applicationContext).spamDao().evictStale(cutoff)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "cache_eviction"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CacheEvictionWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
