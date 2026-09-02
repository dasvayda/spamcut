package com.spamcut.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.spamcut.app.R
import com.spamcut.app.data.SessionManager
import com.spamcut.app.data.api.FcmTokenRequest
import com.spamcut.app.data.api.RetrofitClient
import com.spamcut.app.ui.WalletActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SpamCutFirebaseService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "spamcut_push"
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: return
        val body  = message.notification?.body  ?: return
        val type  = message.data["type"] ?: ""

        // 알림 유형에 따라 탭 시 이동할 화면 결정
        val intent = when (type) {
            "report_validated", "subscription_expiring" ->
                Intent(this, WalletActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            else -> packageManager.getLaunchIntentForPackage(packageName) ?: return
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    // FCM 토큰 갱신 시 서버에 업로드
    override fun onNewToken(token: String) {
        val sessionToken = SessionManager(applicationContext).getToken() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                RetrofitClient.service.registerFcmToken(
                    bearer = "Bearer $sessionToken",
                    body = FcmTokenRequest(token),
                )
            } catch (e: Exception) {
                // 다음 앱 시작 시 재시도
            }
        }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SpamCut 알림", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "신고 검증 완료·구독 만료 알림"
            },
        )
    }
}
