package com.spamguard.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.spamguard.app.R
import com.spamguard.app.data.PhoneNumbers
import com.spamguard.app.data.api.BatchCheckRequest
import com.spamguard.app.data.api.RetrofitClient
import com.spamguard.app.data.db.AppDatabase
import com.spamguard.app.data.db.entities.RecentContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 최근 수신 내역 화면.
//
// 이 화면이 있는 이유: 웹사이트에 들어가 번호를 직접 옮겨 적는 방식은 접근성이 떨어진다.
// 문자·전화를 받는 순간 번호가 이 목록에 자동으로 쌓이고, 사용자는 항목을 눌러
// [신고하기] · [공유하기] · [최신 정보 받기] 중 하나만 고르면 된다.
//
// 세 동작의 데이터 방향:
//   신고하기      내 기기 → 서버 (스팸 등록)
//   공유하기      내 기기 → 친구 (외부 앱으로 전달)
//   최신 정보 받기 서버 → 내 기기 (판정 갱신)
class RecentActivity : AppCompatActivity() {

    private lateinit var adapter: RecentContactAdapter
    private lateinit var emptyView: TextView
    private lateinit var btnRefreshAll: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recent)

        emptyView = findViewById(R.id.tvRecentEmpty)
        btnRefreshAll = findViewById(R.id.btnRefreshAll)

        adapter = RecentContactAdapter { contact -> showActionSheet(contact) }
        findViewById<RecyclerView>(R.id.rvRecent).also {
            it.layoutManager = LinearLayoutManager(this)
            it.adapter = adapter
        }

        btnRefreshAll.setOnClickListener { refreshAll() }
    }

    override fun onResume() {
        super.onResume()
        // 신고 화면에서 돌아왔을 때 상태가 반영되도록 매번 다시 읽는다
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

    // 항목 탭 시 동작 선택 — 메뉴 이름만으로 무엇이 어디로 가는지 알 수 있게 설명을 함께 보여준다
    private fun showActionSheet(contact: RecentContact) {
        val actions = arrayOf(
            "${getString(R.string.action_report)}\n${getString(R.string.action_report_hint)}",
            "${getString(R.string.action_share)}\n${getString(R.string.action_share_hint)}",
            "${getString(R.string.action_refresh)}\n${getString(R.string.action_refresh_hint)}",
            getString(R.string.action_delete),
        )

        AlertDialog.Builder(this)
            .setTitle(PhoneNumbers.toDisplay(contact.phoneNumber))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> startReport(contact)
                    1 -> shareContact(contact)
                    2 -> refreshOne(contact)
                    3 -> deleteContact(contact)
                }
            }
            .show()
    }

    // [신고하기] — 신고 화면으로 번호와 문자 내용을 미리 채워 넘긴다.
    // 서버 전송은 신고 화면에서 사용자가 제출 버튼을 눌렀을 때만 일어난다.
    private fun startReport(contact: RecentContact) {
        startActivity(
            Intent(this, ReportActivity::class.java).apply {
                putExtra(ReportActivity.EXTRA_PREFILL_NUMBER, contact.phoneNumber)
                putExtra(ReportActivity.EXTRA_PREFILL_DESCRIPTION, contact.lastMessagePreview)
            },
        )
    }

    // [공유하기] — 시스템 공유 시트로 번호와 판정 결과를 친구에게 전달한다.
    // P2P 초대 장치와 같은 맥락: 공유가 곧 서비스 소개다.
    private fun shareContact(contact: RecentContact) {
        val display = PhoneNumbers.toDisplay(contact.phoneNumber)
        val verdict = when (contact.tagType) {
            "RED" -> getString(R.string.status_red)
            "YELLOW" -> getString(R.string.status_yellow)
            else -> null
        }

        val text = buildString {
            if (verdict != null) {
                append("[$verdict] $display 번호를 조심하세요.\n")
            } else {
                append("$display 번호로 문자/전화가 왔습니다. 확인해 보세요.\n")
            }
            append("SpamGuard에서 번호를 조회할 수 있습니다: $WEB_URL")
        }

        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                getString(R.string.action_share),
            ),
        )
    }

    // [최신 정보 받기] — 이 번호 하나만 서버에서 다시 확인
    private fun refreshOne(contact: RecentContact) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    RetrofitClient.service.checkSpam(contact.phoneNumber)
                }
                withContext(Dispatchers.IO) {
                    AppDatabase.getInstance(this@RecentActivity).recentContactDao()
                        .markChecked(contact.phoneNumber, result.tagType, System.currentTimeMillis())
                }
                loadList()
                Toast.makeText(this@RecentActivity, getString(R.string.recent_refresh_done, 1), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@RecentActivity, R.string.recent_refresh_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // [최신 정보 받기] — 목록 전체를 배치 엔드포인트로 한 번에 갱신
    private fun refreshAll() {
        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(this@RecentActivity).recentContactDao()

            // E.164로 정규화된 번호만 서버가 받는다 (단축번호 등은 조회 대상에서 제외)
            val numbers = withContext(Dispatchers.IO) {
                dao.getRecent(BATCH_LIMIT)
                    .map { it.phoneNumber }
                    .filter { PhoneNumbers.isE164(it) }
            }
            if (numbers.isEmpty()) {
                Toast.makeText(this@RecentActivity, getString(R.string.recent_refresh_done, 0), Toast.LENGTH_SHORT).show()
                return@launch
            }

            btnRefreshAll.isEnabled = false
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.service.checkSpamBatch(BatchCheckRequest(numbers))
                }
                val now = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    for (item in response.results) {
                        dao.markChecked(item.number, item.tagType, now)
                    }
                }
                loadList()
                Toast.makeText(
                    this@RecentActivity,
                    getString(R.string.recent_refresh_done, response.results.size),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this@RecentActivity, R.string.recent_refresh_failed, Toast.LENGTH_SHORT).show()
            } finally {
                btnRefreshAll.isEnabled = true
            }
        }
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

        // 백엔드 /check-spam/batch 의 요청당 상한과 맞춘다
        private const val BATCH_LIMIT = 100

        // TODO(Stage 4): SEO 웹 도메인 확정 시 교체
        private const val WEB_URL = "https://spamguard.app"
    }
}
