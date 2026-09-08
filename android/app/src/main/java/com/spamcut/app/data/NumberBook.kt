package com.spamcut.app.data

import android.content.Context
import com.spamcut.app.data.api.ReportRequest
import com.spamcut.app.data.api.RetrofitClient
import com.spamcut.app.data.db.AppDatabase
import com.spamcut.app.data.db.entities.MySpamNumber
import com.spamcut.app.data.db.entities.PendingReport
import com.spamcut.app.work.PendingReportWorker
import retrofit2.HttpException

// 내 목록 저장 + (선택) 서버 등록.
// 받아 온 동기화 번호는 이 경로를 타지 않는다.
object NumberBook {

    enum class ShareResult {
        LOCAL_ONLY,
        SHARED,
        QUEUED,
        DUPLICATE,
        LIMIT,
        FAILED,
    }

    data class SaveResult(
        val shareResult: ShareResult,
    )

    suspend fun save(
        context: Context,
        phoneNumber: String,
        tagType: String,
        note: String?,
        shareToServer: Boolean,
    ): SaveResult {
        val db = AppDatabase.getInstance(context)
        val now = System.currentTimeMillis()
        val existing = db.mySpamNumberDao().findByPhoneNumber(phoneNumber)

        db.mySpamNumberDao().upsert(
            MySpamNumber(
                phoneNumber = phoneNumber,
                tagType = tagType,
                note = note ?: existing?.note,
                savedAt = existing?.savedAt ?: now,
                sharedAt = existing?.sharedAt,
            ),
        )

        if (!shareToServer) {
            return SaveResult(ShareResult.LOCAL_ONLY)
        }

        val session = SessionManager(context)
        val token = session.getToken()
        if (token == null) {
            queue(context, phoneNumber, tagType, note)
            return SaveResult(ShareResult.QUEUED)
        }

        return try {
            RetrofitClient.service.report(
                bearer = session.bearerToken(token),
                body = ReportRequest(
                    phone_number = phoneNumber,
                    tag_type = tagType,
                    description = note,
                ),
            )
            db.mySpamNumberDao().markShared(phoneNumber, now)
            db.recentContactDao().markReported(phoneNumber, now)
            SaveResult(ShareResult.SHARED)
        } catch (e: HttpException) {
            when (e.code()) {
                409 -> {
                    db.mySpamNumberDao().markShared(phoneNumber, now)
                    db.recentContactDao().markReported(phoneNumber, now)
                    SaveResult(ShareResult.DUPLICATE)
                }
                429 -> SaveResult(ShareResult.LIMIT)
                else -> {
                    queue(context, phoneNumber, tagType, note)
                    SaveResult(ShareResult.QUEUED)
                }
            }
        } catch (_: Exception) {
            queue(context, phoneNumber, tagType, note)
            SaveResult(ShareResult.QUEUED)
        }
    }

    private suspend fun queue(
        context: Context,
        phoneNumber: String,
        tagType: String,
        note: String?,
    ) {
        AppDatabase.getInstance(context).pendingReportDao().insert(
            PendingReport(
                phoneNumber = phoneNumber,
                tagType = tagType,
                description = note,
            ),
        )
        PendingReportWorker.scheduleOnNetworkAvailable(context)
    }
}
