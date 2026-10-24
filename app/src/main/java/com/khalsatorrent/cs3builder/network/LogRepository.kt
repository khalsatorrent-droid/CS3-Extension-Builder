package com.khalsatorrent.cs3builder.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.khalsatorrent.cs3builder.model.LogKind
import com.khalsatorrent.cs3builder.model.NetworkLog
import java.io.File

/**
 * Single in-memory store of captured traffic (newest first).
 *
 * - Merges duplicate reports of the same request (e.g. the WebView sees the URL, then the JS hook
 *   reports the bodies) into one entry.
 * - Caps the list so long sessions do not run the app out of memory.
 * - Can be paused, and persisted so a session survives the app being killed.
 */
object LogRepository {

    const val MAX_LOGS = 5000
    private const val MERGE_WINDOW_MS = 60_000L
    private const val MERGE_SCAN = 300
    private const val SESSION_FILE = "last_session.json"

    private val lock = Any()
    private val list = ArrayList<NetworkLog>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var postScheduled = false

    val logs = MutableLiveData<List<NetworkLog>>(emptyList())
    val paused = MutableLiveData(false)

    val isPaused: Boolean get() = paused.value == true

    fun setPaused(value: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) paused.value = value else paused.postValue(value)
    }

    fun addLog(log: NetworkLog) {
        if (isPaused) return
        synchronized(lock) {
            val idx = findMergeTarget(log)
            if (idx >= 0) {
                list[idx] = merge(list[idx], log)
            } else {
                list.add(0, log)
                while (list.size > MAX_LOGS) list.removeAt(list.size - 1)
            }
        }
        schedulePost()
    }

    /** DOM snapshots replace the previous snapshot of the same page instead of piling up. */
    fun upsertDom(log: NetworkLog) {
        if (isPaused) return
        synchronized(lock) {
            val idx = list.indexOfFirst { it.kind == LogKind.DOM && it.url == log.url }
            if (idx >= 0) list.removeAt(idx)
            list.add(0, log)
        }
        schedulePost()
    }

    /** Fills in a status code reported by WebViewClient.onReceivedHttpError. */
    fun updateStatus(url: String, code: Int, headers: Map<String, String>) {
        synchronized(lock) {
            val idx = list.asSequence().take(MERGE_SCAN).indexOfFirst { it.url == url && it.responseCode == 0 }
            if (idx < 0) return
            val old = list[idx]
            list[idx] = old.copy(
                responseCode = code,
                responseHeaders = if (old.responseHeaders.isEmpty()) headers else old.responseHeaders
            )
        }
        schedulePost()
    }

    fun clearLogs() {
        synchronized(lock) { list.clear() }
        schedulePost()
    }

    fun getSnapshot(): List<NetworkLog> = synchronized(lock) { ArrayList(list) }

    fun size(): Int = synchronized(lock) { list.size }

    private fun findMergeTarget(log: NetworkLog): Int {
        if (log.kind == LogKind.DOM) return -1
        val limit = minOf(list.size, MERGE_SCAN)
        for (i in 0 until limit) {
            val o = list[i]
            if (o.url != log.url || !o.method.equals(log.method, true)) continue
            if (o.source == log.source) continue
            if (kotlin.math.abs(log.timestamp - o.timestamp) > MERGE_WINDOW_MS) continue
            // Only merge into an entry that is still missing something the new one has
            val adds = (o.responseCode == 0 && log.responseCode > 0) ||
                (o.responseBody == null && log.responseBody != null) ||
                (o.requestBody == null && log.requestBody != null)
            if (adds || o.source == "webview") return i
        }
        return -1
    }

    private fun merge(old: NetworkLog, new: NetworkLog): NetworkLog {
        val better = if (old.source == "webview") new else old
        return old.copy(
            requestHeaders = old.requestHeaders + new.requestHeaders.filterKeys { k ->
                old.requestHeaders.keys.none { it.equals(k, true) }
            },
            requestBody = old.requestBody ?: new.requestBody,
            responseCode = if (old.responseCode > 0) old.responseCode else new.responseCode,
            responseHeaders = old.responseHeaders.ifEmpty { new.responseHeaders },
            responseBody = old.responseBody ?: new.responseBody,
            cookies = (old.cookies + new.cookies).distinct(),
            mimeType = old.mimeType ?: new.mimeType,
            isXHR = old.isXHR || new.isXHR,
            duration = if (old.duration > 0) old.duration else new.duration,
            source = better.source,
            kind = if (old.kind == LogKind.OTHER) new.kind else old.kind,
            pageUrl = old.pageUrl ?: new.pageUrl,
            responseSize = if (old.responseSize >= 0) old.responseSize else new.responseSize
        )
    }

    private fun schedulePost() {
        if (postScheduled) return
        postScheduled = true
        main.postDelayed({
            postScheduled = false
            logs.value = getSnapshot()
        }, 120)
    }

    // ---- persistence ----

    fun saveSession(context: Context) {
        val snapshot = getSnapshot()
        val file = File(context.filesDir, SESSION_FILE)
        try {
            if (snapshot.isEmpty()) { file.delete(); return }
            file.bufferedWriter().use { Gson().toJson(snapshot, it) }
        } catch (_: Throwable) { }
    }

    /** Restores the last session if nothing has been captured yet. Returns the number restored. */
    fun restoreSession(context: Context): Int {
        val file = File(context.filesDir, SESSION_FILE)
        if (!file.exists() || size() > 0) return 0
        return try {
            val type = object : TypeToken<List<NetworkLog>>() {}.type
            val restored: List<NetworkLog> = file.bufferedReader().use { Gson().fromJson(it, type) } ?: return 0
            // Gson bypasses Kotlin defaults for missing fields, so normalise nullable-unsafe ones
            val clean = restored.mapNotNull { l ->
                @Suppress("SENSELESS_COMPARISON")
                if (l.url == null || l.method == null) null else l.copy(
                    requestHeaders = l.requestHeaders ?: emptyMap(),
                    responseHeaders = l.responseHeaders ?: emptyMap(),
                    cookies = l.cookies ?: emptyList(),
                    source = l.source ?: "webview",
                    kind = l.kind ?: LogKind.OTHER,
                    id = l.id ?: java.util.UUID.randomUUID().toString()
                )
            }
            synchronized(lock) {
                if (list.isNotEmpty()) return 0
                list.addAll(clean.take(MAX_LOGS))
            }
            schedulePost()
            clean.size
        } catch (_: Throwable) { 0 }
    }
}
