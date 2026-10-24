package com.khalsatorrent.cs3builder.network

import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.khalsatorrent.cs3builder.model.LogKind
import com.khalsatorrent.cs3builder.model.NetworkLog
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * "Deep capture": performs the WebView's GET requests through OkHttp so the app can record what
 * the WebView alone never exposes — status codes, response headers, Set-Cookie and response bodies.
 *
 * Only safe requests are handled (GET, no Range header, not images/fonts/scripts/video segments).
 * Anything that fails or is unsuitable falls back to the WebView's own network stack.
 */
class DeepCapture(private val packageName: String) {

    private val client get() = sharedClient

    class Result(val response: WebResourceResponse?, val log: NetworkLog)

    fun canHandle(request: WebResourceRequest): Boolean {
        if (!request.method.equals("GET", ignoreCase = true)) return false
        val scheme = request.url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val headers = request.requestHeaders ?: emptyMap()
        if (headers.keys.any { it.equals("Range", true) }) return false
        val accept = headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value
        return when (LogKind.classify(request.url.toString(), null, accept)) {
            LogKind.IMAGE, LogKind.FONT, LogKind.STYLE, LogKind.SCRIPT, LogKind.MEDIA -> false
            else -> !(accept?.startsWith("image/") == true || accept?.startsWith("video/") == true)
        }
    }

    /** Returns null when the request should be left to the WebView (network error etc.). */
    fun fetch(request: WebResourceRequest, userAgent: String, pageUrl: String?): Result? {
        val url = request.url.toString()
        val cookieManager = CookieManager.getInstance()
        val start = System.currentTimeMillis()

        val outHeaders = LinkedHashMap<String, String>()
        (request.requestHeaders ?: emptyMap()).forEach { (k, v) ->
            when {
                k.equals("Accept-Encoding", true) -> Unit           // let OkHttp handle gzip
                k.equals("If-None-Match", true) || k.equals("If-Modified-Since", true) -> Unit // always get a body, never a 304
                k.equals("X-Requested-With", true) && v == packageName -> Unit  // WebView fingerprint
                else -> outHeaders[k] = v
            }
        }
        if (outHeaders.keys.none { it.equals("User-Agent", true) }) outHeaders["User-Agent"] = userAgent
        cookieManager.getCookie(url)?.takeIf { it.isNotBlank() }?.let { outHeaders["Cookie"] = it }

        val okRequest = try {
            Request.Builder().url(url).headers(Headers.Builder().apply {
                outHeaders.forEach { (k, v) -> try { add(k, v) } catch (_: Exception) { } }
            }.build()).get().build()
        } catch (_: Exception) { return null }

        val response = try { client.newCall(okRequest).execute() } catch (_: Exception) { return null }

        val setCookies = response.headers("Set-Cookie")
        setCookies.forEach { cookieManager.setCookie(url, it) }
        if (setCookies.isNotEmpty()) cookieManager.flush()

        val respHeaders = LinkedHashMap<String, String>()
        for (name in response.headers.names()) {
            respHeaders[name] = response.headers(name).joinToString(if (name.equals("Set-Cookie", true)) "\n" else ", ")
        }
        val contentType = response.header("Content-Type")
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        val charsetName = contentType?.let { Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim('"', ' ') }
        val code = response.code
        val accept = outHeaders.entries.firstOrNull { it.key.equals("Accept", true) }?.value
        val isXhr = outHeaders.entries.any { it.key.equals("X-Requested-With", true) && it.value.equals("XMLHttpRequest", true) }
        val kind = LogKind.classify(url, mime, accept, isXhr).let {
            if (it == LogKind.OTHER && request.isForMainFrame) LogKind.DOC else it
        }

        fun baseLog(body: String?, size: Long) = NetworkLog(
            id = UUID.randomUUID().toString(),
            timestamp = start,
            method = "GET",
            url = url,
            requestHeaders = outHeaders,
            requestBody = null,
            responseCode = code,
            responseHeaders = respHeaders,
            responseBody = body,
            cookies = setCookies,
            mimeType = mime,
            isXHR = isXhr,
            duration = System.currentTimeMillis() - start,
            source = "deep",
            kind = kind,
            isMainFrame = request.isForMainFrame,
            pageUrl = pageUrl,
            responseSize = size
        )

        // Cloudflare & co. fingerprint OkHttp differently from Chrome: when a request gets challenged,
        // record it but let the WebView make the real request so the user can pass the challenge.
        val challenged = (code == 403 || code == 503 || code == 429) &&
            (response.header("cf-mitigated") != null || response.header("Server")?.contains("cloudflare", true) == true ||
                response.header("Server")?.contains("ddos-guard", true) == true)

        // Redirects cannot be handed back to the WebView: record them and let the WebView redo it.
        if (challenged || code in 300..399 || code < 100 || code > 599) {
            response.close()
            return Result(null, baseLog(if (challenged) "[bot challenge for OkHttp — the WebView made this request itself]" else null, -1))
        }

        val body = response.body ?: run {
            response.close()
            return Result(null, baseLog(null, -1))
        }

        val passHeaders = respHeaders.filterKeys { k ->
            !k.equals("Content-Encoding", true) && !k.equals("Content-Length", true) &&
                !k.equals("Transfer-Encoding", true) && !k.equals("Set-Cookie", true)
        }
        val reason = response.message.filter { it in ' '..'~' }.ifBlank { "OK" }
        val textual = isTextual(mime, url)
        val declaredLength = body.contentLength()

        if (!textual || declaredLength > MAX_BUFFER) {
            // Stream straight through without buffering (large or binary content)
            val wr = WebResourceResponse(mime ?: "application/octet-stream", charsetName, code, reason, passHeaders, body.byteStream())
            return Result(wr, baseLog("[${mime ?: "binary"} — ${if (declaredLength >= 0) "$declaredLength bytes" else "streamed"}, body not stored]", declaredLength))
        }

        val bytes = try { body.bytes() } catch (_: Exception) { response.close(); return null }
        val charset = try { charsetName?.let { Charset.forName(it) } ?: Charsets.UTF_8 } catch (_: Exception) { Charsets.UTF_8 }
        val text = String(bytes, charset)
        val stored = if (text.length > MAX_STORED) text.substring(0, MAX_STORED) + "\n…[TRUNCATED ${text.length - MAX_STORED} chars]" else text

        val wr = WebResourceResponse(mime ?: "text/plain", charset.name(), code, reason, passHeaders, ByteArrayInputStream(bytes))
        return Result(wr, baseLog(stored, bytes.size.toLong()))
    }

    private fun isTextual(mime: String?, url: String): Boolean {
        if (mime == null) return LogKind.classify(url) != LogKind.MEDIA
        return mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") ||
            mime.contains("javascript") || mime.contains("mpegurl") || mime.contains("x-www-form-urlencoded")
    }

    companion object {
        /** One connection pool shared by every browser tab. */
        private val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)          // WebResourceResponse cannot represent 3xx
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        private const val MAX_BUFFER = 6L * 1024 * 1024
        private const val MAX_STORED = 400_000
    }
}
