package com.spamcut.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.spamcut.app.R
import com.spamcut.app.data.PhoneNumbers

class BookRowAdapter(
    private val onItemClick: (BookRow) -> Unit,
) : RecyclerView.Adapter<BookRowAdapter.ViewHolder>() {

    private var items: List<BookRow> = emptyList()

    fun submit(newItems: List<BookRow>) {
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
        holder.number.text = PhoneNumbers.toDisplay(item.phoneNumber)
        holder.status.text = item.status
        holder.meta.text = item.meta
        holder.preview.visibility = View.GONE
        holder.itemView.setOnClickListener { onItemClick(item) }
    }
}
