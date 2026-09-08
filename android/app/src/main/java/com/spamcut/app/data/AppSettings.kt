package com.spamcut.app.data

import android.content.Context

// 동기화 동의처럼 로그인과 별개인 설정.
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isCrowdSyncEnabled(): Boolean = prefs.getBoolean(KEY_CROWD_SYNC, false)

    fun setCrowdSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CROWD_SYNC, enabled).apply()
    }

    fun getSyncCursor(): String? = prefs.getString(KEY_SYNC_CURSOR, null)

    fun setSyncCursor(since: String?) {
        prefs.edit().putString(KEY_SYNC_CURSOR, since).apply()
    }

    companion object {
        private const val PREFS_NAME = "spamcut_settings"
        private const val KEY_CROWD_SYNC = "crowd_sync_enabled"
        private const val KEY_SYNC_CURSOR = "crowd_sync_cursor"
    }
}
