package com.spamcut.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.spamcut.app.data.db.AppDatabase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

// 매일 실행 — 오래된 로컬 데이터 정리
//  - 스팸 판정 캐시: 24시간
//  - 최근 수신 내역: 30일 (기기에 개인 데이터를 무한정 쌓아두지 않는다)
@HiltWorker
class CacheEvictionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val db = AppDatabase.getInstance(applicationContext)

        db.spamDao().evictStale(now - 24 * 60 * 60 * 1000L)
        db.recentContactDao().evictOlderThan(now - RECENT_RETENTION_DAYS * 24 * 60 * 60 * 1000L)

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "cache_eviction"

        private const val RECENT_RETENTION_DAYS = 30L

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
