package com.khalsatorrent.cs3builder.export

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.khalsatorrent.cs3builder.model.LogKind
import com.khalsatorrent.cs3builder.model.NetworkLog
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Imports traffic captured elsewhere — HAR files from Reqable, HttpCanary, PCAPdroid (with TLS
 * decryption), Chrome DevTools, Charles, mitmproxy… — or a session JSON exported by this app.
 */
object LogImporter {

    class Result(val logs: List<NetworkLog>, val format: String)

    fun import(text: String, source: String = "import"): Result {
        val root = try { JsonParser.parseString(text.trim().removePrefix("﻿")) } catch (e: Exception) {
            throw IllegalArgumentException("Not a HAR or JSON file (${e.message?.take(80)})")
        }
        if (root.isJsonObject && root.asJsonObject.has("log")) return Result(fromHar(root.asJsonObject, source), "HAR")
        if (root.isJsonArray) {
            val type = object : TypeToken<List<NetworkLog>>() {}.type
            val list: List<NetworkLog> = Gson().fromJson(root, type) ?: emptyList()
            @Suppress("SENSELESS_COMPARISON")
            val clean = list.filter { it.url != null && it.method != null }.map { l ->
                l.copy(
                    id = l.id ?: UUID.randomUUID().toString(),
                    requestHeaders = l.requestHeaders ?: emptyMap(),
                    responseHeaders = l.responseHeaders ?: emptyMap(),
                    cookies = l.cookies ?: emptyList(),
                    source = l.source ?: source,
                    kind = l.kind ?: LogKind.classify(l.url, l.mimeType)
                )
            }
            return Result(clean, "session")
        }
        throw IllegalArgumentException("Unrecognised file — expected a .har export or a session .json")
    }

    private fun fromHar(root: JsonObject, source: String): List<NetworkLog> {
        val entries = root.getAsJsonObject("log")?.getAsJsonArray("entries") ?: return emptyList()
        val out = ArrayList<NetworkLog>(entries.size())
        var fallbackTime = System.currentTimeMillis() - entries.size() * 10L
        for (e in entries) {
            val o = e.asObj() ?: continue
            val req = o.getAsJsonObject("request") ?: continue
            val resp = o.getAsJsonObject("response")
            val url = req.str("url") ?: continue
            val method = (req.str("method") ?: "GET").uppercase()
            val reqHeaders = headers(req)
            val respHeaders = resp?.let { headers(it) } ?: emptyMap()
            val content = resp?.getAsJsonObject("content")
            val mime = (content?.str("mimeType") ?: respHeaders.ci("content-type"))?.substringBefore(';')?.trim()?.ifBlank { null }
            var body = content?.str("text")
            if (body != null && content?.str("encoding").equals("base64", true)) {
                body = if (isTextual(mime)) try { String(Base64.getDecoder().decode(body.trim()), Charsets.UTF_8) } catch (_: Exception) { null }
                else "[binary ${mime ?: "content"}, base64 not imported]"
            }
            if (body != null && body.length > 400_000) body = body.substring(0, 400_000) + "\n…[TRUNCATED]"
            val reqBody = req.getAsJsonObject("postData")?.let { pd ->
                pd.str("text") ?: pd.getAsJsonArray("params")?.joinToString("&") { p ->
                    val po = p.asJsonObject; "${po.str("name")}=${po.str("value") ?: ""}"
                }
            }
            val cookieHeader = reqHeaders.ci("cookie")
            val setCookies = respHeaders.entries.filter { it.key.equals("set-cookie", true) }.flatMap { it.value.split('\n') }
            val time = parseTime(o.str("startedDateTime")) ?: fallbackTime.also { fallbackTime += 10 }
            val xhr = reqHeaders.ci("x-requested-with")?.equals("XMLHttpRequest", true) == true
            var kind = LogKind.classify(url, mime, reqHeaders.ci("accept"), xhr)
            if (kind == LogKind.OTHER && mime == null && method != "GET") kind = LogKind.XHR
            out += NetworkLog(
                id = UUID.randomUUID().toString(),
                timestamp = time,
                method = method,
                url = url,
                requestHeaders = if (cookieHeader != null || reqHeaders.isNotEmpty()) reqHeaders else emptyMap(),
                requestBody = reqBody,
                responseCode = resp?.get("status")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                responseHeaders = respHeaders,
                responseBody = body,
                cookies = setCookies,
                mimeType = mime,
                isXHR = xhr || kind == LogKind.XHR,
                duration = o.get("time")?.takeIf { it.isJsonPrimitive }?.asDouble?.toLong() ?: 0L,
                source = source,
                kind = kind,
                isMainFrame = kind == LogKind.DOC && reqHeaders.ci("sec-fetch-dest")?.equals("document", true) == true,
                responseSize = content?.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: -1L
            )
        }
        return out
    }

    private fun headers(o: JsonObject): Map<String, String> {
        val arr = o.getAsJsonArray("headers") ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (h in arr) {
            val ho = h.asObj() ?: continue
            val name = ho.str("name") ?: continue
            if (name.startsWith(":")) continue // HTTP/2 pseudo headers
            val value = ho.str("value") ?: ""
            val existing = map.keys.firstOrNull { it.equals(name, true) }
            if (existing != null) map[existing] = map[existing] + (if (name.equals("set-cookie", true)) "\n" else ", ") + value
            else map[name] = value
        }
        return map
    }

    private fun Map<String, String>.ci(name: String) = entries.firstOrNull { it.key.equals(name, true) }?.value

    private fun JsonElement.asObj(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString

    private fun isTextual(mime: String?): Boolean {
        val m = mime?.lowercase() ?: return true
        return m.startsWith("text/") || m.contains("json") || m.contains("xml") || m.contains("javascript") || m.contains("mpegurl") || m.contains("urlencoded")
    }

    private fun parseTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
        val trimmed = s.replace(Regex("(\\.\\d{3})\\d+"), "$1")
        for (p in patterns) {
            try {
                return SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(trimmed)?.time
            } catch (_: Exception) { }
        }
        return null
    }
}
