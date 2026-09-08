package com.spamcut.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.spamcut.app.data.PhoneNumbers
import com.spamcut.app.data.db.AppDatabase
import com.spamcut.app.data.db.dao.RecentContactDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// 수신 전화의 발신번호를 로컬에 남긴다.
// 실시간 오버레이 경고는 하지 않는다 — 앱을 열면 홈에서 조회를 권유한다.
// RED 자동 거절은 CallScreeningService 가 따로 담당한다.
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        val rawNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        if (rawNumber.isNullOrBlank()) return
        val number = PhoneNumbers.toE164(rawNumber) ?: rawNumber

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppDatabase.getInstance(context).recentContactDao().record(
                    phoneNumber = number,
                    eventType = RecentContactDao.EVENT_CALL,
                )
            } catch (e: Exception) {
                Log.w("PhoneStateReceiver", "최근 수신 내역 기록 실패 (silent): ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
