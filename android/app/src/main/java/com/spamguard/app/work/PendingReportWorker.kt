package com.spamguard.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.spamguard.app.data.api.ReportRequest
import com.spamguard.app.data.api.RetrofitClient
import com.spamguard.app.data.db.AppDatabase
import com.spamguard.app.data.SessionManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

// 오프라인 중 쌓인 신고를 네트워크 복귀 후 재전송
@HiltWorker
class PendingReportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        val dao = db.pendingReportDao()
        val token = SessionManager(applicationContext).getToken() ?: return Result.success()

        dao.evictFailed()

        val pending = dao.getAll()
        var allSuccess = true

        for (report in pending) {
            try {
                RetrofitClient.service.report(
                    bearer = "Bearer $token",
                    body = ReportRequest(
                        phone_number = report.phoneNumber,
                        tag_type = report.tagType,
                        description = report.description,
                    ),
                )
                dao.delete(report)
            } catch (e: Exception) {
                dao.incrementRetry(report.id)
                allSuccess = false
            }
        }

        return if (allSuccess) Result.success() else Result.retry()
    }

    companion object {
        const val WORK_NAME = "pending_report_sync"

        fun scheduleOnNetworkAvailable(context: Context) {
            val request = OneTimeWorkRequestBuilder<PendingReportWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
