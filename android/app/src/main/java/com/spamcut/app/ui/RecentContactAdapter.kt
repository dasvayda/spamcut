package com.spamcut.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.spamcut.app.R
import com.spamcut.app.data.PhoneNumbers
import com.spamcut.app.data.db.entities.RecentContact
import java.util.concurrent.TimeUnit

// 최근 수신 내역 목록 어댑터 — 번호 한 줄에 상태·수신 요약·문자 미리보기를 보여준다
class RecentContactAdapter(
    private val onItemClick: (RecentContact) -> Unit,
) : RecyclerView.Adapter<RecentContactAdapter.ViewHolder>() {

    private var items: List<RecentContact> = emptyList()

    fun submit(newItems: List<RecentContact>) {
        items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.tvItemNumber)
        val status: TextView = view.findViewById(R.id.tvItemStatus)
        val meta: TextView = view.findViewById(R.id.tvItemMeta)
        val preview: TextView = view.findViewById(R.id.tvItemPreview)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_contact, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        holder.number.text = PhoneNumbers.toDisplay(item.phoneNumber)

        val (statusText, statusColor) = when {
            item.reportedAt != null -> context.getString(R.string.status_reported) to COLOR_MUTED
            item.tagType == "RED" -> context.getString(R.string.status_red) to COLOR_RED
            item.tagType == "YELLOW" -> context.getString(R.string.status_yellow) to COLOR_YELLOW
            item.isUnchecked() -> context.getString(R.string.status_unchecked) to COLOR_MUTED
            else -> context.getString(R.string.status_safe) to COLOR_SAFE
        }
        holder.status.text = statusText
        holder.status.setTextColor(statusColor)

        holder.meta.text = buildMeta(item)

        val preview = item.lastMessagePreview
        if (preview.isNullOrBlank()) {
            holder.preview.visibility = View.GONE
        } else {
            holder.preview.visibility = View.VISIBLE
            holder.preview.text = preview
        }

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    private fun buildMeta(item: RecentContact): String {
        val parts = mutableListOf<String>()
        if (item.smsCount > 0) parts.add("문자 ${item.smsCount}건")
        if (item.callCount > 0) parts.add("전화 ${item.callCount}건")
        parts.add(relativeTime(item.lastReceivedAt))
        return parts.joinToString(" · ")
    }

    private fun relativeTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff)
        val days = TimeUnit.MILLISECONDS.toDays(diff)
        return when {
            minutes < 1 -> "방금"
            minutes < 60 -> "${minutes}분 전"
            hours < 24 -> "${hours}시간 전"
            else -> "${days}일 전"
        }
    }

    companion object {
        private const val COLOR_RED = 0xFFD32F2F.toInt()
        private const val COLOR_YELLOW = 0xFFF9A825.toInt()
        private const val COLOR_SAFE = 0xFF2E7D32.toInt()
        private const val COLOR_MUTED = 0xFF757575.toInt()
    }
}
