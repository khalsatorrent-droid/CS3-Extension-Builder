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
    private val onPageFinishedCallback: ((url: String) -> Unit)? = null,
    private val shouldBlockUrl: ((url: String) -> Boolean)? = null
) : WebViewClient() {

    private val gson = Gson()

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        request?.let {
            val url = it.url.toString()

            // Block ad/redirect domains if blocker is active
            if (shouldBlockUrl?.invoke(url) == true) {
                return WebResourceResponse("text/plain", "utf-8", null)
            }

            val headers = it.requestHeaders?.toMutableMap() ?: mutableMapOf()
            val isXHR = headers["X-Requested-With"]?.contains("XMLHttpRequest", true) == true
            val log = NetworkLog(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                method = it.method ?: "GET",
                url = url,
                requestHeaders = headers,
                requestBody = null,
                responseCode = 0,         // not available from WebView intercept
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
        // language=JavaScript
        val js = """
(function() {
    if (window.__cs3Injected) return;
    window.__cs3Injected = true;

    /* ---- intercept fetch ---- */
    var _fetch = window.fetch;
    window.fetch = function(input, init) {
        var url = typeof input === 'string' ? input : (input && input.url) || '';
        var method = (init && init.method) ? init.method.toUpperCase() : 'GET';
        var headers = {};
        if (init && init.headers) {
            try {
                if (init.headers instanceof Headers) {
                    init.headers.forEach(function(v, k) { headers[k] = v; });
                } else {
                    Object.assign(headers, init.headers);
                }
            } catch(e) {}
        }
        var body = (init && init.body) ? String(init.body).substring(0, 8000) : null;

        var p = _fetch.apply(this, arguments);
        p.then(function(res) {
            var resClone = res.clone();
            resClone.text().then(function(text) {
                try {
                    window.CS3Bridge.onRequestComplete(JSON.stringify({
                        url: url, method: method, headers: headers, body: body,
                        type: 'fetch',
                        responseCode: res.status,
                        responseBody: text.substring(0, 8000),
                        contentType: res.headers.get('content-type') || ''
                    }));
                } catch(e) {}
            }).catch(function() {
                try {
                    window.CS3Bridge.onRequestComplete(JSON.stringify({
                        url: url, method: method, headers: headers, body: body,
                        type: 'fetch', responseCode: res.status, responseBody: null, contentType: ''
                    }));
                } catch(e) {}
            });
        }).catch(function() {});
        return p;
    };

    /* ---- intercept XHR ---- */
    var _open  = XMLHttpRequest.prototype.open;
    var _send  = XMLHttpRequest.prototype.send;
    var _setHdr = XMLHttpRequest.prototype.setRequestHeader;
    XMLHttpRequest.prototype.open = function(m, u) {
        this._cs3m = m.toUpperCase(); this._cs3u = u; this._cs3h = {};
        return _open.apply(this, arguments);
    };
    XMLHttpRequest.prototype.setRequestHeader = function(n, v) {
        if (!this._cs3h) this._cs3h = {};
        this._cs3h[n] = v;
        return _setHdr.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function(data) {
        var self = this;
        this.addEventListener('load', function() {
            try {
                window.CS3Bridge.onRequestComplete(JSON.stringify({
                    url: self._cs3u, method: self._cs3m || 'GET',
                    headers: self._cs3h || {}, body: data ? String(data).substring(0, 8000) : null,
                    type: 'xhr',
                    responseCode: self.status,
                    responseBody: (self.responseText || '').substring(0, 8000),
                    contentType: self.getResponseHeader('content-type') || ''
                }));
            } catch(e) {}
        });
        return _send.apply(this, arguments);
    };
})();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class JSBridge {
        @JavascriptInterface
        fun onRequestComplete(json: String) {
            try {
                @Suppress("UNCHECKED_CAST")
                val data = gson.fromJson<Map<String, Any>>(
                    json, object : TypeToken<Map<String, Any>>() {}.type
                )
                val url = data["url"] as? String ?: return
                val method = ((data["method"] as? String) ?: "GET").uppercase()
                @Suppress("UNCHECKED_CAST")
                val hdrs = (data["headers"] as? Map<String, String>) ?: emptyMap()
                val body = data["body"] as? String
                val responseCode = (data["responseCode"] as? Number)?.toInt() ?: 0
                val responseBody = data["responseBody"] as? String
                val contentType = data["contentType"] as? String

                val log = NetworkLog(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    method = method,
                    url = url,
                    requestHeaders = hdrs,
                    requestBody = body,
                    responseCode = responseCode,
                    responseHeaders = if (contentType != null) mapOf("Content-Type" to contentType) else emptyMap(),
                    responseBody = responseBody,
                    cookies = emptyList(),
                    mimeType = contentType?.substringBefore(";")?.trim(),
                    isXHR = true,
                    duration = 0L
                )
                ProxyInterceptor.addLog(log)
            } catch (_: Exception) {}
        }
    }
}
