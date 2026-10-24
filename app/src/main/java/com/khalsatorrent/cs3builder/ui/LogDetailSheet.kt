package com.khalsatorrent.cs3builder.ui

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.khalsatorrent.cs3builder.export.LogExporter
import com.khalsatorrent.cs3builder.model.LogKind
import com.khalsatorrent.cs3builder.model.NetworkLog
import com.khalsatorrent.cs3builder.utils.ShareUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full-height bottom sheet with every detail of one request plus copy actions. */
object LogDetailSheet {

    fun show(context: Context, log: NetworkLog) {
        val dialog = BottomSheetDialog(context)
        val dp = context.resources.displayMetrics.density
        val pad = (12 * dp).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        root.addView(TextView(context).apply {
            text = "${log.method} ${if (log.responseCode > 0) log.responseCode else ""} · ${LogKind.label(log.kind)} · ${log.source}"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(context).apply {
            text = log.url
            textSize = 12f
            setTextIsSelectable(true)
            maxLines = 4
            setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
        })

        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(label: String, onClick: () -> Unit) = actions.addView(
            MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                textSize = 12f
                minimumWidth = 0
                minWidth = 0
                setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = (6 * dp).toInt() }
                setOnClickListener { onClick() }
            }
        )
        val detail = buildDetail(log)
        action("URL") { ShareUtils.copyToClipboard(context, log.url, "URL") }
        action("cURL") { ShareUtils.copyToClipboard(context, LogExporter.curl(log), "cURL") }
        if (!log.responseBody.isNullOrBlank()) action("Response") { ShareUtils.copyToClipboard(context, log.responseBody, "Response") }
        action("All") { ShareUtils.copyToClipboard(context, detail, "Request") }
        action("Share") { ShareUtils.shareAsText(context, detail, "Request: ${log.url.take(60)}") }
        root.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(actions) })

        val body = TextView(context).apply {
            text = detail
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, pad, 0, pad * 3)
        }
        root.addView(NestedScrollView(context).apply {
            addView(body)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (context.resources.displayMetrics.heightPixels * 0.72).toInt())
        })

        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    fun buildDetail(log: NetworkLog): String = buildString {
        val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        appendLine("Time     : ${timeFmt.format(Date(log.timestamp))}")
        if (log.duration > 0) appendLine("Duration : ${log.duration} ms")
        if (log.responseSize >= 0) appendLine("Size     : ${LogAdapter.humanSize(log.responseSize)}")
        if (!log.pageUrl.isNullOrBlank() && log.pageUrl != log.url) appendLine("Page     : ${log.pageUrl}")
        if (log.mimeType != null) appendLine("MIME     : ${log.mimeType}")
        appendLine()
        if (log.requestHeaders.isNotEmpty()) {
            appendLine("── Request headers ──")
            log.requestHeaders.forEach { (k, v) -> appendLine("$k: $v") }
            appendLine()
        }
        if (!log.requestBody.isNullOrBlank()) {
            appendLine("── Request body ──")
            appendLine(LogExporter.prettyBody(log.requestBody.take(20_000)))
            appendLine()
        }
        if (log.responseHeaders.isNotEmpty()) {
            appendLine("── Response headers ──")
            log.responseHeaders.forEach { (k, v) -> appendLine("$k: $v") }
            appendLine()
        }
        if (log.cookies.isNotEmpty()) {
            appendLine(if (log.kind == LogKind.DOM) "── Cookies ──" else "── Set-Cookie ──")
            log.cookies.forEach { appendLine(it) }
            appendLine()
        }
        if (!log.responseBody.isNullOrBlank()) {
            appendLine("── Response body (${log.responseBody.length} chars) ──")
            appendLine(LogExporter.prettyBody(log.responseBody.take(60_000)))
            if (log.responseBody.length > 60_000) appendLine("…[use Response to copy all]")
        } else if (log.responseCode == 0 && log.source == "webview") {
            appendLine("(Only the URL was visible for this request. Turn on Deep capture in the browser menu to record status, headers and body.)")
        }
    }
}
