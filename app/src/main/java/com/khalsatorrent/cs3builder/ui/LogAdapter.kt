package com.khalsatorrent.cs3builder.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.khalsatorrent.cs3builder.R
import com.khalsatorrent.cs3builder.model.LogKind
import com.khalsatorrent.cs3builder.model.NetworkLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter(
    private val onClick: (NetworkLog) -> Unit,
    private val onLongClick: (NetworkLog) -> Unit
) : ListAdapter<NetworkLog, LogAdapter.LogViewHolder>(DIFF_CALLBACK) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder =
        LogViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_log_entry, parent, false))

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) = holder.bind(getItem(position))

    inner class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMethod: TextView = itemView.findViewById(R.id.tvMethod)
        private val tvKind: TextView = itemView.findViewById(R.id.tvKind)
        private val tvHost: TextView = itemView.findViewById(R.id.tvHost)
        private val tvUrl: TextView = itemView.findViewById(R.id.tvUrl)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvMeta)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)

        fun bind(log: NetworkLog) {
            tvMethod.text = log.method
            tvMethod.setBackgroundColor(methodColor(log.method))
            tvKind.text = LogKind.label(log.kind)
            tvKind.setTextColor(kindColor(log.kind))

            tvHost.text = log.host.ifBlank { log.url.substringBefore("/", log.url) }
            val rest = log.url.substringAfter("://").substringAfter('/', "")
            tvUrl.text = "/" + rest.take(240)

            if (log.responseCode > 0) {
                tvStatus.text = log.responseCode.toString()
                tvStatus.setTextColor(statusColor(log.responseCode))
            } else {
                tvStatus.text = if (log.kind == LogKind.DOM) "DOM" else "—"
                tvStatus.setTextColor(Color.GRAY)
            }
            val size = when {
                log.responseSize >= 0 -> humanSize(log.responseSize)
                log.responseBody != null -> humanSize(log.responseBody.length.toLong())
                else -> ""
            }
            tvMeta.text = listOf(size, if (log.duration > 0) "${log.duration}ms" else "").filter { it.isNotEmpty() }.joinToString(" · ")
            tvMeta.visibility = if (tvMeta.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            tvTimestamp.text = timeFormat.format(Date(log.timestamp))

            itemView.setOnClickListener { onClick(log) }
            itemView.setOnLongClickListener { onLongClick(log); true }
        }

        private fun methodColor(method: String): Int = when (method.uppercase()) {
            "GET" -> Color.parseColor("#2E7D32")
            "POST" -> Color.parseColor("#E65100")
            "PUT" -> Color.parseColor("#1565C0")
            "DELETE" -> Color.parseColor("#B71C1C")
            "PATCH" -> Color.parseColor("#6A1B9A")
            "DOM" -> Color.parseColor("#00838F")
            else -> Color.parseColor("#546E7A")
        }

        private fun kindColor(kind: String): Int = when (kind) {
            LogKind.STREAM, LogKind.MEDIA -> Color.parseColor("#D81B60")
            LogKind.XHR -> Color.parseColor("#5E35B1")
            LogKind.DOC, LogKind.DOM -> Color.parseColor("#00838F")
            else -> Color.GRAY
        }

        private fun statusColor(code: Int): Int = when (code) {
            in 200..299 -> Color.parseColor("#2E7D32")
            in 300..399 -> Color.parseColor("#1565C0")
            in 400..499 -> Color.parseColor("#E65100")
            else -> Color.parseColor("#B71C1C")
        }
    }

    companion object {
        fun humanSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        }

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<NetworkLog>() {
            override fun areItemsTheSame(oldItem: NetworkLog, newItem: NetworkLog) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: NetworkLog, newItem: NetworkLog) =
                oldItem.responseCode == newItem.responseCode && oldItem.responseSize == newItem.responseSize &&
                    oldItem.source == newItem.source && oldItem.kind == newItem.kind && oldItem.duration == newItem.duration &&
                    (oldItem.responseBody?.length ?: -1) == (newItem.responseBody?.length ?: -1)
        }
    }
}
