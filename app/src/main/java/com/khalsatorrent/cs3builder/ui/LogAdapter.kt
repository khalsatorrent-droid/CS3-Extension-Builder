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
import com.khalsatorrent.cs3builder.model.NetworkLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter(private val onClick: (NetworkLog) -> Unit) :
    ListAdapter<NetworkLog, LogAdapter.LogViewHolder>(DIFF_CALLBACK) {

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log_entry, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMethod: TextView = itemView.findViewById(R.id.tvMethod)
        private val tvUrl: TextView = itemView.findViewById(R.id.tvUrl)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)

        fun bind(log: NetworkLog) {
            tvMethod.text = log.method
            tvMethod.setBackgroundColor(methodColor(log.method))

            val displayUrl = if (log.url.length > 80) log.url.take(80) + "…" else log.url
            tvUrl.text = displayUrl

            if (log.responseCode > 0) {
                tvStatus.text = log.responseCode.toString()
                tvStatus.setTextColor(statusColor(log.responseCode))
            } else {
                tvStatus.text = "---"
                tvStatus.setTextColor(Color.GRAY)
            }

            tvTimestamp.text = timeFormat.format(Date(log.timestamp))

            itemView.setOnClickListener { onClick(log) }
        }

        private fun methodColor(method: String): Int = when (method.uppercase()) {
            "GET"    -> Color.parseColor("#2E7D32")
            "POST"   -> Color.parseColor("#E65100")
            "PUT"    -> Color.parseColor("#1565C0")
            "DELETE" -> Color.parseColor("#B71C1C")
            "PATCH"  -> Color.parseColor("#6A1B9A")
            else     -> Color.parseColor("#546E7A")
        }

        private fun statusColor(code: Int): Int = when {
            code in 200..299 -> Color.parseColor("#2E7D32")
            code in 300..399 -> Color.parseColor("#1565C0")
            code in 400..499 -> Color.parseColor("#E65100")
            code >= 500      -> Color.parseColor("#B71C1C")
            else             -> Color.GRAY
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<NetworkLog>() {
            override fun areItemsTheSame(oldItem: NetworkLog, newItem: NetworkLog) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: NetworkLog, newItem: NetworkLog) =
                oldItem == newItem
        }
    }
}
