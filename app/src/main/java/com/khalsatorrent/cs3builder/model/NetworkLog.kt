package com.khalsatorrent.cs3builder.model

/**
 * One captured network event.
 *
 * [source] says how it was captured:
 *  - "webview" – seen by shouldInterceptRequest only (URL + request headers)
 *  - "deep"    – fetched through OkHttp on the WebView's behalf (full status/headers/body)
 *  - "js"      – reported by the injected fetch/XHR hook (request + response bodies)
 *  - "dom"     – rendered page HTML snapshot taken after the page finished loading
 *
 * [kind] is a coarse resource type, see [LogKind].
 */
data class NetworkLog(
    val id: String,
    val timestamp: Long,
    val method: String,
    val url: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String?,
    val responseCode: Int,
    val responseHeaders: Map<String, String>,
    val responseBody: String?,
    val cookies: List<String>,
    val mimeType: String?,
    val isXHR: Boolean,
    val duration: Long,
    val source: String = "webview",
    val kind: String = LogKind.OTHER,
    val isMainFrame: Boolean = false,
    val pageUrl: String? = null,
    val responseSize: Long = -1L
) {
    val host: String
        get() = try { java.net.URI(url).host ?: "" } catch (_: Exception) { "" }

    fun header(name: String): String? =
        requestHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    fun responseHeader(name: String): String? =
        responseHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

object LogKind {
    const val DOC = "doc"          // HTML document (main frame or iframe)
    const val XHR = "xhr"          // fetch / XHR / JSON API
    const val STREAM = "stream"    // HLS / DASH playlists
    const val MEDIA = "media"      // direct video/audio files and segments
    const val SCRIPT = "script"
    const val STYLE = "style"
    const val IMAGE = "image"
    const val FONT = "font"
    const val DOM = "dom"          // rendered DOM snapshot
    const val SOCKET = "socket"
    const val OTHER = "other"

    private val IMAGE_EXT = listOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico", ".avif", ".bmp")
    private val FONT_EXT = listOf(".woff", ".woff2", ".ttf", ".otf", ".eot")
    private val MEDIA_EXT = listOf(".mp4", ".mkv", ".webm", ".m4v", ".ts", ".m4s", ".mp3", ".aac", ".m4a", ".flv", ".avi", ".mov", ".vtt", ".srt")
    private val STREAM_EXT = listOf(".m3u8", ".mpd", ".m3u")

    fun classify(url: String, mime: String? = null, accept: String? = null, isXhr: Boolean = false): String {
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        val m = mime?.lowercase().orEmpty()
        val a = accept?.lowercase().orEmpty()
        return when {
            STREAM_EXT.any { path.endsWith(it) } || m.contains("mpegurl") || m.contains("dash+xml") -> STREAM
            MEDIA_EXT.any { path.endsWith(it) } || m.startsWith("video/") || m.startsWith("audio/") -> MEDIA
            IMAGE_EXT.any { path.endsWith(it) } || m.startsWith("image/") -> IMAGE
            FONT_EXT.any { path.endsWith(it) } || m.startsWith("font/") || m.contains("font-") -> FONT
            path.endsWith(".css") || m == "text/css" -> STYLE
            path.endsWith(".js") || path.endsWith(".mjs") || m.contains("javascript") -> SCRIPT
            isXhr || m.contains("json") || a.startsWith("application/json") -> XHR
            m.contains("html") || a.startsWith("text/html") -> DOC
            else -> OTHER
        }
    }

    fun label(kind: String): String = when (kind) {
        DOC -> "DOC"; XHR -> "API"; STREAM -> "HLS"; MEDIA -> "MEDIA"; SCRIPT -> "JS"
        STYLE -> "CSS"; IMAGE -> "IMG"; FONT -> "FONT"; DOM -> "DOM"; SOCKET -> "WS"
        else -> "—"
    }

    fun isNoise(kind: String) = kind == IMAGE || kind == FONT || kind == STYLE || kind == SCRIPT
}
