package com.spamguard.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.spamguard.app.R
import com.spamguard.app.data.SessionManager
import com.spamguard.app.data.api.RetrofitClient
import com.spamguard.app.data.db.AppDatabase
import com.spamguard.app.data.db.entities.UserSubscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        session = SessionManager(this)

        if (!session.isLoggedIn()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        findViewById<Button>(R.id.btnReport).setOnClickListener {
            startActivity(Intent(this, ReportActivity::class.java))
        }
        findViewById<Button>(R.id.btnWallet).setOnClickListener {
            startActivity(Intent(this, WalletActivity::class.java))
        }
        findViewById<Button>(R.id.btnGrantOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            } else {
                Toast.makeText(this, "이미 오버레이 권한이 허용되어 있습니다", Toast.LENGTH_SHORT).show()
            }
        }

        lifecycleScope.launch { syncWallet() }
    }

    override fun onResume() {
        super.onResume()
        val overlayGranted = Settings.canDrawOverlays(this)
        findViewById<TextView>(R.id.tvOverlayStatus).text =
            if (overlayGranted) "오버레이 알림: 활성화 ✓" else "오버레이 알림: 비활성화 — 탭하여 허용"
    }

    private suspend fun syncWallet() {
        val token = session.getToken() ?: return
        try {
            val wallet = withContext(Dispatchers.IO) {
                RetrofitClient.service.getWallet(session.bearerToken(token))
            }
            val sub = wallet.subscription

            // Room DB 구독 상태 갱신
            val expiryMs = sub.expires_at?.let {
                runCatching {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).parse(it)?.time
                }.getOrNull()
            } ?: 0L

            withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@MainActivity).spamDao().saveSubscription(
                    UserSubscription(isAlertActive = sub.is_active, expiryTimestamp = expiryMs)
                )
            }

            val subText = if (sub.is_active)
                "실시간 알림: 활성 (${sub.days_remaining}일 남음)"
            else
                "실시간 알림: 비활성 — 지갑에서 활성화"

            findViewById<TextView>(R.id.tvSubscriptionStatus).text = subText
            findViewById<TextView>(R.id.tvBalance).text = "잔액: ${wallet.balance} token"
        } catch (_: Exception) {
            // 오프라인 상태 — 캐시된 Room DB 값 유지
        }
    }
}
