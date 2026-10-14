package com.khalsatorrent.cs3builder.codegen

import com.khalsatorrent.cs3builder.model.CS3Template
import com.khalsatorrent.cs3builder.model.DetectedPattern
import com.khalsatorrent.cs3builder.model.NetworkLog
import com.khalsatorrent.cs3builder.model.PatternType
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CS3CodeGenerator {

    private val STREAMING_EXTENSIONS = listOf(".m3u8", ".mp4", ".mkv", ".ts", ".webm", ".m4v")
    private val STREAMING_PATTERNS = listOf("manifest", "playlist", "stream", "hls", "dash", "video", "media")
    private val SEARCH_PATTERNS = listOf("search", "query", "q=", "keyword", "find", "lookup")
    private val JSON_MIME_TYPES = listOf("application/json", "text/json")
    private val HTML_MIME_TYPES = listOf("text/html", "application/xhtml")
    private val SKIP_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".gif", ".ico", ".svg", ".woff", ".woff2", ".css", ".js")
    private val SKIP_DOMAINS = listOf("google-analytics", "googletagmanager", "doubleclick", "facebook.com", "twitter.com",
        "googleapis.com/maps", "recaptcha", "cloudflare.com/cdn-cgi")

    fun generate(logs: List<NetworkLog>): CS3Template {
        val filteredLogs = logs.filter { log ->
            SKIP_EXTENSIONS.none { log.url.contains(it, ignoreCase = true) } &&
            SKIP_DOMAINS.none { log.url.contains(it, ignoreCase = true) }
        }

        val baseUrl = detectBaseUrl(filteredLogs)
        val providerName = deriveProviderName(baseUrl)
        val patterns = detectPatterns(filteredLogs)
        val commonHeaders = extractCommonHeaders(filteredLogs)
        val allCookies = extractAllCookies(logs)

        val searchPattern = patterns.firstOrNull { it.type == PatternType.SEARCH_ENDPOINT }
        val mediaUrls = patterns.filter { it.type == PatternType.MEDIA_URL || it.type == PatternType.STREAMING_URL }
            .map { it.url }.distinct()
        val apiEndpoints = patterns.filter { it.type == PatternType.API_ENDPOINT }
            .map { it.url }.distinct()

        val searchEndpoint = searchPattern?.url
        val searchQueryParam = searchPattern?.let { extractQueryParam(it.url) }

        val code = buildGeneratedCode(
            baseUrl = baseUrl,
            providerName = providerName,
            commonHeaders = commonHeaders,
            allCookies = allCookies,
            searchEndpoint = searchEndpoint,
            searchQueryParam = searchQueryParam,
            mediaUrls = mediaUrls,
            apiEndpoints = apiEndpoints,
            patterns = patterns,
            logs = filteredLogs
        )

        val summary = buildLogSummary(logs, filteredLogs, baseUrl, patterns, commonHeaders, allCookies)

        return CS3Template(
            baseUrl = baseUrl,
            providerName = providerName,
            detectedHeaders = commonHeaders,
            detectedCookies = allCookies,
            searchEndpoint = searchEndpoint,
            searchQueryParam = searchQueryParam,
            mediaUrls = mediaUrls,
            apiEndpoints = apiEndpoints,
            generatedCode = code,
            logSummary = summary
        )
    }

    private fun detectBaseUrl(logs: List<NetworkLog>): String {
        if (logs.isEmpty()) return "https://example.com"

        val originCounts = mutableMapOf<String, Int>()
        for (log in logs) {
            try {
                val uri = URI(log.url)
                val origin = "${uri.scheme}://${uri.host}"
                originCounts[origin] = (originCounts[origin] ?: 0) + 1
            } catch (e: Exception) {
                // skip malformed URLs
            }
        }
        return originCounts.maxByOrNull { it.value }?.key ?: "https://example.com"
    }

    private fun deriveProviderName(baseUrl: String): String {
        return try {
            val host = URI(baseUrl).host ?: return "GeneratedProvider"
            val parts = host.removePrefix("www.").split(".")
            val name = parts.firstOrNull() ?: "GeneratedProvider"
            name.replaceFirstChar { it.uppercase() }
                .replace("-", "")
                .replace("_", "")
                .let { if (it.first().isDigit()) "Provider$it" else it }
        } catch (e: Exception) {
            "GeneratedProvider"
        }
    }

    private fun detectPatterns(logs: List<NetworkLog>): List<DetectedPattern> {
        val patterns = mutableListOf<DetectedPattern>()

        for (log in logs) {
            val url = log.url.lowercase()

            // Streaming URLs
            if (STREAMING_EXTENSIONS.any { url.contains(it) } ||
                STREAMING_PATTERNS.any { url.contains(it) && (url.contains(".m3u8") || url.contains(".mp4") || url.contains("stream")) }) {
                patterns.add(DetectedPattern(
                    type = if (url.contains(".m3u8")) PatternType.STREAMING_URL else PatternType.MEDIA_URL,
                    url = log.url,
                    description = "Detected streaming/media URL"
                ))
                continue
            }

            // Search endpoints
            if (SEARCH_PATTERNS.any { url.contains(it) }) {
                patterns.add(DetectedPattern(
                    type = PatternType.SEARCH_ENDPOINT,
                    url = log.url,
                    description = "Detected search endpoint"
                ))
                continue
            }

            // JSON API endpoints
            val mime = log.mimeType?.lowercase() ?: ""
            if (JSON_MIME_TYPES.any { mime.contains(it) } || log.isXHR) {
                patterns.add(DetectedPattern(
                    type = PatternType.API_ENDPOINT,
                    url = log.url,
                    description = "JSON/API endpoint"
                ))
                continue
            }

            // HTML detail pages
            if (HTML_MIME_TYPES.any { mime.contains(it) } && log.responseCode == 200) {
                val body = log.responseBody?.lowercase() ?: ""
                if (body.contains("video") || body.contains("episode") || body.contains("season") ||
                    body.contains("watch") || body.contains("player")) {
                    patterns.add(DetectedPattern(
                        type = PatternType.DETAIL_PAGE,
                        url = log.url,
                        description = "Possible media detail page"
                    ))
                }
            }
        }

        return patterns
    }

    private fun extractCommonHeaders(logs: List<NetworkLog>): Map<String, String> {
        val headerFrequency = mutableMapOf<String, MutableMap<String, Int>>()
        for (log in logs) {
            for ((k, v) in log.requestHeaders) {
                val lower = k.lowercase()
                if (lower in listOf("user-agent", "accept", "accept-language", "referer", "origin",
                        "x-requested-with", "authorization", "content-type")) {
                    val valueMap = headerFrequency.getOrPut(k) { mutableMapOf() }
                    valueMap[v] = (valueMap[v] ?: 0) + 1
                }
            }
        }

        val result = mutableMapOf<String, String>()
        for ((header, valueMap) in headerFrequency) {
            val mostCommon = valueMap.maxByOrNull { it.value }?.key
            if (mostCommon != null) result[header] = mostCommon
        }
        return result
    }

    private fun extractAllCookies(logs: List<NetworkLog>): List<String> {
        return logs.flatMap { it.cookies }.distinct()
    }

    private fun extractQueryParam(url: String): String? {
        return try {
            val query = URI(url).query ?: return null
            val params = query.split("&")
            SEARCH_PATTERNS.forEach { pattern ->
                params.forEach { param ->
                    if (param.lowercase().startsWith(pattern)) {
                        return param.substringBefore("=")
                    }
                }
            }
            params.firstOrNull()?.substringBefore("=")
        } catch (e: Exception) {
            null
        }
    }

    private fun buildGeneratedCode(
        baseUrl: String,
        providerName: String,
        commonHeaders: Map<String, String>,
        allCookies: List<String>,
        searchEndpoint: String?,
        searchQueryParam: String?,
        mediaUrls: List<String>,
        apiEndpoints: List<String>,
        patterns: List<DetectedPattern>,
        logs: List<NetworkLog>
    ): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        val headersComment = if (commonHeaders.isNotEmpty()) {
            commonHeaders.entries.joinToString("\n") { (k, v) -> "    //   $k: $v" }
        } else {
            "    //   (no common headers detected)"
        }

        val cookiesComment = if (allCookies.isNotEmpty()) {
            allCookies.take(10).joinToString("\n") { "    //   $it" }
        } else {
            "    //   (no cookies detected)"
        }

        val headersMapEntries = commonHeaders.entries
            .filter { it.key.lowercase() !in listOf("host", "content-length") }
            .take(8)
            .joinToString(",\n        ") { (k, v) -> "\"$k\" to \"${v.replace("\"", "\\\"")}\"" }

        val searchImpl = if (searchEndpoint != null) {
            val param = searchQueryParam ?: "q"
            val cleanEndpoint = searchEndpoint.substringBefore("?")
            """        val url = "$cleanEndpoint?$param=\${query.encodeURL()}"
        val doc = app.get(url, headers = defaultHeaders).document
        return doc.select("a.result-item, .search-result, article").mapNotNull { el ->
            val href = el.attr("abs:href")
            val title = el.select("h2, h3, .title").text()
            if (href.isBlank() || title.isBlank()) return@mapNotNull null
            newAnimeSearchResponse(title, href, TvType.Movie)
        }"""
        } else {
            "        // TODO: No search endpoint detected — implement manually\n        return listOf()"
        }

        val detailImpl = run {
            val detailPage = patterns.firstOrNull { it.type == PatternType.DETAIL_PAGE }
            if (detailPage != null) {
                """        val doc = app.get(url, headers = defaultHeaders).document
        val title = doc.select("h1, .title, .movie-title").firstOrNull()?.text() ?: "Unknown"
        val posterUrl = doc.select("img.poster, .thumbnail img, .cover img").firstOrNull()?.attr("abs:src")
        val description = doc.select(".description, .synopsis, p.overview").firstOrNull()?.text()
        // TODO: Adjust selectors based on actual page structure
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = description
        }"""
            } else {
                "        val doc = app.get(url, headers = defaultHeaders).document\n        val title = doc.select(\"h1\").firstOrNull()?.text() ?: \"Unknown\"\n        // TODO: Implement load() based on observed page structure\n        return newMovieLoadResponse(title, url, TvType.Movie, url)"
            }
        }

        val loadLinksImpl = if (mediaUrls.isNotEmpty()) {
            val detected = mediaUrls.take(5).joinToString("\n") { "            //   $it" }
            """        // Detected media URLs from captured traffic:
$detected
        // TODO: Parse page at `data` to extract actual stream URL dynamically
        val doc = app.get(data, headers = defaultHeaders).document
        val streamUrl = doc.select("source[src], video[src]").firstOrNull()?.attr("abs:src")
            ?: extractFromScripts(doc)
        if (streamUrl != null) {
            callback(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    referer = data,
                    quality = Qualities.Unknown.value,
                    isM3u8 = streamUrl.contains(".m3u8")
                )
            )
            return true
        }
        return false"""
        } else {
            "        // TODO: No media URLs detected — browse to a media page first\n        return false"
        }

        val cookieHeader = if (allCookies.isNotEmpty()) {
            val cookieStr = allCookies.take(5)
                .map { it.substringBefore(";").trim() }
                .joinToString("; ")
            "\n        \"Cookie\" to \"$cookieStr\","
        } else ""

        val apiEndpointsComment = if (apiEndpoints.isNotEmpty()) {
            "\n    // Detected API endpoints:\n" + apiEndpoints.take(10).joinToString("\n") { "    //   $it" }
        } else ""

        return """// Auto-generated by CS3 Extension Builder on $dateStr
// Base URL: $baseUrl
// NOTE: This is a starting point. Review and fix before using.
//       The generated selectors and URLs may need manual adjustment.

package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ${providerName}Provider : MainAPI() {
    override var mainUrl = "$baseUrl"
    override var name = "$providerName"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true
    override val hasSearch = true

    // Auto-detected headers from captured traffic:
$headersComment

    // Detected cookies:
$cookiesComment
$apiEndpointsComment

    private val defaultHeaders = mapOf(
        $headersMapEntries$cookieHeader
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(mainUrl, headers = defaultHeaders).document
        // TODO: Adjust selectors for actual homepage structure
        val items = doc.select(".movie-item, .content-item, article").mapNotNull { el ->
            val href = el.attr("abs:href").takeIf { it.isNotBlank() }
                ?: el.select("a").firstOrNull()?.attr("abs:href") ?: return@mapNotNull null
            val title = el.select("h2, h3, .title").text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = el.select("img").firstOrNull()?.attr("abs:src")
            newAnimeSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
$searchImpl
    }

    override suspend fun load(url: String): LoadResponse? {
$detailImpl
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
$loadLinksImpl
    }

    private fun extractFromScripts(doc: Document): String? {
        // Attempt to extract stream URL embedded in JavaScript
        val scripts = doc.select("script").map { it.html() }
        val patterns = listOf(
            Regex(""""(https?://[^"]+\.m3u8[^"]*)""""),
            Regex(""""(https?://[^"]+\.mp4[^"]*)""""),
            Regex("""'(https?://[^']+\.m3u8[^']*)'"""),
            Regex("""file:\s*["'](https?://[^"']+)["']"""),
            Regex("""source:\s*["'](https?://[^"']+)["']""")
        )
        for (script in scripts) {
            for (pattern in patterns) {
                val match = pattern.find(script)
                if (match != null) return match.groupValues[1]
            }
        }
        return null
    }
}
"""
    }

    private fun buildLogSummary(
        allLogs: List<NetworkLog>,
        filteredLogs: List<NetworkLog>,
        baseUrl: String,
        patterns: List<DetectedPattern>,
        commonHeaders: Map<String, String>,
        allCookies: List<String>
    ): String {
        val uniqueDomains = allLogs.mapNotNull { log ->
            try { URI(log.url).host } catch (e: Exception) { null }
        }.distinct()

        val methodCounts = allLogs.groupBy { it.method }.mapValues { it.value.size }
        val responseCodes = allLogs.filter { it.responseCode > 0 }.groupBy { it.responseCode / 100 * 100 }
            .mapValues { it.value.size }

        val sb = StringBuilder()
        sb.appendLine("=== CS3 Extension Builder — Capture Summary ===")
        sb.appendLine()
        sb.appendLine("Total requests captured : ${allLogs.size}")
        sb.appendLine("After filtering         : ${filteredLogs.size}")
        sb.appendLine("Unique domains          : ${uniqueDomains.size}")
        sb.appendLine("Base URL detected       : $baseUrl")
        sb.appendLine()
        sb.appendLine("--- Request Methods ---")
        methodCounts.entries.sortedByDescending { it.value }.forEach { (method, count) ->
            sb.appendLine("  $method: $count")
        }
        sb.appendLine()
        sb.appendLine("--- Response Codes ---")
        responseCodes.entries.sortedBy { it.key }.forEach { (base, count) ->
            sb.appendLine("  ${base}xx: $count")
        }
        sb.appendLine()

        val searchPatterns = patterns.filter { it.type == PatternType.SEARCH_ENDPOINT }
        val streamingPatterns = patterns.filter { it.type == PatternType.STREAMING_URL }
        val mediaPatterns = patterns.filter { it.type == PatternType.MEDIA_URL }
        val apiPatterns = patterns.filter { it.type == PatternType.API_ENDPOINT }
        val detailPatterns = patterns.filter { it.type == PatternType.DETAIL_PAGE }

        sb.appendLine("--- Detected Patterns ---")
        sb.appendLine("  Search endpoints  : ${searchPatterns.size}")
        searchPatterns.take(3).forEach { sb.appendLine("    → ${it.url.take(100)}") }

        sb.appendLine("  Streaming URLs    : ${streamingPatterns.size}")
        streamingPatterns.take(3).forEach { sb.appendLine("    → ${it.url.take(100)}") }

        sb.appendLine("  Media URLs        : ${mediaPatterns.size}")
        mediaPatterns.take(3).forEach { sb.appendLine("    → ${it.url.take(100)}") }

        sb.appendLine("  API endpoints     : ${apiPatterns.size}")
        apiPatterns.take(3).forEach { sb.appendLine("    → ${it.url.take(100)}") }

        sb.appendLine("  Detail pages      : ${detailPatterns.size}")
        detailPatterns.take(3).forEach { sb.appendLine("    → ${it.url.take(100)}") }

        sb.appendLine()
        sb.appendLine("--- Detected Common Headers ---")
        if (commonHeaders.isEmpty()) {
            sb.appendLine("  (none detected)")
        } else {
            commonHeaders.forEach { (k, v) -> sb.appendLine("  $k: ${v.take(80)}") }
        }

        sb.appendLine()
        sb.appendLine("--- Detected Cookies ---")
        if (allCookies.isEmpty()) {
            sb.appendLine("  (none detected)")
        } else {
            allCookies.take(20).forEach { sb.appendLine("  ${it.take(100)}") }
            if (allCookies.size > 20) sb.appendLine("  ... and ${allCookies.size - 20} more")
        }

        sb.appendLine()
        sb.appendLine("--- All Unique Domains ---")
        uniqueDomains.sorted().forEach { sb.appendLine("  $it") }

        return sb.toString()
    }
}
