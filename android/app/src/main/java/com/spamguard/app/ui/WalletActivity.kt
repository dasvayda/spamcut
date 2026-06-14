package com.spamguard.app.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.spamguard.app.R
import com.spamguard.app.data.SessionManager
import com.spamguard.app.data.api.RetrofitClient
import com.spamguard.app.data.api.TransferRequest
import kotlinx.coroutines.launch

class WalletActivity : AppCompatActivity() {

    private lateinit var session: SessionManager
    private lateinit var tvBalance: TextView
    private lateinit var tvSubStatus: TextView
    private lateinit var btnActivate: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallet)

        session = SessionManager(this)
        tvBalance = findViewById(R.id.tvWalletBalance)
        tvSubStatus = findViewById(R.id.tvWalletSubStatus)
        btnActivate = findViewById(R.id.btnActivateSubscription)

        val etTransferPhone = findViewById<EditText>(R.id.etTransferPhone)
        val etTransferAmount = findViewById<EditText>(R.id.etTransferAmount)
        val btnTransfer = findViewById<Button>(R.id.btnTransfer)

        loadWallet()

        btnActivate.setOnClickListener {
            lifecycleScope.launch {
                val token = session.getToken() ?: return@launch
                try {
                    val result = RetrofitClient.service.activateSubscription(session.bearerToken(token))
                    Toast.makeText(
                        this@WalletActivity,
                        "Subscription activated! Expires: ${result.expires_at}",
                        Toast.LENGTH_LONG,
                    ).show()
                    loadWallet()
                } catch (e: Exception) {
                    Toast.makeText(this@WalletActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnTransfer.setOnClickListener {
            val targetPhone = etTransferPhone.text.toString().trim()
            val amount = etTransferAmount.text.toString().toIntOrNull()

            if (!targetPhone.matches(Regex("^\\+[1-9]\\d{6,14}$"))) {
                Toast.makeText(this, "Invalid target phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (amount == null || amount <= 0) {
                Toast.makeText(this, "Enter a valid amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                val token = session.getToken() ?: return@launch
                try {
                    val result = RetrofitClient.service.transfer(
                        bearer = session.bearerToken(token),
                        body = TransferRequest(targetPhone, amount),
                    )
                    Toast.makeText(
                        this@WalletActivity,
                        "Sent ${result.transferred} \$STK to ${result.to}",
                        Toast.LENGTH_LONG,
                    ).show()
                    etTransferPhone.text.clear()
                    etTransferAmount.text.clear()
                    loadWallet()
                } catch (e: Exception) {
                    Toast.makeText(this@WalletActivity, "Transfer failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadWallet() {
        lifecycleScope.launch {
            val token = session.getToken() ?: return@launch
            try {
                val wallet = RetrofitClient.service.getWallet(session.bearerToken(token))
                tvBalance.text = "Balance: ${wallet.balance} \$STK"
                val sub = wallet.subscription
                tvSubStatus.text = if (sub.is_active)
                    "Real-Time Alert: Active (${sub.days_remaining} days left)"
                else
                    "Real-Time Alert: Inactive"

                btnActivate.text = "Activate 30 Days (Cost: 10 \$STK)"
                btnActivate.isEnabled = wallet.balance >= 10
            } catch (e: Exception) {
                tvBalance.text = "Could not load wallet"
            }
        }
    }
}
