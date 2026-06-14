package com.spamguard.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.spamguard.app.data.SessionManager
import com.spamguard.app.data.api.RetrofitClient
import com.spamguard.app.data.db.AppDatabase
import com.spamguard.app.data.db.entities.UserSubscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                syncSubscription(context)
            } catch (e: Exception) {
                Log.w("BootReceiver", "Subscription sync failed: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun syncSubscription(context: Context) {
        val session = SessionManager(context)
        val token = session.getToken() ?: return

        val wallet = RetrofitClient.service.getWallet(session.bearerToken(token))
        val sub = wallet.subscription

        val expiryMs = sub.expires_at?.let {
            runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).parse(it)?.time
            }.getOrNull()
        } ?: 0L

        AppDatabase.getInstance(context).spamDao().saveSubscription(
            UserSubscription(
                isAlertActive = sub.is_active,
                expiryTimestamp = expiryMs,
            )
        )
        Log.d("BootReceiver", "Subscription synced: active=${sub.is_active}")
    }
}
