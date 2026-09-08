package com.spamcut.app.ui

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
import com.spamcut.app.data.AppSettings
import com.spamcut.app.data.NumberBook
import com.spamcut.app.data.PhoneNumbers
import com.spamcut.app.data.db.AppDatabase
import com.spamcut.app.data.db.entities.MySpamNumber
import com.spamcut.app.data.db.entities.SyncedSpamNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MyListActivity : AppCompatActivity() {

    private lateinit var mineAdapter: BookRowAdapter
    private lateinit var syncedAdapter: BookRowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_list)

        mineAdapter = BookRowAdapter { row ->
            if (row.source == BookRow.Source.MINE) confirmDeleteMine(row.phoneNumber)
        }
        syncedAdapter = BookRowAdapter { row ->
            if (row.source == BookRow.Source.SYNCED) copySyncedToMine(row)
        }

        findViewById<RecyclerView>(R.id.rvMine).apply {
            layoutManager = LinearLayoutManager(this@MyListActivity)
            adapter = mineAdapter
        }
        findViewById<RecyclerView>(R.id.rvSynced).apply {
            layoutManager = LinearLayoutManager(this@MyListActivity)
            adapter = syncedAdapter
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val syncOn = AppSettings(this).isCrowdSyncEnabled()
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@MyListActivity)
            val mine = withContext(Dispatchers.IO) { db.mySpamNumberDao().getAll() }
            mineAdapter.submit(mine.map { it.toRow() })
            findViewById<TextView>(R.id.tvMyEmpty).visibility =
                if (mine.isEmpty()) View.VISIBLE else View.GONE

            val header = findViewById<TextView>(R.id.tvSyncedHeader)
            val empty = findViewById<TextView>(R.id.tvSyncedEmpty)
            val list = findViewById<RecyclerView>(R.id.rvSynced)

            if (!syncOn) {
                header.visibility = View.VISIBLE
                header.text = getString(R.string.synced_hidden)
                empty.visibility = View.GONE
                list.visibility = View.GONE
                return@launch
            }

            val synced = withContext(Dispatchers.IO) { db.syncedSpamNumberDao().getAll() }
            header.visibility = View.VISIBLE
            header.text = getString(R.string.synced_list_title)
            list.visibility = View.VISIBLE
            syncedAdapter.submit(synced.map { it.toRow() })
            empty.visibility = if (synced.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun confirmDeleteMine(phoneNumber: String) {
        AlertDialog.Builder(this)
            .setTitle(PhoneNumbers.toDisplay(phoneNumber))
            .setItems(arrayOf(getString(R.string.action_delete))) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.getInstance(this@MyListActivity)
                            .mySpamNumberDao().deleteByPhoneNumber(phoneNumber)
                    }
                    load()
                }
            }
            .show()
    }

    private fun copySyncedToMine(row: BookRow) {
        AlertDialog.Builder(this)
            .setTitle(PhoneNumbers.toDisplay(row.phoneNumber))
            .setMessage(R.string.copy_to_mine_hint)
            .setPositiveButton(R.string.action_copy_to_mine) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        NumberBook.save(
                            context = this@MyListActivity,
                            phoneNumber = row.phoneNumber,
                            tagType = row.tagType,
                            note = null,
                            shareToServer = false,
                        )
                    }
                    Toast.makeText(this@MyListActivity, R.string.save_done_local, Toast.LENGTH_SHORT).show()
                    load()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun MySpamNumber.toRow() = BookRow(
        phoneNumber = phoneNumber,
        tagType = tagType,
        status = if (sharedAt != null) {
            getString(R.string.status_shared)
        } else {
            getString(R.string.status_local_only)
        },
        meta = if (tagType == "RED") getString(R.string.status_red) else getString(R.string.status_yellow),
        source = BookRow.Source.MINE,
    )

    private fun SyncedSpamNumber.toRow() = BookRow(
        phoneNumber = phoneNumber,
        tagType = tagType,
        status = getString(R.string.status_from_crowd),
        meta = if (tagType == "RED") getString(R.string.status_red) else getString(R.string.status_yellow),
        source = BookRow.Source.SYNCED,
    )
}

data class BookRow(
    val phoneNumber: String,
    val tagType: String,
    val status: String,
    val meta: String,
    val source: Source,
) {
    enum class Source { MINE, SYNCED }
}
