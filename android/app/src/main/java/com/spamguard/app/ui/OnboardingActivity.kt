package com.spamguard.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.spamguard.app.R
import com.spamguard.app.data.SessionManager
import com.spamguard.app.data.api.AuthRequest
import com.spamguard.app.data.api.RetrofitClient
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private var inviteCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        // 딥링크 spamguard://invite?code=XXXX 에서 코드 추출
        inviteCode = intent?.data?.getQueryParameter("code")

        val tvInviteNote = findViewById<TextView>(R.id.tvInviteNote)
        if (inviteCode != null) {
            tvInviteNote.text = "초대 링크로 접속했습니다. 가입 시 보너스 token이 지급됩니다!"
            tvInviteNote.visibility = android.view.View.VISIBLE
        }

        val etPhone = findViewById<EditText>(R.id.etPhoneNumber)
        val btnRegister = findViewById<Button>(R.id.btnRegister)

        btnRegister.setOnClickListener {
            val phone = etPhone.text.toString().trim()
            if (!phone.matches(Regex("^\\+[1-9]\\d{6,14}$"))) {
                Toast.makeText(this, "국가 코드 포함 형식으로 입력하세요. 예: +821012345678", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            btnRegister.isEnabled = false
            lifecycleScope.launch {
                try {
                    val response = RetrofitClient.service.register(
                        AuthRequest(phone_number = phone, invite_code = inviteCode)
                    )
                    SessionManager(this@OnboardingActivity)
                        .saveSession(response.token, response.userId, phone)
                    startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                    finish()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@OnboardingActivity,
                        "오류: ${e.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                    btnRegister.isEnabled = true
                }
            }
        }
    }
}
