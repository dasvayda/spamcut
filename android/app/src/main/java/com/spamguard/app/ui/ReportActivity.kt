package com.spamguard.app.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.spamguard.app.R
import com.spamguard.app.data.SessionManager
import com.spamguard.app.data.api.ReportRequest
import com.spamguard.app.data.api.RetrofitClient
import kotlinx.coroutines.launch

class ReportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)

        val etPhone = findViewById<EditText>(R.id.etReportPhone)
        val rbRed = findViewById<RadioButton>(R.id.rbRed)
        val rbYellow = findViewById<RadioButton>(R.id.rbYellow)
        val etDesc = findViewById<EditText>(R.id.etDescription)
        val btnSubmit = findViewById<Button>(R.id.btnSubmitReport)

        // Pre-fill if launched from a notification tap
        intent.getStringExtra("prefill_number")?.let { etPhone.setText(it) }

        btnSubmit.setOnClickListener {
            val phone = etPhone.text.toString().trim()
            val tagType = when {
                rbRed.isChecked -> "RED"
                rbYellow.isChecked -> "YELLOW"
                else -> null
            }

            if (!phone.matches(Regex("^\\+[1-9]\\d{6,14}$"))) {
                Toast.makeText(this, "Invalid phone number format", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (tagType == null) {
                Toast.makeText(this, "Select a tag type", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSubmit.isEnabled = false
            lifecycleScope.launch {
                val session = SessionManager(this@ReportActivity)
                val token = session.getToken()
                if (token == null) {
                    Toast.makeText(this@ReportActivity, "Not logged in", Toast.LENGTH_SHORT).show()
                    btnSubmit.isEnabled = true
                    return@launch
                }

                try {
                    RetrofitClient.service.report(
                        bearer = session.bearerToken(token),
                        body = ReportRequest(
                            phone_number = phone,
                            tag_type = tagType,
                            description = etDesc.text.toString().ifBlank { null },
                        ),
                    )
                    Toast.makeText(this@ReportActivity, "Report submitted!", Toast.LENGTH_SHORT).show()
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@ReportActivity,
                        "Failed: ${e.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                    btnSubmit.isEnabled = true
                }
            }
        }
    }
}
