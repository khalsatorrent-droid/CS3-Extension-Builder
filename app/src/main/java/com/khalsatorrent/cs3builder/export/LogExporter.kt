package com.khalsatorrent.cs3builder.export

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.khalsatorrent.cs3builder.model.LogKind
import com.khalsatorrent.cs3builder.model.NetworkLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Text / HAR / JSON exports of captured traffic. */
object LogExporter {

    private val NOISY_HEADERS = setOf(
        "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site",
        "sec-fetch-user", "upgrade-insecure-requests", "accept-language", "accept-encoding", "priority", "dnt",
        "cache-control", "pragma", "connection"
    )
    private val KEEP_RESPONSE_HEADERS = setOf(
        "content-type", "location", "set-cookie", "access-control-allow-origin", "access-control-allow-credentials",
        "server", "cf-ray", "x-powered-by", "content-disposition", "refresh"
    )
    private const val MAX_TOTAL = 1_800_000

    /**
     * A readable log meant to be pasted/uploaded to Claude (or any reviewer) to build an extension:
     * analysis first, then every important request in full, then a one-line index of the rest.
     */
    fun claudeLog(logs: List<NetworkLog>, analysisSummary: String?, title: String = "CS3 Extension Builder — Network Log"): String {
        val chrono = logs.sortedBy { it.timestamp }
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val important = chrono.filter { isImportant(it) }
        val rest = chrono.filterNot { isImportant(it) }
        val sb = StringBuilder()
        sb.appendLine(title)
        sb.appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        sb.appendLine("Requests: ${logs.size} (${important.size} shown in full, ${rest.size} listed)")
        sb.appendLine("Order: oldest first. Sources — deep: fetched with full response · js: page fetch/XHR hook · dom: rendered HTML · webview: URL only")
        sb.appendLine("=".repeat(72))
        if (!analysisSummary.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine(analysisSummary.trim())
            sb.appendLine()
            sb.appendLine("=".repeat(72))
        }
        sb.appendLine()
        sb.appendLine("## FULL REQUESTS")
        sb.appendLine()
        var truncated = false
        important.forEachIndexed { i, l ->
            if (truncated) return@forEachIndexed
            if (sb.length > MAX_TOTAL) { sb.appendLine("…[log truncated — export HAR for everything]"); truncated = true; return@forEachIndexed }
            sb.appendLine("[${i + 1}] ${time.format(Date(l.timestamp))}  ${l.method} ${statusText(l)}  ${LogKind.label(l.kind)}  (${l.source})")
            sb.appendLine("URL: ${l.url}")
            if (!l.pageUrl.isNullOrBlank() && l.pageUrl != l.url) sb.appendLine("From page: ${l.pageUrl}")
            val reqH = l.requestHeaders.filterKeys { it.lowercase() !in NOISY_HEADERS }
            if (reqH.isNotEmpty()) {
                sb.appendLine("Request headers:")
                reqH.forEach { (k, v) -> sb.appendLine("  $k: ${v.take(if (k.equals("cookie", true)) 1200 else 400)}") }
            }
            if (!l.requestBody.isNullOrBlank()) sb.appendLine("Request body: ${l.requestBody.take(3000)}")
            val respH = l.responseHeaders.filterKeys { it.lowercase() in KEEP_RESPONSE_HEADERS }
            if (respH.isNotEmpty()) {
                sb.appendLine("Response headers:")
                respH.forEach { (k, v) -> sb.appendLine("  $k: ${v.take(400)}") }
            }
            if (l.cookies.isNotEmpty() && l.kind != LogKind.DOM) {
                sb.appendLine("Set-Cookie:")
                l.cookies.take(10).forEach { sb.appendLine("  ${it.take(200)}") }
            }
            val body = l.responseBody
            if (!body.isNullOrBlank()) {
                val limit = when (l.kind) {
                    LogKind.DOC, LogKind.DOM -> 20_000
                    LogKind.STREAM -> 4_000
                    else -> 8_000
                }
                sb.appendLine("Response body (${body.length} chars${if (body.length > limit) ", first $limit" else ""}):")
                sb.appendLine(prettyIfJson(body.take(limit)))
            }
            sb.appendLine("-".repeat(72))
        }
        sb.appendLine()
        sb.appendLine("## OTHER REQUESTS (index)")
        rest.forEach { l -> sb.appendLine("${time.format(Date(l.timestamp))} ${l.method.padEnd(4)} ${statusText(l).padEnd(4)} ${LogKind.label(l.kind).padEnd(5)} ${l.url.take(220)}") }
        return sb.toString()
    }

    private fun statusText(l: NetworkLog) = if (l.responseCode > 0) l.responseCode.toString() else "—"

    fun isImportant(l: NetworkLog): Boolean = when (l.kind) {
        LogKind.IMAGE, LogKind.FONT, LogKind.STYLE, LogKind.SCRIPT -> false
        LogKind.MEDIA -> !l.url.substringBefore('?').let { it.endsWith(".ts") || it.endsWith(".m4s") || it.endsWith(".aac") }
        LogKind.DOC -> l.isMainFrame || l.responseBody != null || l.method != "GET"
        LogKind.OTHER -> l.responseBody != null || l.method == "POST"
        else -> true
    }

    private fun prettyIfJson(s: String): String {
        val t = s.trim()
        if (!(t.startsWith("{") || t.startsWith("["))) return s
        return try { GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(JsonParser.parseString(t)) } catch (_: Exception) { s }
    }

    fun prettyBody(s: String): String = prettyIfJson(s)

    /** HTTP Archive 1.2 — opens in Chrome DevTools, Charles, Fiddler, HAR viewers, etc. */
    fun har(logs: List<NetworkLog>, appVersion: String): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val entries = JsonArray()
        logs.sortedBy { it.timestamp }.filter { it.kind != LogKind.DOM && it.url.startsWith("http") }.forEach { l ->
            val req = JsonObject().apply {
                addProperty("method", l.method)
                addProperty("url", l.url)
                addProperty("httpVersion", "HTTP/1.1")
                add("headers", headerArray(l.requestHeaders))
                add("queryString", JsonArray().apply {
                    l.url.substringAfter('?', "").substringBefore('#').split('&').filter { it.isNotEmpty() }.forEach { p ->
                        add(JsonObject().apply { addProperty("name", p.substringBefore('=')); addProperty("value", p.substringAfter('=', "")) })
                    }
                })
                add("cookies", JsonArray())
                addProperty("headersSize", -1)
                addProperty("bodySize", l.requestBody?.length ?: 0)
                if (l.requestBody != null) add("postData", JsonObject().apply {
                    addProperty("mimeType", l.header("Content-Type") ?: "application/x-www-form-urlencoded")
                    addProperty("text", l.requestBody)
                })
            }
            val resp = JsonObject().apply {
                addProperty("status", l.responseCode)
                addProperty("statusText", "")
                addProperty("httpVersion", "HTTP/1.1")
                add("headers", headerArray(l.responseHeaders))
                add("cookies", JsonArray())
                add("content", JsonObject().apply {
                    addProperty("size", if (l.responseSize >= 0) l.responseSize else (l.responseBody?.length ?: 0).toLong())
                    addProperty("mimeType", l.mimeType ?: "")
                    if (l.responseBody != null) addProperty("text", l.responseBody)
                })
                addProperty("redirectURL", l.responseHeader("Location") ?: "")
                addProperty("headersSize", -1)
                addProperty("bodySize", -1)
            }
            entries.add(JsonObject().apply {
                addProperty("startedDateTime", iso.format(Date(l.timestamp)))
                addProperty("time", l.duration.coerceAtLeast(0))
                add("request", req)
                add("response", resp)
                add("cache", JsonObject())
                add("timings", JsonObject().apply { addProperty("send", 0); addProperty("wait", l.duration.coerceAtLeast(0)); addProperty("receive", 0) })
                addProperty("_source", l.source)
                addProperty("_kind", l.kind)
            })
        }
        val root = JsonObject().apply {
            add("log", JsonObject().apply {
                addProperty("version", "1.2")
                add("creator", JsonObject().apply { addProperty("name", "CS3 Extension Builder"); addProperty("version", appVersion) })
                add("pages", JsonArray())
                add("entries", entries)
            })
        }
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
    }

    private fun headerArray(h: Map<String, String>) = JsonArray().apply {
        h.forEach { (k, v) -> v.split('\n').forEach { line -> add(JsonObject().apply { addProperty("name", k); addProperty("value", line) }) } }
    }

    /** curl command that replays the request. */
    fun curl(l: NetworkLog): String = buildString {
        append("curl")
        if (l.method != "GET" && l.method != "DOM") append(" -X ${l.method}")
        append(" ").append(shq(l.url))
        l.requestHeaders.forEach { (k, v) -> if (k.lowercase() !in NOISY_HEADERS) append(" \\\n  -H ").append(shq("$k: $v")) }
        if (!l.requestBody.isNullOrBlank()) append(" \\\n  --data-raw ").append(shq(l.requestBody))
        append(" \\\n  --compressed")
    }

    private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
