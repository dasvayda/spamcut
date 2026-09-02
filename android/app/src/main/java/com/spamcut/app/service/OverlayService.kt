package com.spamcut.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.spamcut.app.R

class OverlayService : Service() {

    companion object {
        const val EXTRA_TAG_TYPE        = "tag_type"
        const val EXTRA_PHONE_NUMBER    = "phone_number"
        // 자동 닫기 딜레이(ms) — 전화 수신 중에는 30초, SMS는 0(수동 닫기)
        const val EXTRA_AUTO_DISMISS_MS = "auto_dismiss_ms"

        private const val CHANNEL_ID      = "spamcut_overlay"
        private const val NOTIFICATION_ID = 1001
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val tagType      = intent?.getStringExtra(EXTRA_TAG_TYPE)        ?: return START_NOT_STICKY
        val phoneNumber  = intent.getStringExtra(EXTRA_PHONE_NUMBER)      ?: return START_NOT_STICKY
        val autoDismissMs = intent.getLongExtra(EXTRA_AUTO_DISMISS_MS, 0L)

        if (!Settings.canDrawOverlays(this)) {
            // 오버레이 권한 없으면 알림만 표시하고 종료 (알림은 호출자가 이미 표시)
            stopSelf()
            return START_NOT_STICKY
        }

        showOverlay(tagType, phoneNumber)

        // 자동 닫기 — 전화 수신 중 일정 시간 후 사라짐 (전화 받기 버튼 가리지 않도록)
        if (autoDismissMs > 0) {
            dismissRunnable?.let { handler.removeCallbacks(it) }
            dismissRunnable = Runnable {
                removeOverlay()
                stopSelf()
            }.also { handler.postDelayed(it, autoDismissMs) }
        }

        return START_NOT_STICKY
    }

    private fun showOverlay(tagType: String, phoneNumber: String) {
        removeOverlay()

        val layoutId = when (tagType) {
            "RED"    -> R.layout.overlay_red
            else     -> R.layout.overlay_yellow
        }

        overlayView = LayoutInflater.from(this).inflate(layoutId, null)
        overlayView?.findViewById<TextView>(R.id.tvPhoneNumber)?.text = phoneNumber
        overlayView?.findViewById<Button>(R.id.btnDismiss)?.setOnClickListener {
            dismissRunnable?.let { handler.removeCallbacks(it) }
            removeOverlay()
            stopSelf()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_NOT_FOCUSABLE: 전화 받기/거절 버튼이 포커스를 받을 수 있도록
            // FLAG_NOT_TOUCH_MODAL: 오버레이 외부 터치도 전달 (전화 수신 UI 조작 가능)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
        }

        windowManager?.addView(overlayView, params)
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
            overlayView = null
        }
    }

    override fun onDestroy() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        removeOverlay()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SpamCut Alerts",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SpamCut")
            .setContentText("스팸 번호 감시 중")
            .setSmallIcon(R.drawable.ic_shield)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
