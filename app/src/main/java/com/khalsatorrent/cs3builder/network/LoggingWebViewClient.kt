package com.khalsatorrent.cs3builder.network

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.khalsatorrent.cs3builder.model.LogKind
import com.khalsatorrent.cs3builder.model.NetworkLog
import java.io.ByteArrayInputStream
import java.util.UUID

/**
 * Captures everything the WebView loads:
 *  - shouldInterceptRequest sees every request (and optionally runs it through [DeepCapture])
 *  - the injected JS hook ([CaptureScripts.HOOK]) reports fetch/XHR bodies via [JSBridge]
 *  - after each page load a rendered-DOM snapshot can be stored for selector work
 */
class LoggingWebViewClient(
    packageName: String,
    private val settings: Settings,
    private val callbacks: Callbacks
) : WebViewClient() {

    interface Settings {
        val deepCapture: Boolean
        val adBlock: Boolean
        val domSnapshots: Boolean
        val userAgent: String
        /** True when the hook is already injected at document start for every frame. */
        val documentStartHook: Boolean
    }

    interface Callbacks {
        fun onPageStarted(url: String)
        fun onPageFinished(url: String)
    }

    private val gson = Gson()
    private val deep = DeepCapture(packageName)
    @Volatile private var currentPage: String? = null

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        request ?: return null
        val url = request.url.toString()

        if (settings.adBlock && AdBlocker.isAd(url)) {
            return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        if (LogRepository.isPaused) return null
        val scheme = request.url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null

        if (settings.deepCapture && deep.canHandle(request)) {
            val result = try { deep.fetch(request, settings.userAgent, currentPage) } catch (_: Throwable) { null }
            if (result != null) {
                LogRepository.addLog(result.log)
                if (result.response != null) return result.response
                return null
            }
        }

        val headers = (request.requestHeaders ?: emptyMap()).toMutableMap()
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        val accept = headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value
        val isXhr = headers.entries.any { it.key.equals("X-Requested-With", true) && it.value.equals("XMLHttpRequest", true) }
        var kind = LogKind.classify(url, null, accept, isXhr)
        if (kind == LogKind.OTHER && request.isForMainFrame) kind = LogKind.DOC

        LogRepository.addLog(
            NetworkLog(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                method = request.method ?: "GET",
                url = url,
                requestHeaders = headers,
                requestBody = null,
                responseCode = 0,
                responseHeaders = emptyMap(),
                responseBody = null,
                cookies = emptyList(),
                mimeType = null,
                isXHR = isXhr,
                duration = 0L,
                source = "webview",
                kind = kind,
                isMainFrame = request.isForMainFrame,
                pageUrl = currentPage
            )
        )
        return null
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return false
        val scheme = uri.scheme?.lowercase()
        // intent://, market://, tg:// etc. would only show an error page — swallow them
        if (scheme != "http" && scheme != "https") return true
        if (settings.adBlock && AdBlocker.isAd(uri.toString())) return true
        // Ad networks love redirecting the main frame on the first tap
        if (settings.adBlock && request.isForMainFrame && !request.hasGesture() && isCrossSiteRedirect(view, uri)) return true
        return false
    }

    private fun isCrossSiteRedirect(view: WebView?, target: Uri): Boolean {
        val current = view?.url?.let { Uri.parse(it).host } ?: return false
        val host = target.host ?: return false
        return registrable(current) != registrable(host) && AdBlocker.looksLikeAdRedirect(target.toString())
    }

    private fun registrable(host: String) = host.split('.').takeLast(2).joinToString(".")

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        url ?: return
        currentPage = url
        callbacks.onPageStarted(url)
        if (!settings.documentStartHook) view?.evaluateJavascript(CaptureScripts.HOOK, null)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        url ?: return
        callbacks.onPageFinished(url)
        view ?: return
        // Harmless when already injected (the script guards itself)
        view.evaluateJavascript(CaptureScripts.HOOK, null)
        if (settings.adBlock) view.evaluateJavascript(CaptureScripts.AD_BLOCK, null)
        if (settings.domSnapshots && !LogRepository.isPaused && url.startsWith("http")) snapshotDom(view, url)
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        super.onReceivedHttpError(view, request, errorResponse)
        val url = request?.url?.toString() ?: return
        val code = errorResponse?.statusCode ?: return
        LogRepository.updateStatus(url, code, errorResponse.responseHeaders ?: emptyMap())
    }

    fun snapshotDom(view: WebView, url: String = view.url ?: "") {
        view.evaluateJavascript(CaptureScripts.DOM_SNAPSHOT) { raw ->
            val html = try { gson.fromJson(raw, String::class.java) } catch (_: Exception) { null }
            if (html.isNullOrBlank()) return@evaluateJavascript
            LogRepository.upsertDom(
                NetworkLog(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    method = "DOM",
                    url = url,
                    requestHeaders = emptyMap(),
                    requestBody = null,
                    responseCode = 0,
                    responseHeaders = emptyMap(),
                    responseBody = html,
                    cookies = CookieManager.getInstance().getCookie(url)?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                    mimeType = "text/html",
                    isXHR = false,
                    duration = 0L,
                    source = "dom",
                    kind = LogKind.DOM,
                    isMainFrame = true,
                    pageUrl = url,
                    responseSize = html.length.toLong()
                )
            )
        }
    }

    /** Receives reports from [CaptureScripts.HOOK]. Exposed to every frame as `CS3Bridge`. */
    inner class JSBridge {
        @JavascriptInterface
        fun onRequestComplete(json: String) {
            try {
                val data: Map<String, Any?> = gson.fromJson(json, object : TypeToken<Map<String, Any?>>() {}.type) ?: return
                val url = data["url"] as? String ?: return
                if (!url.startsWith("http") && !url.startsWith("ws")) return
                val type = data["type"] as? String ?: "xhr"
                val method = ((data["method"] as? String) ?: "GET").uppercase()
                val reqHeaders = (data["headers"] as? Map<*, *>)?.entries
                    ?.associate { (k, v) -> k.toString() to v.toString() } ?: emptyMap()
                val respHeaders = (data["responseHeaders"] as? Map<*, *>)?.entries
                    ?.associate { (k, v) -> k.toString() to v.toString() } ?: emptyMap()
                val contentType = (data["contentType"] as? String)?.takeIf { it.isNotBlank() }
                val mime = contentType?.substringBefore(";")?.trim()
                val kind = when (type) {
                    "websocket" -> LogKind.SOCKET
                    "media" -> LogKind.classify(url).let { if (it == LogKind.OTHER) LogKind.MEDIA else it }
                    else -> LogKind.classify(url, mime, null, true).let {
                        if (it == LogKind.OTHER || it == LogKind.DOC) LogKind.XHR else it
                    }
                }
                val body = data["responseBody"] as? String
                LogRepository.addLog(
                    NetworkLog(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        method = method,
                        url = url,
                        requestHeaders = reqHeaders,
                        requestBody = data["body"] as? String,
                        responseCode = (data["responseCode"] as? Number)?.toInt() ?: 0,
                        responseHeaders = respHeaders,
                        responseBody = body,
                        cookies = emptyList(),
                        mimeType = mime,
                        isXHR = type == "fetch" || type == "xhr",
                        duration = (data["duration"] as? Number)?.toLong() ?: 0L,
                        source = "js",
                        kind = kind,
                        isMainFrame = false,
                        pageUrl = data["page"] as? String,
                        responseSize = (data["size"] as? Number)?.toLong() ?: (body?.length?.toLong() ?: -1L)
                    )
                )
            } catch (_: Exception) { }
        }
    }
}
