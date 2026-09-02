package com.spamcut.app.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.spamcut.app.R
import com.spamcut.app.data.PhoneNumbers
import com.spamcut.app.data.SessionManager
import com.spamcut.app.data.api.RetrofitClient
import com.spamcut.app.data.db.AppDatabase
import com.spamcut.app.data.db.dao.RecentContactDao
import com.spamcut.app.data.db.entities.SpamNumber
import com.spamcut.app.service.OverlayService
import com.spamcut.app.ui.ReportActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "spamcut_detect"
        private var notificationId = 2000
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val rawSender = messages.first().originatingAddress ?: return
        val senderNumber = PhoneNumbers.toE164(rawSender) ?: rawSender

        // 본문은 멀티파트 SMS가 나뉘어 오므로 이어붙인다
        val body = messages.mapNotNull { it.messageBody }.joinToString(separator = "")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                recordRecent(context, senderNumber, body)
                checkAndAlert(context, senderNumber)
            } catch (e: Exception) {
                // SMS 수신 콜백에서는 어떤 예외도 앱을 크래시시키지 않는다
                Log.w("SmsReceiver", "SMS 처리 실패 (silent): ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    // 최근 수신 내역에 남긴다 — 구독 여부와 무관하게 항상 기록한다.
    // WHY: 미구독자도 나중에 목록에서 골라 신고할 수 있어야 한다.
    //      경고(오버레이·알림)만 구독자 전용이고, 로컬 기록은 모두에게 열려 있다.
    private suspend fun recordRecent(context: Context, phoneNumber: String, body: String) {
        try {
            AppDatabase.getInstance(context).recentContactDao().record(
                phoneNumber = phoneNumber,
                eventType = RecentContactDao.EVENT_SMS,
                messagePreview = body.take(RecentContactDao.PREVIEW_MAX_LENGTH).ifBlank { null },
            )
        } catch (e: Exception) {
            // SMS 수신 콜백에서는 어떤 경우에도 앱을 죽이지 않는다
            Log.w("SmsReceiver", "최근 수신 내역 기록 실패 (silent): ${e.message}")
        }
    }

    private suspend fun checkAndAlert(context: Context, phoneNumber: String) {
        val db = AppDatabase.getInstance(context)
        val dao = db.spamDao()

        // Step A: 구독 확인
        val sub = dao.getSubscription()
        if (sub == null || !sub.isValid()) return

        // Step B: 로컬 캐시 조회
        val cached = dao.findByPhoneNumber(phoneNumber)
        if (cached != null) {
            db.recentContactDao()
                .markChecked(phoneNumber, cached.tagType, System.currentTimeMillis())
            launchOverlay(context, cached.tagType, phoneNumber)
            showDetectionNotification(context, phoneNumber, cached.tagType)
            return
        }

        // Step C: API 조회 (타임아웃은 OkHttp에서 5초 설정)
        try {
            val response = RetrofitClient.service.checkSpam(phoneNumber)

            // 스팸이 아니어도 확인 사실은 최근 수신 내역에 남긴다 (목록에서 "안전"으로 표시)
            db.recentContactDao()
                .markChecked(phoneNumber, response.tagType, System.currentTimeMillis())

            if (response.isSpam && response.tagType != null) {
                dao.insertOrReplace(
                    SpamNumber(
                        phoneNumber = phoneNumber,
                        tagType = response.tagType,
                        validationScore = response.score ?: 0,
                    )
                )
                launchOverlay(context, response.tagType, phoneNumber)
                showDetectionNotification(context, phoneNumber, response.tagType)
            }
        } catch (e: Exception) {
            Log.w("SmsReceiver", "API check failed (silent): ${e.message}")
        }
    }

    private fun launchOverlay(context: Context, tagType: String, phoneNumber: String) {
        val intent = Intent(context, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_TAG_TYPE, tagType)
            putExtra(OverlayService.EXTRA_PHONE_NUMBER, phoneNumber)
        }
        context.startForegroundService(intent)
    }

    private fun showDetectionNotification(context: Context, phoneNumber: String, tagType: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm, context)

        val label = if (tagType == "RED") "위험 스팸" else "마케팅 문자"
        val color = if (tagType == "RED") 0xFFD32F2F.toInt() else 0xFFF9A825.toInt()

        // "신고하기" 액션 — ReportActivity로 전화번호 전달
        val reportIntent = Intent(context, ReportActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ReportActivity.EXTRA_PREFILL_NUMBER, phoneNumber)
        }
        val reportPending = PendingIntent.getActivity(
            context,
            phoneNumber.hashCode(),
            reportIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("$label 감지: $phoneNumber")
            .setContentText("탭하여 자세히 보거나 신고하세요")
            .setColor(color)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_shield, "신고하기", reportPending)
            .build()

        nm.notify(notificationId++, notification)
    }

    private fun ensureChannel(nm: NotificationManager, context: Context) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "스팸 감지 알림",
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.description = "스팸 번호 수신 시 표시됩니다"
        nm.createNotificationChannel(channel)
    }
}
