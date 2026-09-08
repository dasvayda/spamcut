package com.spamcut.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.spamcut.app.R
import com.spamcut.app.data.NumberBook
import com.spamcut.app.data.PhoneNumbers
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
        val cbShare = findViewById<CheckBox>(R.id.cbShareServer)
        val btnSubmit = findViewById<Button>(R.id.btnSubmitReport)

        intent.getStringExtra(EXTRA_PREFILL_NUMBER)?.let { etPhone.setText(it) }
        intent.getStringExtra(EXTRA_PREFILL_DESCRIPTION)?.let { etDesc.setText(it) }
        cbShare.isChecked = !intent.getBooleanExtra(EXTRA_FORCE_LOCAL, false)
        if (intent.getBooleanExtra(EXTRA_FORCE_LOCAL, false)) {
            cbShare.isChecked = false
            cbShare.isEnabled = false
        }

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
                Toast.makeText(this, R.string.save_need_tag, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSubmit.isEnabled = false
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    NumberBook.save(
                        context = this@ReportActivity,
                        phoneNumber = phone,
                        tagType = tagType,
                        note = etDesc.text.toString().ifBlank { null },
                        shareToServer = cbShare.isChecked,
                    )
                }
                Toast.makeText(this@ReportActivity, toastFor(result.shareResult), Toast.LENGTH_SHORT).show()
                finish()
            }
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

    companion object {
        const val EXTRA_PREFILL_NUMBER = "prefill_number"
        const val EXTRA_PREFILL_DESCRIPTION = "prefill_description"
        const val EXTRA_FORCE_LOCAL = "force_local"
    }
}
