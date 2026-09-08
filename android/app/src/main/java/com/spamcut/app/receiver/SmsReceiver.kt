package com.spamcut.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.spamcut.app.data.PhoneNumbers
import com.spamcut.app.data.db.AppDatabase
import com.spamcut.app.data.db.dao.RecentContactDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// 문자가 오면 발신번호만 로컬에 남긴다.
// 실시간 오버레이 경고는 하지 않는다 — 앱을 열면 홈에서 조회를 권유한다.
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val rawSender = messages.first().originatingAddress ?: return
        val senderNumber = PhoneNumbers.toE164(rawSender) ?: rawSender
        val body = messages.mapNotNull { it.messageBody }.joinToString(separator = "")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppDatabase.getInstance(context).recentContactDao().record(
                    phoneNumber = senderNumber,
                    eventType = RecentContactDao.EVENT_SMS,
                    messagePreview = body.take(RecentContactDao.PREVIEW_MAX_LENGTH).ifBlank { null },
                )
            } catch (e: Exception) {
                Log.w("SmsReceiver", "최근 수신 내역 기록 실패 (silent): ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
