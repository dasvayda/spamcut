package com.spamguard.app.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.spamguard.app.R
import com.spamguard.app.data.PhoneNumbers
import com.spamguard.app.data.SessionManager
import com.spamguard.app.data.api.ReportRequest
import com.spamguard.app.data.api.RetrofitClient
import com.spamguard.app.data.db.AppDatabase
import com.spamguard.app.data.db.entities.PendingReport
import com.spamguard.app.work.PendingReportWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)

        val etPhone = findViewById<EditText>(R.id.etReportPhone)
        val rbRed = findViewById<RadioButton>(R.id.rbRed)
        val rbYellow = findViewById<RadioButton>(R.id.rbYellow)
        val etDesc = findViewById<EditText>(R.id.etDescription)
        val btnSubmit = findViewById<Button>(R.id.btnSubmitReport)

        // 알림 탭 또는 최근 수신 내역에서 넘어온 경우 번호·문자 내용을 미리 채운다
        intent.getStringExtra(EXTRA_PREFILL_NUMBER)?.let { etPhone.setText(it) }
        intent.getStringExtra(EXTRA_PREFILL_DESCRIPTION)?.let { etDesc.setText(it) }

        btnSubmit.setOnClickListener {
            val phone = PhoneNumbers.toE164(etPhone.text.toString())
            val tagType = when {
                rbRed.isChecked -> "RED"
                rbYellow.isChecked -> "YELLOW"
                else -> null
            }

            if (phone == null) {
                Toast.makeText(this, "전화번호 형식을 확인해 주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (tagType == null) {
                Toast.makeText(this, "신고 유형을 선택해 주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSubmit.isEnabled = false
            lifecycleScope.launch {
                val session = SessionManager(this@ReportActivity)
                val token = session.getToken()
                if (token == null) {
                    Toast.makeText(this@ReportActivity, "로그인이 필요합니다", Toast.LENGTH_SHORT).show()
                    btnSubmit.isEnabled = true
                    return@launch
                }

                val description = etDesc.text.toString().ifBlank { null }

                try {
                    withContext(Dispatchers.IO) {
                        RetrofitClient.service.report(
                            bearer = session.bearerToken(token),
                            body = ReportRequest(
                                phone_number = phone,
                                tag_type = tagType,
                                description = description,
                            ),
                        )
                    }
                    markReported(phone)
                    Toast.makeText(this@ReportActivity, "신고가 접수되었습니다", Toast.LENGTH_SHORT).show()
                    finish()
                } catch (e: Exception) {
                    // 네트워크 실패 시 로컬 큐에 넣고 복귀 시 자동 전송 — 사용자가 다시 입력할 필요 없음
                    queueForRetry(phone, tagType, description)
                    markReported(phone)
                    Toast.makeText(
                        this@ReportActivity,
                        "지금은 연결이 어려워 저장해 두었습니다. 네트워크가 복구되면 자동으로 전송됩니다.",
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                }
            }
        }
    }

    // 최근 수신 내역에 신고 사실을 표시 — 같은 번호를 반복 신고하지 않도록
    private suspend fun markReported(phoneNumber: String) {
        withContext(Dispatchers.IO) {
            AppDatabase.getInstance(this@ReportActivity).recentContactDao()
                .markReported(phoneNumber, System.currentTimeMillis())
        }
    }

    private suspend fun queueForRetry(phoneNumber: String, tagType: String, description: String?) {
        withContext(Dispatchers.IO) {
            AppDatabase.getInstance(this@ReportActivity).pendingReportDao().insert(
                PendingReport(
                    phoneNumber = phoneNumber,
                    tagType = tagType,
                    description = description,
                ),
            )
        }
        PendingReportWorker.scheduleOnNetworkAvailable(this)
    }

    companion object {
        const val EXTRA_PREFILL_NUMBER = "prefill_number"
        const val EXTRA_PREFILL_DESCRIPTION = "prefill_description"
    }
}
