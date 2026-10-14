package com.khalsatorrent.cs3builder.network

import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.khalsatorrent.cs3builder.model.NetworkLog
import java.util.UUID

class LoggingWebViewClient(
    private val onPageStartedCallback: ((url: String) -> Unit)? = null,
    private val onPageFinishedCallback: ((url: String) -> Unit)? = null
) : WebViewClient() {

    private val gson = Gson()

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        request?.let {
            val headers = mutableMapOf<String, String>()
            it.requestHeaders?.forEach { (k, v) -> headers[k] = v }

            val isXHR = headers["X-Requested-With"]?.contains("XMLHttpRequest", true) == true

            val log = NetworkLog(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                method = it.method ?: "GET",
                url = it.url.toString(),
                requestHeaders = headers,
                requestBody = null,
                responseCode = 0,
                responseHeaders = emptyMap(),
                responseBody = null,
                cookies = emptyList(),
                mimeType = null,
                isXHR = isXHR,
                duration = 0L
            )
            ProxyInterceptor.addLog(log)
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        url?.let { onPageStartedCallback?.invoke(it) }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        url?.let { onPageFinishedCallback?.invoke(it) }
        view?.let { injectJsInterceptor(it) }
    }

    private fun injectJsInterceptor(webView: WebView) {
        val js = """
(function() {
    if (window.__cs3BuilderInjected) return;
    window.__cs3BuilderInjected = true;

    var origFetch = window.fetch;
    window.fetch = function(input, init) {
        var url = (typeof input === 'string') ? input : input.url;
        var method = (init && init.method) ? init.method : 'GET';
        var headers = {};
        if (init && init.headers) {
            if (init.headers instanceof Headers) {
                init.headers.forEach(function(v, k) { headers[k] = v; });
            } else {
                headers = init.headers;
            }
        }
        var body = (init && init.body) ? String(init.body).substring(0, 10000) : null;
        try {
            window.CS3Bridge.onRequest(JSON.stringify({
                url: url,
                method: method,
                headers: headers,
                body: body,
                type: 'fetch'
            }));
        } catch(e) {}
        return origFetch.apply(this, arguments);
    };

    var origXHROpen = XMLHttpRequest.prototype.open;
    var origXHRSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function(method, url) {
        this.__cs3Method = method;
        this.__cs3Url = url;
        this.__cs3Headers = {};
        return origXHROpen.apply(this, arguments);
    };
    var origSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
    XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
        if (!this.__cs3Headers) this.__cs3Headers = {};
        this.__cs3Headers[name] = value;
        return origSetRequestHeader.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function(body) {
        try {
            window.CS3Bridge.onRequest(JSON.stringify({
                url: this.__cs3Url,
                method: this.__cs3Method || 'GET',
                headers: this.__cs3Headers || {},
                body: body ? String(body).substring(0, 10000) : null,
                type: 'xhr'
            }));
        } catch(e) {}
        return origXHRSend.apply(this, arguments);
    };
})();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    inner class JSBridge {
        @JavascriptInterface
        fun onRequest(json: String) {
            try {
                val type = object : TypeToken<Map<String, Any>>() {}.type
                val data: Map<String, Any> = gson.fromJson(json, type)

                val url = data["url"] as? String ?: return
                val method = (data["method"] as? String ?: "GET").uppercase()

                @Suppress("UNCHECKED_CAST")
                val headersRaw = data["headers"] as? Map<String, String> ?: emptyMap()
                val body = data["body"] as? String

                val log = NetworkLog(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    method = method,
                    url = url,
                    requestHeaders = headersRaw,
                    requestBody = body,
                    responseCode = 0,
                    responseHeaders = emptyMap(),
                    responseBody = null,
                    cookies = emptyList(),
                    mimeType = null,
                    isXHR = true,
                    duration = 0L
                )
                ProxyInterceptor.addLog(log)
            } catch (e: Exception) {
                // Silently ignore malformed JS bridge calls
            }
        }
    }
}
