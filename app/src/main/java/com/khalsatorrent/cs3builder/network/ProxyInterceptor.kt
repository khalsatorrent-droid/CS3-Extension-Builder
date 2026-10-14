package com.khalsatorrent.cs3builder.network

import androidx.lifecycle.MutableLiveData
import com.khalsatorrent.cs3builder.model.NetworkLog
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class ProxyInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = mutableMapOf<String, String>()
        for (i in 0 until request.headers.size) {
            requestHeaders[request.headers.name(i)] = request.headers.value(i)
        }

        val requestBody: String? = try {
            request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                val raw = buffer.readUtf8()
                if (raw.length > 51200) raw.substring(0, 51200) + "\n[TRUNCATED]" else raw
            }
        } catch (e: Exception) {
            null
        }

        val response = chain.proceed(request)
        val duration = System.currentTimeMillis() - startTime

        val responseHeaders = mutableMapOf<String, String>()
        for (i in 0 until response.headers.size) {
            responseHeaders[response.headers.name(i)] = response.headers.value(i)
        }

        val cookies = response.headers.values("Set-Cookie")

        val mimeType = response.body?.contentType()?.let { "${it.type}/${it.subtype}" }

        val responseBodySource = response.body
        val responseBodyText: String?
        val newResponse: Response

        if (responseBodySource != null) {
            val bodyBytes = responseBodySource.bytes()
            responseBodyText = try {
                val raw = String(bodyBytes, Charsets.UTF_8)
                if (raw.length > 51200) raw.substring(0, 51200) + "\n[TRUNCATED]" else raw
            } catch (e: Exception) {
                "[Binary content, ${bodyBytes.size} bytes]"
            }
            newResponse = response.newBuilder()
                .body(okhttp3.ResponseBody.create(responseBodySource.contentType(), bodyBytes))
                .build()
        } else {
            responseBodyText = null
            newResponse = response
        }

        val isXHR = requestHeaders["X-Requested-With"]?.contains("XMLHttpRequest", true) == true ||
                requestHeaders["Accept"]?.contains("application/json", true) == true

        val log = NetworkLog(
            id = UUID.randomUUID().toString(),
            timestamp = startTime,
            method = request.method,
            url = request.url.toString(),
            requestHeaders = requestHeaders,
            requestBody = requestBody,
            responseCode = response.code,
            responseHeaders = responseHeaders,
            responseBody = responseBodyText,
            cookies = cookies,
            mimeType = mimeType,
            isXHR = isXHR,
            duration = duration
        )

        LogRepository.addLog(log)

        return newResponse
    }

    companion object LogRepository {
        private val _logList = CopyOnWriteArrayList<NetworkLog>()
        val logs = MutableLiveData<List<NetworkLog>>(emptyList())

        fun addLog(log: NetworkLog) {
            _logList.add(0, log)
            logs.postValue(_logList.toList())
        }

        fun clearLogs() {
            _logList.clear()
            logs.postValue(emptyList())
        }

        fun getSnapshot(): List<NetworkLog> = _logList.toList()
    }
}
