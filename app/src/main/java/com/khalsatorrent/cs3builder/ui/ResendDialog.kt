package com.khalsatorrent.cs3builder.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.khalsatorrent.cs3builder.export.LogExporter
import com.khalsatorrent.cs3builder.model.LogKind
import com.khalsatorrent.cs3builder.model.NetworkLog
import com.khalsatorrent.cs3builder.network.LogRepository
import com.khalsatorrent.cs3builder.utils.ShareUtils
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Edit & resend: replay a captured request with a changed URL, headers or body — the quickest way
 * to find out which headers/tokens an API really needs, or to try page=2, another id, another query.
 * The reply is shown and also added to Logs.
 */
object ResendDialog {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
    private val main = Handler(Looper.getMainLooper())

    private val SKIP = setOf("content-length", "host", "accept-encoding", "connection")

    fun show(context: Context, log: NetworkLog) {
        val dp = context.resources.displayMetrics.density
        val pad = (16 * dp).toInt()
        fun field(text: String, lines: Int, hint: String) = EditText(context).apply {
            setText(text)
            this.hint = hint
            typeface = Typeface.MONOSPACE
            textSize = 12f
            minLines = lines
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        fun label(t: String) = TextView(context).apply { text = t; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setPadding(0, (8 * dp).toInt(), 0, 0) }

        val method = field(if (log.method in setOf("DNS", "DOM", "CONNECT", "WS")) "GET" else log.method, 1, "GET")
        val url = field(log.url, 2, "https://…")
        val headers = field(
            log.requestHeaders.filterKeys { it.lowercase() !in SKIP }.entries.joinToString("\n") { "${it.key}: ${it.value}" },
            4, "Header: value (one per line)"
        )
        val body = field(log.requestBody.orEmpty(), 2, "Request body (optional)")

        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(label("Method")); addView(method)
            addView(label("URL")); addView(url)
            addView(label("Headers — delete one to see if the API still answers")); addView(headers)
            addView(label("Body")); addView(body)
        }
        AlertDialog.Builder(context)
            .setTitle("Edit & resend")
            .setView(ScrollView(context).apply { addView(form) })
            .setPositiveButton("Send") { _, _ ->
                send(context, method.text.toString().trim().uppercase().ifBlank { "GET" }, url.text.toString().trim(),
                    parseHeaders(headers.text.toString()), body.text.toString().takeIf { it.isNotBlank() }, log)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun parseHeaders(text: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lines().forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) out[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        return out
    }

    private fun send(context: Context, verb: String, url: String, headers: Map<String, String>, body: String?, original: NetworkLog) {
        if (!url.startsWith("http")) { toastLike(context, "URL must start with http"); return }
        val started = System.currentTimeMillis()
        Thread {
            val result = try {
                val type = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.toMediaTypeOrNull()
                val reqBody = when {
                    body != null -> body.toRequestBody(type)
                    verb in setOf("POST", "PUT", "PATCH") -> ByteArray(0).toRequestBody(null)
                    else -> null
                }
                val req = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> runCatching { header(k, v) } }
                    method(verb, reqBody)
                }.build()
                client.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    val respHeaders = r.headers.names().associateWith { r.headers(it).joinToString(", ") }
                    val mime = r.header("Content-Type")?.substringBefore(';')?.trim()
                    val entry = NetworkLog(
                        id = UUID.randomUUID().toString(), timestamp = started, method = verb, url = url,
                        requestHeaders = headers, requestBody = body, responseCode = r.code, responseHeaders = respHeaders,
                        responseBody = text, cookies = r.headers("Set-Cookie"), mimeType = mime,
                        isXHR = true, duration = System.currentTimeMillis() - started, source = "replay",
                        kind = LogKind.classify(url, mime, headers["Accept"], true), pageUrl = original.pageUrl,
                        responseSize = text.length.toLong()
                    )
                    LogRepository.addLog(entry)
                    "HTTP ${r.code} ${r.message} · ${text.length} chars · ${entry.duration} ms\n\n" + LogExporter.prettyBody(text.take(60_000))
                }
            } catch (e: Exception) {
                "Request failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            main.post {
                AlertDialog.Builder(context)
                    .setTitle("Response")
                    .setMessage(result.take(30_000))
                    .setPositiveButton("Close", null)
                    .setNeutralButton("Copy") { _, _ -> ShareUtils.copyToClipboard(context, result, "Response") }
                    .setNegativeButton("Edit again") { _, _ ->
                        show(context, original.copy(method = verb, url = url, requestHeaders = headers, requestBody = body))
                    }
                    .show()
            }
        }.start()
    }

    private fun toastLike(context: Context, msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
}
