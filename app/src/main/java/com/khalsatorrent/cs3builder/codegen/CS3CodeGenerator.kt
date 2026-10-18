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
    private val SEARCH_PATTERNS = listOf("search", "query", "q=", "keyword", "find", "suggest", "autocomplete")
    private val EPISODE_PATTERNS = listOf("episode", "ep=", "/ep/", "watch", "player", "embed", "stream")
    private val JSON_MIME = listOf("application/json", "text/json")
    private val SKIP_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".gif", ".ico", ".svg",
        ".woff", ".woff2", ".css", ".js", ".webp", ".map")
    private val SKIP_DOMAINS = listOf("google-analytics", "googletagmanager", "doubleclick",
        "facebook.com", "twitter.com", "googleapis.com/maps", "recaptcha", "cloudflare.com/cdn-cgi",
        "gstatic.com", "googleapis.com/ajax", "disqus.com", "hotjar.com", "clarity.ms")

    fun generate(logs: List<NetworkLog>): CS3Template {
        val filtered = logs.filter { log ->
            SKIP_EXTENSIONS.none { log.url.substringBefore("?").endsWith(it, ignoreCase = true) } &&
            SKIP_DOMAINS.none { log.url.contains(it, ignoreCase = true) }
        }

        val baseUrl = detectBaseUrl(filtered)
        val providerName = deriveProviderName(baseUrl)
        val patterns = detectPatterns(filtered)
        val commonHeaders = extractCommonHeaders(filtered, baseUrl)
        val allCookies = extractAllCookies(logs, baseUrl)

        val searchPattern = patterns.firstOrNull { it.type == PatternType.SEARCH_ENDPOINT }
        val episodeApiPattern = patterns.firstOrNull { it.type == PatternType.EPISODE_API }
        val streamingUrls = patterns.filter { it.type == PatternType.STREAMING_URL }.map { it.url }.distinct()
        val mediaUrls = patterns.filter { it.type == PatternType.MEDIA_URL }.map { it.url }.distinct()
        val apiEndpoints = patterns.filter { it.type == PatternType.API_ENDPOINT }.map { it.url }.distinct()

        val code = buildGeneratedCode(
            baseUrl = baseUrl,
            providerName = providerName,
            commonHeaders = commonHeaders,
            allCookies = allCookies,
            searchPattern = searchPattern,
            episodeApiPattern = episodeApiPattern,
            streamingUrls = streamingUrls,
            mediaUrls = mediaUrls,
            apiEndpoints = apiEndpoints,
            patterns = patterns,
            logs = filtered
        )

        val summary = buildLogSummary(logs, filtered, baseUrl, patterns, commonHeaders, allCookies)

        return CS3Template(
            baseUrl = baseUrl,
            providerName = providerName,
            detectedHeaders = commonHeaders,
            detectedCookies = allCookies,
            searchEndpoint = searchPattern?.url,
            searchQueryParam = searchPattern?.let { extractQueryParam(it.url) },
            mediaUrls = (streamingUrls + mediaUrls).distinct(),
            apiEndpoints = apiEndpoints,
            generatedCode = code,
            logSummary = summary
        )
    }

    private fun detectBaseUrl(logs: List<NetworkLog>): String {
        if (logs.isEmpty()) return "https://example.com"
        val counts = mutableMapOf<String, Int>()
        for (log in logs) {
            try {
                val uri = URI(log.url)
                val origin = "${uri.scheme}://${uri.host}"
                counts[origin] = (counts[origin] ?: 0) + 1
            } catch (_: Exception) {}
        }
        return counts.maxByOrNull { it.value }?.key ?: "https://example.com"
    }

    private fun deriveProviderName(baseUrl: String): String {
        return try {
            val host = URI(baseUrl).host ?: return "GeneratedProvider"
            val parts = host.removePrefix("www.").split(".")
            val name = parts.firstOrNull() ?: "GeneratedProvider"
            name.replaceFirstChar { it.uppercase() }
                .replace(Regex("[^a-zA-Z0-9]"), "")
                .let { if (it.firstOrNull()?.isDigit() == true) "Provider$it" else it }
        } catch (_: Exception) { "GeneratedProvider" }
    }

    private fun detectPatterns(logs: List<NetworkLog>): List<DetectedPattern> {
        val patterns = mutableListOf<DetectedPattern>()
        val seen = mutableSetOf<String>()

        for (log in logs) {
            val url = log.url
            val urlLower = url.lowercase()
            val key = urlLower.substringBefore("?")
            if (!seen.add(key)) continue

            // Streaming URLs — highest priority
            if (STREAMING_EXTENSIONS.any { urlLower.substringBefore("?").endsWith(it) }) {
                patterns.add(DetectedPattern(
                    type = if (urlLower.contains(".m3u8")) PatternType.STREAMING_URL else PatternType.MEDIA_URL,
                    url = url,
                    description = "Stream/media URL",
                    method = log.method,
                    responseCode = log.responseCode
                ))
                continue
            }

            // Episode/player embed APIs
            if (EPISODE_PATTERNS.any { urlLower.contains(it) } && (log.isXHR || JSON_MIME.any { log.mimeType?.contains(it) == true })) {
                patterns.add(DetectedPattern(
                    type = PatternType.EPISODE_API,
                    url = url,
                    description = "Episode/player API endpoint",
                    method = log.method,
                    responseCode = log.responseCode,
                    responseBody = log.responseBody?.take(500)
                ))
                continue
            }

            // Search endpoints
            if (SEARCH_PATTERNS.any { urlLower.contains(it) }) {
                patterns.add(DetectedPattern(
                    type = PatternType.SEARCH_ENDPOINT,
                    url = url,
                    description = "Search endpoint",
                    method = log.method,
                    responseCode = log.responseCode
                ))
                continue
            }

            // JSON API endpoints
            val isJson = JSON_MIME.any { log.mimeType?.contains(it) == true } ||
                log.requestHeaders["Accept"]?.contains("application/json") == true
            if (log.isXHR || isJson) {
                patterns.add(DetectedPattern(
                    type = PatternType.API_ENDPOINT,
                    url = url,
                    description = "JSON/API endpoint",
                    method = log.method,
                    responseCode = log.responseCode,
                    responseBody = log.responseBody?.take(300)
                ))
            }
        }
        return patterns
    }

    private fun extractCommonHeaders(logs: List<NetworkLog>, baseUrl: String): Map<String, String> {
        // Only include headers from requests to the main domain
        val mainHost = try { URI(baseUrl).host } catch (_: Exception) { "" }
        val relevant = logs.filter {
            try { URI(it.url).host?.contains(mainHost ?: "") == true } catch (_: Exception) { false }
        }

        val frequency = mutableMapOf<String, MutableMap<String, Int>>()
        val importantHeaders = setOf("user-agent", "accept", "accept-language", "referer",
            "origin", "x-requested-with", "authorization", "x-api-key", "x-auth-token")

        for (log in relevant) {
            for ((k, v) in log.requestHeaders) {
                if (k.lowercase() in importantHeaders) {
                    val map = frequency.getOrPut(k) { mutableMapOf() }
                    map[v] = (map[v] ?: 0) + 1
                }
            }
        }

        return frequency.mapNotNull { (header, values) ->
            val best = values.maxByOrNull { it.value }?.key ?: return@mapNotNull null
            header to best
        }.toMap()
    }

    private fun extractAllCookies(logs: List<NetworkLog>, baseUrl: String): List<String> {
        val mainHost = try { URI(baseUrl).host?.removePrefix("www.") } catch (_: Exception) { "" }
        return logs
            .filter { log ->
                try { URI(log.url).host?.contains(mainHost ?: "") == true } catch (_: Exception) { false }
            }
            .flatMap { it.cookies }
            .map { it.substringBefore(";").trim() }  // just name=value
            .filter { it.isNotBlank() && it.contains("=") }
            .distinct()
    }

    private fun extractQueryParam(url: String): String? {
        return try {
            val query = URI(url).query ?: return null
            val params = query.split("&")
            SEARCH_PATTERNS.forEach { pattern ->
                params.forEach { param ->
                    if (param.lowercase().startsWith(pattern)) return param.substringBefore("=")
                }
            }
            params.firstOrNull()?.substringBefore("=")
        } catch (_: Exception) { null }
    }

    private fun buildGeneratedCode(
        baseUrl: String,
        providerName: String,
        commonHeaders: Map<String, String>,
        allCookies: List<String>,
        searchPattern: DetectedPattern?,
        episodeApiPattern: DetectedPattern?,
        streamingUrls: List<String>,
        mediaUrls: List<String>,
        apiEndpoints: List<String>,
        patterns: List<DetectedPattern>,
        logs: List<NetworkLog>
    ): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        // Build defaultHeaders map
        val headersMap = commonHeaders
            .filter { it.key.lowercase() !in listOf("host", "content-length", "content-type") }
            .entries.take(6)
            .joinToString(",\n        ") { (k, v) ->
                "\"$k\" to \"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            }

        val cookieLine = if (allCookies.isNotEmpty()) {
            val cookieStr = allCookies.take(5).joinToString("; ")
            ",\n        \"Cookie\" to \"$cookieStr\""
        } else ""

        // Search impl
        val searchImpl = if (searchPattern != null) {
            val param = extractQueryParam(searchPattern.url) ?: "q"
            val cleanEndpoint = searchPattern.url.substringBefore("?")
            val isJson = JSON_MIME.any { searchPattern.url.contains("json") } ||
                logs.find { it.url == searchPattern.url }?.mimeType?.contains("json") == true

            if (isJson) {
                """        val url = "$cleanEndpoint?$param=\${query.encodeURL()}"
        val response = app.get(url, headers = defaultHeaders).parsedSafe<SearchResponse>()
        // TODO: adjust based on actual JSON structure from captured response
        return response?.results?.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val href = fixUrl(item.url ?: item.link ?: return@mapNotNull null)
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = item.poster ?: item.image
            }
        } ?: emptyList()"""
            } else {
                """        val url = "$cleanEndpoint?$param=\${query.encodeURL()}"
        val doc = app.get(url, headers = defaultHeaders).document
        // TODO: adjust CSS selectors for actual page structure
        return doc.select("a.result-item, .search-result, article, .item").mapNotNull { el ->
            val href = el.attr("abs:href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = el.select("h2, h3, .title, [class*=title]").text().trim()
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = el.select("img").firstOrNull()?.attr("abs:src")
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }"""
            }
        } else {
            """        // TODO: No search endpoint detected — browse to a search results page first
        val url = "\$mainUrl/search?q=\${query.encodeURL()}"
        val doc = app.get(url, headers = defaultHeaders).document
        return doc.select("article, .item, .result").mapNotNull { el ->
            val href = el.attr("abs:href").takeIf { it.isNotBlank() }
                ?: el.selectFirst("a")?.attr("abs:href") ?: return@mapNotNull null
            val title = el.selectFirst("h2, h3, .title")?.text()?.trim() ?: return@mapNotNull null
            newMovieSearchResponse(title, href, TvType.Movie)
        }"""
        }

        // loadLinks impl
        val allStreams = streamingUrls + mediaUrls
        val loadLinksImpl = when {
            episodeApiPattern != null -> {
                val apiUrl = episodeApiPattern.url.substringBefore("?")
                """        // Detected episode API: $apiUrl
        // TODO: parse the `data` URL to extract episode ID, then call the API
        val doc = app.get(data, headers = defaultHeaders).document
        val episodeId = doc.select("[data-id], [data-episode-id], #episode-id")
            .firstOrNull()?.attr("data-id")
            ?: Regex("""id\s*[:=]\s*["']?(\w+)""").find(doc.html())?.groupValues?.get(1)
        if (episodeId != null) {
            val apiResponse = app.get("$apiUrl/\$episodeId", headers = defaultHeaders)
                .parsedSafe<EpisodeResponse>()
            apiResponse?.sources?.forEach { source ->
                callback(ExtractorLink(
                    source = name,
                    name = "\$name - \${source.label ?: "Auto"}",
                    url = source.url ?: return@forEach,
                    referer = data,
                    quality = getQualityFromName(source.label),
                    isM3u8 = source.url.contains(".m3u8")
                ))
            }
            return apiResponse?.sources?.isNotEmpty() == true
        }
        return false"""
            }
            allStreams.isNotEmpty() -> {
                val streamNote = allStreams.take(3).joinToString("\n") { "        //   $it" }
                """        // Detected stream URLs (use as pattern reference):
$streamNote
        val doc = app.get(data, headers = defaultHeaders).document

        // Try embedded sources
        val streamUrl = doc.select("source[src], video[src]").firstOrNull()?.attr("abs:src")
            ?: extractStreamFromScripts(doc)
            ?: extractFromIframe(doc, data)

        if (streamUrl != null) {
            callback(ExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                referer = data,
                quality = Qualities.Unknown.value,
                isM3u8 = streamUrl.contains(".m3u8")
            ))
            return true
        }

        // Try embed extractors
        doc.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("abs:src")
            if (iframeSrc.isNotBlank()) {
                loadExtractor(iframeSrc, data, subtitleCallback, callback)
            }
        }
        return false"""
            }
            else -> {
                """        // TODO: No stream URLs detected — browse to a video player page first
        val doc = app.get(data, headers = defaultHeaders).document
        val streamUrl = extractStreamFromScripts(doc)
            ?: doc.select("source[src]").firstOrNull()?.attr("abs:src")
        if (streamUrl != null) {
            callback(ExtractorLink(
                source = name, name = name, url = streamUrl,
                referer = data, quality = Qualities.Unknown.value,
                isM3u8 = streamUrl.contains(".m3u8")
            ))
            return streamUrl.isNotBlank()
        }
        return false"""
            }
        }

        // API endpoints comment
        val apiComment = if (apiEndpoints.isNotEmpty()) {
            "\n    // Detected API endpoints:\n" + apiEndpoints.take(8).joinToString("\n") { "    //   $it" } + "\n"
        } else ""

        return """// Auto-generated by CS3 Extension Builder — $dateStr
// Site: $baseUrl
// ⚠ This is a starting point — review selectors/URLs before using.

package com.example.${providerName.lowercase()}

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parsedSafe
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ${providerName}Provider : MainAPI() {
    override var mainUrl = "$baseUrl"
    override var name = "$providerName"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "en"
    override val hasMainPage = true
    override val hasSearch = true
    override val hasDownloadSupport = true
$apiComment
    // Auto-detected request headers
    private val defaultHeaders = mapOf(
        $headersMap$cookieLine
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(mainUrl, headers = defaultHeaders).document
        // TODO: adjust selectors for actual homepage layout
        val items = doc.select(".movie-item, .content-item, article, .item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
$searchImpl
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = defaultHeaders).document
        val title = doc.selectFirst("h1, h2.title, .movie-title, [itemprop=name]")
            ?.text()?.trim() ?: return null
        val poster = doc.selectFirst("img.poster, .thumbnail img, [itemprop=image], .cover img")
            ?.let { it.attr("data-src").ifBlank { it.attr("abs:src") } }
        val description = doc.selectFirst(".description, .synopsis, p.overview, [itemprop=description]")
            ?.text()
        val tags = doc.select("a[href*=genre], a[href*=category], .genres a").map { it.text() }
        val year = doc.selectFirst(".year, [itemprop=dateCreated]")?.text()
            ?.filter { it.isDigit() }?.take(4)?.toIntOrNull()

        // Detect if it's a TV series (has episodes)
        val episodeLinks = doc.select("a[href*=episode], a[href*=/ep-], .episodes a")
        return if (episodeLinks.isNotEmpty()) {
            val episodes = episodeLinks.mapIndexed { i, el ->
                newEpisode(el.attr("abs:href")) {
                    this.name = el.text().trim().ifBlank { "Episode \${i + 1}" }
                    this.episode = el.attr("data-num").toIntOrNull() ?: (i + 1)
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
$loadLinksImpl
    }

    // --- helpers ---

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("abs:href").takeIf { it.isNotBlank() }
            ?: selectFirst("a")?.attr("abs:href") ?: return null
        val title = selectFirst("h2, h3, .title, [class*=title]")?.text()?.trim()
            ?: selectFirst("a")?.text()?.trim() ?: return null
        val poster = selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("abs:src") } }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    private fun extractStreamFromScripts(doc: Document): String? {
        val patterns = listOf(
            Regex(""""(https?://[^"]+\.m3u8[^"]*)""""),
            Regex(""""(https?://[^"]+\.mp4[^"]*)""""),
            Regex("""'(https?://[^']+\.m3u8[^']*)'"""),
            Regex("""file\s*:\s*["'](https?://[^"']+)["']"""),
            Regex("""source\s*:\s*["'](https?://[^"']+)["']"""),
            Regex("""src\s*:\s*["'](https?://[^"']+)["']"""),
            Regex("""hls\s*:\s*["'](https?://[^"']+)["']""")
        )
        for (script in doc.select("script").map { it.html() }) {
            for (pattern in patterns) {
                val match = pattern.find(script) ?: continue
                val url = match.groupValues[1]
                if (url.isNotBlank()) return url
            }
        }
        return null
    }

    private suspend fun extractFromIframe(doc: Document, referer: String): String? {
        val iframe = doc.selectFirst("iframe[src]") ?: return null
        val src = iframe.attr("abs:src").takeIf { it.isNotBlank() } ?: return null
        val iframeDoc = try { app.get(src, referer = referer, headers = defaultHeaders).document }
            catch (_: Exception) { return null }
        return extractStreamFromScripts(iframeDoc)
    }

    // Data classes for JSON API responses — adjust to match actual API
    data class SearchResponse(val results: List<SearchItem>? = null, val data: List<SearchItem>? = null)
    data class SearchItem(
        val title: String? = null, val name: String? = null,
        val url: String? = null, val link: String? = null,
        val poster: String? = null, val image: String? = null
    )
    data class EpisodeResponse(val sources: List<StreamSource>? = null)
    data class StreamSource(val url: String? = null, val label: String? = null, val type: String? = null)
}
"""
    }

    private fun buildLogSummary(
        allLogs: List<NetworkLog>,
        filtered: List<NetworkLog>,
        baseUrl: String,
        patterns: List<DetectedPattern>,
        headers: Map<String, String>,
        cookies: List<String>
    ): String {
        val domains = allLogs.mapNotNull { try { URI(it.url).host } catch (_: Exception) { null } }.distinct()
        val methodCounts = allLogs.groupBy { it.method }.mapValues { it.value.size }
        val statusCounts = allLogs.filter { it.responseCode > 0 }.groupBy { it.responseCode / 100 * 100 }.mapValues { it.value.size }

        return buildString {
            appendLine("=== CS3 Extension Builder — Capture Summary ===")
            appendLine()
            appendLine("Total captured  : ${allLogs.size}")
            appendLine("After filtering : ${filtered.size}")
            appendLine("Unique domains  : ${domains.size}")
            appendLine("Base URL        : $baseUrl")
            appendLine()

            appendLine("--- HTTP Methods ---")
            methodCounts.entries.sortedByDescending { it.value }.forEach { (m, c) -> appendLine("  $m: $c") }
            appendLine()

            appendLine("--- Status Codes ---")
            statusCounts.entries.sortedBy { it.key }.forEach { (b, c) -> appendLine("  ${b}xx: $c") }
            appendLine()

            appendLine("--- Detected Patterns ---")
            for (type in PatternType.entries) {
                val group = patterns.filter { it.type == type }
                if (group.isEmpty()) continue
                appendLine("  ${type.name} (${group.size}):")
                group.take(4).forEach { appendLine("    [${it.method}] ${it.url.take(100)}") }
            }
            appendLine()

            appendLine("--- Common Headers ---")
            if (headers.isEmpty()) appendLine("  (none)")
            else headers.forEach { (k, v) -> appendLine("  $k: ${v.take(80)}") }
            appendLine()

            appendLine("--- Cookies Set ---")
            if (cookies.isEmpty()) appendLine("  (none)")
            else {
                cookies.take(15).forEach { appendLine("  ${it.take(100)}") }
                if (cookies.size > 15) appendLine("  ... +${cookies.size - 15} more")
            }
            appendLine()

            appendLine("--- All Domains ---")
            domains.sorted().forEach { appendLine("  $it") }
        }
    }
}
