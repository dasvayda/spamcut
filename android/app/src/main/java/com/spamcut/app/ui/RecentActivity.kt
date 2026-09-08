package com.spamcut.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.spamcut.app.R
import com.spamcut.app.data.PhoneNumbers
import com.spamcut.app.data.db.AppDatabase
import com.spamcut.app.data.db.entities.RecentContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecentActivity : AppCompatActivity() {

    private lateinit var adapter: RecentContactAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recent)

        emptyView = findViewById(R.id.tvRecentEmpty)
        adapter = RecentContactAdapter { contact -> showActionSheet(contact) }
        findViewById<RecyclerView>(R.id.rvRecent).also {
            it.layoutManager = LinearLayoutManager(this)
            it.adapter = adapter
        }
    }

    override fun onResume() {
        super.onResume()
        loadList()
    }

    private fun loadList() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@RecentActivity).recentContactDao().getRecent(LIST_LIMIT)
            }
            adapter.submit(items)
            emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showActionSheet(contact: RecentContact) {
        val actions = arrayOf(
            getString(R.string.action_add_to_list),
            getString(R.string.action_delete),
        )
        AlertDialog.Builder(this)
            .setTitle(PhoneNumbers.toDisplay(contact.phoneNumber))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> startActivity(
                        Intent(this, ReportActivity::class.java).apply {
                            putExtra(ReportActivity.EXTRA_PREFILL_NUMBER, contact.phoneNumber)
                            putExtra(ReportActivity.EXTRA_PREFILL_DESCRIPTION, contact.lastMessagePreview)
                        },
                    )
                    1 -> deleteContact(contact)
                }
            }
            .show()
    }

    private fun deleteContact(contact: RecentContact) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@RecentActivity).recentContactDao()
                    .deleteByPhoneNumber(contact.phoneNumber)
            }
            loadList()
            Toast.makeText(this@RecentActivity, R.string.recent_deleted, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val LIST_LIMIT = 100
    }
}
