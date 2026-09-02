package com.spamcut.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.spamcut.app.R
import com.spamcut.app.data.SessionManager
import com.spamcut.app.data.api.RetrofitClient
import com.spamcut.app.data.db.AppDatabase
import com.spamcut.app.data.db.entities.UserSubscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var session: SessionManager

    // 결과는 별도로 처리하지 않는다 — 거부해도 앱은 동작하고, 수신 감지만 비활성 상태가 된다
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        session = SessionManager(this)

        if (!session.isLoggedIn()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        findViewById<Button>(R.id.btnRecent).setOnClickListener {
            startActivity(Intent(this, RecentActivity::class.java))
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

        requestReceiverPermissions()

        lifecycleScope.launch { syncWallet() }
    }

    // SMS·전화 수신 감지에 필요한 런타임 권한 요청.
    // WHY: 이 권한이 없으면 BroadcastReceiver가 호출되지 않아 최근 수신 내역이 비어 있게 된다.
    private fun requestReceiverPermissions() {
        val needed = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
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
