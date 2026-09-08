package com.spamcut.app.ui

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.spamcut.app.R
import com.spamcut.app.data.AppSettings
import com.spamcut.app.data.NumberBook
import com.spamcut.app.data.PhoneNumbers
import com.spamcut.app.data.SessionManager
import com.spamcut.app.data.api.RetrofitClient
import com.spamcut.app.data.db.AppDatabase
import com.spamcut.app.data.db.dao.RecentContactDao
import com.spamcut.app.data.db.entities.RecentContact
import com.spamcut.app.data.db.entities.UserSubscription
import com.spamcut.app.work.CrowdSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var session: SessionManager
    private lateinit var settings: AppSettings
    private var promptContact: RecentContact? = null

    private lateinit var cardPrompt: LinearLayout
    private lateinit var tvPromptEmpty: TextView
    private lateinit var tvPromptKind: TextView
    private lateinit var tvPromptNumber: TextView
    private lateinit var tvPromptPreview: TextView
    private lateinit var tvPromptQuestion: TextView
    private lateinit var tvLookupResult: TextView
    private lateinit var btnLookupNow: Button
    private lateinit var btnSaveToList: Button
    private lateinit var btnLookupLater: Button
    private lateinit var rgSaveTag: RadioGroup
    private lateinit var rbSaveRed: RadioButton
    private lateinit var rbSaveYellow: RadioButton
    private lateinit var cbShareServer: CheckBox
    private lateinit var cbCrowdSync: CheckBox

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        session = SessionManager(this)
        settings = AppSettings(this)

        if (!session.isLoggedIn()) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        cardPrompt = findViewById(R.id.cardLookupPrompt)
        tvPromptEmpty = findViewById(R.id.tvPromptEmpty)
        tvPromptKind = findViewById(R.id.tvPromptKind)
        tvPromptNumber = findViewById(R.id.tvPromptNumber)
        tvPromptPreview = findViewById(R.id.tvPromptPreview)
        tvPromptQuestion = findViewById(R.id.tvPromptQuestion)
        tvLookupResult = findViewById(R.id.tvLookupResult)
        btnLookupNow = findViewById(R.id.btnLookupNow)
        btnSaveToList = findViewById(R.id.btnSaveToList)
        btnLookupLater = findViewById(R.id.btnLookupLater)
        rgSaveTag = findViewById(R.id.rgSaveTag)
        rbSaveRed = findViewById(R.id.rbSaveRed)
        rbSaveYellow = findViewById(R.id.rbSaveYellow)
        cbShareServer = findViewById(R.id.cbShareServer)
        cbCrowdSync = findViewById(R.id.cbCrowdSync)

        findViewById<Button>(R.id.btnMyList).setOnClickListener {
            startActivity(Intent(this, MyListActivity::class.java))
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

        btnLookupNow.setOnClickListener { lookupPromptNumber() }
        btnLookupLater.setOnClickListener { dismissPrompt() }
        btnSaveToList.setOnClickListener { savePromptNumber() }

        cbCrowdSync.setOnCheckedChangeListener(null)
        cbCrowdSync.isChecked = settings.isCrowdSyncEnabled()
        cbCrowdSync.setOnCheckedChangeListener { _, checked ->
            settings.setCrowdSyncEnabled(checked)
            if (checked) {
                CrowdSyncWorker.schedulePeriodic(this)
                CrowdSyncWorker.runNow(this)
                Toast.makeText(this, R.string.sync_on_toast, Toast.LENGTH_SHORT).show()
            } else {
                CrowdSyncWorker.cancel(this)
                Toast.makeText(this, R.string.sync_off_toast, Toast.LENGTH_SHORT).show()
            }
        }

        if (settings.isCrowdSyncEnabled()) {
            CrowdSyncWorker.schedulePeriodic(this)
            CrowdSyncWorker.runNow(this)
        }

        requestReceiverPermissions()
        lifecycleScope.launch { syncWallet() }
    }

    override fun onResume() {
        super.onResume()
        bindLookupPrompt()
    }

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

    private fun bindLookupPrompt() {
        lifecycleScope.launch {
            val latest = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@MainActivity).recentContactDao().getLatest()
            }
            if (latest == null || !latest.shouldOfferLookup()) {
                promptContact = null
                cardPrompt.visibility = View.GONE
                tvPromptEmpty.visibility = View.VISIBLE
                return@launch
            }
            showPromptCard(latest)
        }
    }

    private fun showPromptCard(contact: RecentContact) {
        promptContact = contact
        tvPromptEmpty.visibility = View.GONE
        cardPrompt.visibility = View.VISIBLE
        tvLookupResult.visibility = View.GONE
        tvPromptQuestion.visibility = View.VISIBLE
        btnLookupNow.visibility = View.VISIBLE
        btnLookupNow.isEnabled = true
        btnLookupNow.text = getString(R.string.lookup_prompt_yes)
        cbShareServer.isChecked = true
        rgSaveTag.clearCheck()

        tvPromptKind.text = if (contact.lastEventType == RecentContactDao.EVENT_CALL) {
            getString(R.string.lookup_prompt_kind_call)
        } else {
            getString(R.string.lookup_prompt_kind_sms)
        }
        tvPromptNumber.text = PhoneNumbers.toDisplay(contact.phoneNumber)

        val preview = contact.lastMessagePreview
        if (preview.isNullOrBlank()) {
            tvPromptPreview.visibility = View.GONE
        } else {
            tvPromptPreview.visibility = View.VISIBLE
            tvPromptPreview.text = preview
        }
    }

    private fun lookupPromptNumber() {
        val contact = promptContact ?: return
        btnLookupNow.isEnabled = false
        btnLookupNow.text = getString(R.string.lookup_prompt_looking)

        lifecycleScope.launch {
            try {
                val token = session.getToken()
                val result = withContext(Dispatchers.IO) {
                    RetrofitClient.service.checkSpam(
                        contact.phoneNumber,
                        token?.let { session.bearerToken(it) },
                    )
                }
                val tagType = when {
                    result.isSpam -> result.tagType
                    result.myReport?.status == "PENDING" -> result.myReport.tagType
                    else -> null
                }
                withContext(Dispatchers.IO) {
                    AppDatabase.getInstance(this@MainActivity).recentContactDao()
                        .markChecked(contact.phoneNumber, tagType, System.currentTimeMillis())
                }

                tvPromptQuestion.visibility = View.GONE
                tvLookupResult.visibility = View.VISIBLE
                btnLookupNow.visibility = View.GONE

                tvLookupResult.text = when {
                    result.isSpam && result.tagType == "RED" -> getString(R.string.lookup_result_red)
                    result.isSpam && result.tagType == "YELLOW" -> getString(R.string.lookup_result_yellow)
                    result.myReport?.status == "PENDING" -> getString(R.string.lookup_result_pending)
                    else -> getString(R.string.lookup_result_safe)
                }
                tvLookupResult.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        when {
                            result.isSpam && result.tagType == "RED" -> android.R.color.holo_red_dark
                            result.isSpam && result.tagType == "YELLOW" -> android.R.color.holo_orange_dark
                            result.myReport?.status == "PENDING" -> android.R.color.holo_blue_dark
                            else -> android.R.color.holo_green_dark
                        },
                    ),
                )
                if (result.isSpam && result.tagType == "RED") rbSaveRed.isChecked = true
                if (result.isSpam && result.tagType == "YELLOW") rbSaveYellow.isChecked = true
            } catch (_: Exception) {
                tvLookupResult.visibility = View.VISIBLE
                tvLookupResult.text = getString(R.string.lookup_result_failed)
                btnLookupNow.isEnabled = true
                btnLookupNow.text = getString(R.string.lookup_prompt_yes)
            }
        }
    }

    private fun savePromptNumber() {
        val contact = promptContact ?: return
        val tagType = when {
            rbSaveRed.isChecked -> "RED"
            rbSaveYellow.isChecked -> "YELLOW"
            else -> null
        }
        if (tagType == null) {
            Toast.makeText(this, R.string.save_need_tag, Toast.LENGTH_SHORT).show()
            return
        }

        btnSaveToList.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val saved = NumberBook.save(
                    context = this@MainActivity,
                    phoneNumber = contact.phoneNumber,
                    tagType = tagType,
                    note = contact.lastMessagePreview,
                    shareToServer = cbShareServer.isChecked,
                )
                AppDatabase.getInstance(this@MainActivity).recentContactDao()
                    .markPromptDismissed(contact.phoneNumber, System.currentTimeMillis())
                saved
            }
            Toast.makeText(this@MainActivity, toastFor(result.shareResult), Toast.LENGTH_SHORT).show()
            btnSaveToList.isEnabled = true
            bindLookupPrompt()
        }
    }

    private fun toastFor(result: NumberBook.ShareResult): Int = when (result) {
        NumberBook.ShareResult.LOCAL_ONLY -> R.string.save_done_local
        NumberBook.ShareResult.SHARED -> R.string.save_done_shared
        NumberBook.ShareResult.QUEUED -> R.string.save_done_queued
        NumberBook.ShareResult.DUPLICATE -> R.string.save_done_duplicate
        NumberBook.ShareResult.LIMIT -> R.string.save_done_limit
        NumberBook.ShareResult.FAILED -> R.string.save_done_queued
    }

    private fun dismissPrompt() {
        val contact = promptContact ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@MainActivity).recentContactDao()
                    .markPromptDismissed(contact.phoneNumber, System.currentTimeMillis())
            }
            promptContact = null
            cardPrompt.visibility = View.GONE
            tvPromptEmpty.visibility = View.VISIBLE
        }
    }

    private suspend fun syncWallet() {
        val token = session.getToken() ?: return
        try {
            val wallet = withContext(Dispatchers.IO) {
                RetrofitClient.service.getWallet(session.bearerToken(token))
            }
            val sub = wallet.subscription

            val expiryMs = sub.expires_at?.let {
                runCatching {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).parse(it)?.time
                }.getOrNull()
            } ?: 0L

            withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@MainActivity).spamDao().saveSubscription(
                    UserSubscription(isAlertActive = sub.is_active, expiryTimestamp = expiryMs),
                )
            }

            val subText = if (sub.is_active) {
                getString(R.string.home_sub_active, sub.days_remaining)
            } else {
                getString(R.string.home_sub_inactive)
            }

            findViewById<TextView>(R.id.tvSubscriptionStatus).text = subText
            findViewById<TextView>(R.id.tvBalance).text = "잔액: ${wallet.balance} token"
        } catch (_: Exception) {
            // 오프라인 상태 — 캐시된 Room DB 값 유지
        }
    }
}
