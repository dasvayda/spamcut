package com.spamcut.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.core.app.NotificationCompat
import com.spamcut.app.R
import com.spamcut.app.data.AppSettings
import com.spamcut.app.data.PhoneNumbers
import com.spamcut.app.data.api.RetrofitClient
import com.spamcut.app.data.db.AppDatabase
import com.spamcut.app.data.db.entities.SpamNumber
import com.spamcut.app.ui.ReportActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

// 수신 전화 자동 차단 서비스 — RED 스팸만 거절
//
// 활성화 방법: 설정 > 전화 > 발신자 ID 및 스팸 차단 > SpamCut 선택
// (ROLE_CALL_SCREENING — 사용자가 직접 선택해야 하는 특수 권한)
//
// 역할 분리:
//   - 이 서비스:    RED 전화 자동 거절 (차단 결정)
//   - 홈 화면:      앱을 열었을 때 방금 번호 조회 권유
//
// YELLOW 전화는 차단하지 않음 — 광고/마케팅은 사용자가 직접 판단
class CallScreeningService : CallScreeningService() {

    companion object {
        private const val CHANNEL_ID = "spamcut_call_block"
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart

        if (number == null) {
            // 번호 없음(비공개) — 허용
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val response = screenNumber(number)
            respondToCall(callDetails, response)

            // RED 거절은 수신음이 짧게 끊길 수 있으므로, 차단된 번호를 알림으로 남긴다.
            if (response.rejectCall) {
                showBlockedNotification(number)
            }
        }
    }

    private suspend fun screenNumber(phoneNumber: String): CallResponse {
        val db = AppDatabase.getInstance(applicationContext)
        val e164 = PhoneNumbers.toE164(phoneNumber) ?: phoneNumber

        val mine = db.mySpamNumberDao().findByPhoneNumber(e164)
            ?: db.mySpamNumberDao().findByPhoneNumber(phoneNumber)
        if (mine?.tagType == "RED") return buildRejectResponse()

        if (AppSettings(applicationContext).isCrowdSyncEnabled()) {
            val synced = db.syncedSpamNumberDao().findByPhoneNumber(e164)
                ?: db.syncedSpamNumberDao().findByPhoneNumber(phoneNumber)
            if (synced?.tagType == "RED") return buildRejectResponse()
        }

        val dao = db.spamDao()

        // 로컬 캐시 우선 — 응답 시간이 중요
        val cached = dao.findByPhoneNumber(e164) ?: dao.findByPhoneNumber(phoneNumber)
        if (cached?.tagType == "RED") return buildRejectResponse()

        // API 조회 — 타임아웃 2초 (초과 시 허용: false negative 선택)
        return try {
            withTimeout(2000) {
                val result = RetrofitClient.service.checkSpam(e164)
                if (result.isSpam && result.tagType == "RED") {
                    dao.insertOrReplace(
                        SpamNumber(
                            phoneNumber = e164,
                            tagType = result.tagType,
                            validationScore = result.score ?: 0,
                        ),
                    )
                    buildRejectResponse()
                } else {
                    CallResponse.Builder().build()
                }
            }
        } catch (e: Exception) {
            // 타임아웃 또는 오류 → 허용 (차단 오류보다 미차단 오류가 낫다)
            CallResponse.Builder().build()
        }
    }

    private fun buildRejectResponse(): CallResponse =
        CallResponse.Builder()
            .setRejectCall(true)
            .setDisallowCall(false)
            .setSilenceCall(false)
            .setSkipNotification(false) // 거절 알림은 시스템이 표시
            .build()

    private fun showBlockedNotification(phoneNumber: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        val reportIntent = Intent(applicationContext, ReportActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("prefill_number", phoneNumber)
        }
        val reportPending = PendingIntent.getActivity(
            applicationContext,
            phoneNumber.hashCode() + 20000,
            reportIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("스팸 전화 차단됨: $phoneNumber")
            .setContentText("위험 스팸으로 등록된 번호입니다")
            .setColor(0xFFD32F2F.toInt())
            .setAutoCancel(true)
            .addAction(R.drawable.ic_shield, "내 목록에 넣기", reportPending)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "전화 차단 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "자동 차단된 스팸 전화 내역"
            },
        )
    }
}
