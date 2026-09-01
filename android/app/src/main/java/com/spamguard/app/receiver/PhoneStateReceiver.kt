package com.spamguard.app.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.spamguard.app.R
import com.spamguard.app.data.PhoneNumbers
import com.spamguard.app.data.api.RetrofitClient
import com.spamguard.app.data.db.AppDatabase
import com.spamguard.app.data.db.dao.RecentContactDao
import com.spamguard.app.data.db.entities.SpamNumber
import com.spamguard.app.service.OverlayService
import com.spamguard.app.ui.ReportActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

// 수신 전화 스팸 경고 — 사전(proactive) 알림
//
// 동작 원리:
//   RINGING → 발신자 번호 스팸 확인 → 스팸이면 오버레이 + 알림
//   IDLE    → 통화 종료(또는 거절) 시 오버레이 자동 닫기
//
// CallScreeningService 와의 역할 분리:
//   - PhoneStateReceiver : 시각적 경고 (RED/YELLOW 모두)
//   - CallScreeningService: RED 전화 자동 거절 (사용자가 기본 스팸 차단 앱으로 설정 시)
class PhoneStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PhoneStateReceiver"
        private const val CHANNEL_ID = "spamguard_call"
        private var notificationId = 3000

        // 마지막으로 경고한 번호 — IDLE 시 오버레이 닫기에 사용
        @Volatile private var lastAlertedNumber: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val rawNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (rawNumber.isNullOrBlank()) return
                val number = PhoneNumbers.toE164(rawNumber) ?: rawNumber
                lastAlertedNumber = number

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        recordRecent(context, number)
                        checkAndAlert(context, number)
                    } catch (e: Exception) {
                        Log.w(TAG, "전화 스팸 확인 실패 (silent): ${e.message}")
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                // 전화 종료 또는 거절 — 오버레이 닫기
                context.stopService(Intent(context, OverlayService::class.java))
                lastAlertedNumber = null
            }

            // OFFHOOK(통화 연결)은 별도 처리 불필요 — OverlayService가 자동 닫기 타이머로 처리
        }
    }

    // 최근 수신 내역에 남긴다 — 구독 여부와 무관하게 항상 기록한다.
    // WHY: 미구독자도 방금 걸려온 번호를 목록에서 골라 신고할 수 있어야 한다.
    private suspend fun recordRecent(context: Context, phoneNumber: String) {
        try {
            AppDatabase.getInstance(context).recentContactDao().record(
                phoneNumber = phoneNumber,
                eventType = RecentContactDao.EVENT_CALL,
            )
        } catch (e: Exception) {
            Log.w(TAG, "최근 수신 내역 기록 실패 (silent): ${e.message}")
        }
    }

    private suspend fun checkAndAlert(context: Context, phoneNumber: String) {
        val db = AppDatabase.getInstance(context)
        val dao = db.spamDao()

        // 구독 확인 — 미구독자에게는 경고 없음
        val sub = dao.getSubscription()
        if (sub == null || !sub.isValid()) return

        // 로컬 캐시 먼저 (즉시 응답)
        val cached = dao.findByPhoneNumber(phoneNumber)
        if (cached != null) {
            db.recentContactDao()
                .markChecked(phoneNumber, cached.tagType, System.currentTimeMillis())
            triggerAlert(context, phoneNumber, cached.tagType)
            return
        }

        // API 조회 — 전화 수신 중이므로 타임아웃 엄격하게 (3초)
        try {
            withTimeout(3000) {
                val result = RetrofitClient.service.checkSpam(phoneNumber)

                // 스팸이 아니어도 확인 사실은 최근 수신 내역에 남긴다
                db.recentContactDao()
                    .markChecked(phoneNumber, result.tagType, System.currentTimeMillis())

                if (result.isSpam && result.tagType != null) {
                    dao.insertOrReplace(
                        SpamNumber(
                            phoneNumber = phoneNumber,
                            tagType = result.tagType,
                            validationScore = result.score ?: 0,
                        ),
                    )
                    triggerAlert(context, phoneNumber, result.tagType)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "API 조회 실패 또는 타임아웃: ${e.message}")
        }
    }

    private fun triggerAlert(context: Context, phoneNumber: String, tagType: String) {
        // 오버레이 표시 — 전화 수신 화면 위 상단 배너
        val overlayIntent = Intent(context, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_TAG_TYPE, tagType)
            putExtra(OverlayService.EXTRA_PHONE_NUMBER, phoneNumber)
            putExtra(OverlayService.EXTRA_AUTO_DISMISS_MS, 30_000L) // 30초 자동 닫기
        }
        context.startForegroundService(overlayIntent)

        // 알림도 함께 표시 (화면이 꺼져 있거나 오버레이 권한 없는 경우 대비)
        showCallNotification(context, phoneNumber, tagType)
    }

    private fun showCallNotification(context: Context, phoneNumber: String, tagType: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        val label = if (tagType == "RED") "위험 스팸 전화" else "마케팅 전화"
        val color = if (tagType == "RED") 0xFFD32F2F.toInt() else 0xFFF9A825.toInt()

        val reportIntent = Intent(context, ReportActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ReportActivity.EXTRA_PREFILL_NUMBER, phoneNumber)
        }
        val reportPending = PendingIntent.getActivity(
            context,
            phoneNumber.hashCode() + 10000,
            reportIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("$label: $phoneNumber")
            .setContentText("지금 수신 중인 전화입니다")
            .setColor(color)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_shield, "신고하기", reportPending)
            .build()

        nm.notify(notificationId++, notification)
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "수신 전화 스팸 경고", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "수신 전화 스팸 여부를 실시간으로 알려줍니다"
            },
        )
    }
}
