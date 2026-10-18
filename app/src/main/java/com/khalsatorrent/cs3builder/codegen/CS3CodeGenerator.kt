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
    private val SEARCH_PATTERNS = listOf("search", "query", "q=", "keyword", "find", "suggest")
    private val EPISODE_PATTERNS = listOf("episode", "/ep/", "ep=", "watch", "player", "embed", "stream")
    private val JSON_MIME = listOf("application/json", "text/json")
    private val SKIP_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".gif", ".ico", ".svg",
        ".woff", ".woff2", ".css", ".js", ".webp", ".map")
    private val SKIP_DOMAINS = listOf("google-analytics", "googletagmanager", "doubleclick",
        "facebook.com", "twitter.com", "recaptcha", "cloudflare.com/cdn-cgi",
        "gstatic.com", "googleapis.com/ajax", "disqus.com", "hotjar.com")

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

            // Streaming — highest priority
            if (STREAMING_EXTENSIONS.any { urlLower.substringBefore("?").endsWith(it) }) {
                patterns.add(DetectedPattern(
                    type = if (urlLower.contains(".m3u8")) PatternType.STREAMING_URL else PatternType.MEDIA_URL,
                    url = url, description = "Stream URL",
                    method = log.method, responseCode = log.responseCode
                ))
                continue
            }

            // Episode API
            val isApiResponse = JSON_MIME.any { log.mimeType?.contains(it) == true } ||
                log.requestHeaders["Accept"]?.contains("application/json") == true
            if (EPISODE_PATTERNS.any { urlLower.contains(it) } && (log.isXHR || isApiResponse)) {
                patterns.add(DetectedPattern(
                    type = PatternType.EPISODE_API, url = url, description = "Episode/player API",
                    method = log.method, responseCode = log.responseCode,
                    responseBody = log.responseBody?.take(500)
                ))
                continue
            }

            // Search
            if (SEARCH_PATTERNS.any { urlLower.contains(it) }) {
                patterns.add(DetectedPattern(
                    type = PatternType.SEARCH_ENDPOINT, url = url, description = "Search endpoint",
                    method = log.method, responseCode = log.responseCode
                ))
                continue
            }

            // JSON API
            if (log.isXHR || isApiResponse) {
                patterns.add(DetectedPattern(
                    type = PatternType.API_ENDPOINT, url = url, description = "API endpoint",
                    method = log.method, responseCode = log.responseCode,
                    responseBody = log.responseBody?.take(300)
                ))
            }
        }
        return patterns
    }

    private fun extractCommonHeaders(logs: List<NetworkLog>, baseUrl: String): Map<String, String> {
        val mainHost = try { URI(baseUrl).host } catch (_: Exception) { "" }
        val relevant = logs.filter {
            try { URI(it.url).host?.contains(mainHost ?: "") == true } catch (_: Exception) { false }
        }
        val important = setOf("user-agent", "accept", "accept-language", "referer",
            "origin", "x-requested-with", "authorization", "x-api-key")
        val freq = mutableMapOf<String, MutableMap<String, Int>>()
        for (log in relevant) {
            for ((k, v) in log.requestHeaders) {
                if (k.lowercase() in important) {
                    val m = freq.getOrPut(k) { mutableMapOf() }
                    m[v] = (m[v] ?: 0) + 1
                }
            }
        }
        return freq.mapNotNull { (h, vals) ->
            val best = vals.maxByOrNull { it.value }?.key ?: return@mapNotNull null
            h to best
        }.toMap()
    }

    private fun extractAllCookies(logs: List<NetworkLog>, baseUrl: String): List<String> {
        val mainHost = try { URI(baseUrl).host?.removePrefix("www.") } catch (_: Exception) { "" }
        return logs
            .filter { log ->
                try { URI(log.url).host?.contains(mainHost ?: "") == true } catch (_: Exception) { false }
            }
            .flatMap { it.cookies }
            .map { it.substringBefore(";").trim() }
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

    // Use + concat to avoid triple-quote collisions when embedding code strings
    private fun q3(s: String) = s  // just a readable alias

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
        val pkg = "com.example.${providerName.lowercase()}"

        val headersMap = commonHeaders
            .filter { it.key.lowercase() !in listOf("host", "content-length", "content-type") }
            .entries.take(6)
            .joinToString(",\n        ") { (k, v) ->
                "\"$k\" to \"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            }
            .ifBlank {
                "\"User-Agent\" to \"Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36\""
            }

        val cookieLine = if (allCookies.isNotEmpty()) {
            ",\n        \"Cookie\" to \"" + allCookies.take(5).joinToString("; ") + "\""
        } else ""

        // --- search block ---
        val searchBlock: String
        if (searchPattern != null) {
            val param = extractQueryParam(searchPattern.url) ?: "q"
            val endpoint = searchPattern.url.substringBefore("?")
            val isJson = logs.find { it.url == searchPattern.url }
                ?.mimeType?.contains("json") == true
            searchBlock = if (isJson) {
                "        val url = \"$endpoint?\${param.encodeURL()}\"\n" +
                "        // JSON search — adjust field names to match actual response\n" +
                "        val response = app.get(url, headers = defaultHeaders)\n" +
                "            .parsedSafe<SearchApiResponse>()\n" +
                "        return response?.results?.mapNotNull { item ->\n" +
                "            val title = item.title ?: item.name ?: return@mapNotNull null\n" +
                "            val href = fixUrl(item.url ?: item.link ?: return@mapNotNull null)\n" +
                "            newMovieSearchResponse(title, href, TvType.Movie) {\n" +
                "                this.posterUrl = item.poster ?: item.image\n" +
                "            }\n" +
                "        } ?: emptyList()"
            } else {
                "        val url = \"$endpoint?$param=\${query.encodeURL()}\"\n" +
                "        val doc = app.get(url, headers = defaultHeaders).document\n" +
                "        return doc.select(\".item, article, .result, .search-result\").mapNotNull { el ->\n" +
                "            val href = el.attr(\"abs:href\").takeIf { it.isNotBlank() }\n" +
                "                ?: el.selectFirst(\"a\")?.attr(\"abs:href\") ?: return@mapNotNull null\n" +
                "            val title = el.selectFirst(\"h2, h3, .title\")?.text()?.trim()\n" +
                "                .takeIf { !it.isNullOrBlank() } ?: return@mapNotNull null\n" +
                "            newMovieSearchResponse(title, href, TvType.Movie) {\n" +
                "                this.posterUrl = el.selectFirst(\"img\")?.attr(\"abs:src\")\n" +
                "            }\n" +
                "        }"
            }
        } else {
            searchBlock = "        // TODO: No search endpoint detected\n" +
                "        val url = \"\$mainUrl/search?q=\${query.encodeURL()}\"\n" +
                "        val doc = app.get(url, headers = defaultHeaders).document\n" +
                "        return doc.select(\".item, article\").mapNotNull { el ->\n" +
                "            val href = el.selectFirst(\"a\")?.attr(\"abs:href\") ?: return@mapNotNull null\n" +
                "            val title = el.selectFirst(\"h2, h3, .title\")?.text()?.trim() ?: return@mapNotNull null\n" +
                "            newMovieSearchResponse(title, href, TvType.Movie)\n" +
                "        }"
        }

        // --- loadLinks block ---
        val allStreams = streamingUrls + mediaUrls
        val loadLinksBlock: String
        if (episodeApiPattern != null) {
            val apiUrl = episodeApiPattern.url.substringBefore("?")
            loadLinksBlock =
                "        // Detected episode API: $apiUrl\n" +
                "        val doc = app.get(data, headers = defaultHeaders).document\n" +
                "        val episodeId = doc.selectFirst(\"[data-id], [data-episode-id]\")?.attr(\"data-id\")\n" +
                "        if (episodeId != null) {\n" +
                "            val resp = app.get(\"$apiUrl/\$episodeId\", headers = defaultHeaders)\n" +
                "                .parsedSafe<EpisodeResponse>()\n" +
                "            resp?.sources?.forEach { src ->\n" +
                "                callback(ExtractorLink(\n" +
                "                    source = name,\n" +
                "                    name = \"\$name - \${src.label ?: \"Auto\"}\",\n" +
                "                    url = src.url ?: return@forEach,\n" +
                "                    referer = data,\n" +
                "                    quality = getQualityFromName(src.label),\n" +
                "                    isM3u8 = src.url.contains(\".m3u8\")\n" +
                "                ))\n" +
                "            }\n" +
                "            return resp?.sources?.isNotEmpty() == true\n" +
                "        }\n" +
                "        return false"
        } else if (allStreams.isNotEmpty()) {
            val streamNotes = allStreams.take(3).joinToString("\n") { "        //   $it" }
            loadLinksBlock =
                "        // Detected stream URLs:\n$streamNotes\n" +
                "        val doc = app.get(data, headers = defaultHeaders).document\n" +
                "        val streamUrl = doc.selectFirst(\"source[src], video[src]\")?.attr(\"abs:src\")\n" +
                "            ?: extractStreamFromScripts(doc)\n" +
                "            ?: extractFromIframe(doc, data)\n" +
                "        if (streamUrl != null) {\n" +
                "            callback(ExtractorLink(\n" +
                "                source = name, name = name, url = streamUrl,\n" +
                "                referer = data, quality = Qualities.Unknown.value,\n" +
                "                isM3u8 = streamUrl.contains(\".m3u8\")\n" +
                "            ))\n" +
                "            return true\n" +
                "        }\n" +
                "        doc.select(\"iframe[src]\").forEach { iframe ->\n" +
                "            loadExtractor(iframe.attr(\"abs:src\"), data, subtitleCallback, callback)\n" +
                "        }\n" +
                "        return false"
        } else {
            loadLinksBlock =
                "        // TODO: No stream URLs captured — browse to a video player page first\n" +
                "        val doc = app.get(data, headers = defaultHeaders).document\n" +
                "        val streamUrl = extractStreamFromScripts(doc)\n" +
                "            ?: doc.selectFirst(\"source[src]\")?.attr(\"abs:src\")\n" +
                "        if (streamUrl != null) {\n" +
                "            callback(ExtractorLink(\n" +
                "                source = name, name = name, url = streamUrl,\n" +
                "                referer = data, quality = Qualities.Unknown.value,\n" +
                "                isM3u8 = streamUrl.contains(\".m3u8\")\n" +
                "            ))\n" +
                "            return true\n" +
                "        }\n" +
                "        return false"
        }

        val apiComment = if (apiEndpoints.isNotEmpty()) {
            "\n    // Detected API endpoints:\n" +
            apiEndpoints.take(8).joinToString("\n") { "    //   $it" } + "\n"
        } else ""

        val headersComment = commonHeaders.entries.take(6)
            .joinToString("\n") { (k, v) -> "    //   $k: $v" }
            .ifBlank { "    //   (none detected)" }

        val cookiesComment = if (allCookies.isNotEmpty())
            allCookies.take(8).joinToString("\n") { "    //   $it" }
        else "    //   (none)"

        return "// Auto-generated by CS3 Extension Builder — $dateStr\n" +
            "// Site: $baseUrl\n" +
            "// NOTE: Starting point only — adjust selectors/URLs before using.\n\n" +
            "package $pkg\n\n" +
            "import com.lagradost.cloudstream3.*\n" +
            "import com.lagradost.cloudstream3.utils.*\n" +
            "import com.lagradost.cloudstream3.utils.AppUtils.parsedSafe\n" +
            "import org.jsoup.nodes.Document\n" +
            "import org.jsoup.nodes.Element\n\n" +
            "class ${providerName}Provider : MainAPI() {\n" +
            "    override var mainUrl = \"$baseUrl\"\n" +
            "    override var name = \"$providerName\"\n" +
            "    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)\n" +
            "    override var lang = \"en\"\n" +
            "    override val hasMainPage = true\n" +
            "    override val hasSearch = true\n" +
            "    override val hasDownloadSupport = true\n" +
            "$apiComment\n" +
            "    // Detected request headers:\n" +
            "$headersComment\n\n" +
            "    // Detected cookies:\n" +
            "$cookiesComment\n\n" +
            "    private val defaultHeaders = mapOf(\n" +
            "        $headersMap$cookieLine\n" +
            "    )\n\n" +
            "    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {\n" +
            "        val doc = app.get(mainUrl, headers = defaultHeaders).document\n" +
            "        val items = doc.select(\".movie-item, .content-item, article, .item\").mapNotNull { it.toSearchResult() }\n" +
            "        return newHomePageResponse(request.name, items)\n" +
            "    }\n\n" +
            "    override suspend fun search(query: String): List<SearchResponse> {\n" +
            "$searchBlock\n" +
            "    }\n\n" +
            "    override suspend fun load(url: String): LoadResponse? {\n" +
            "        val doc = app.get(url, headers = defaultHeaders).document\n" +
            "        val title = doc.selectFirst(\"h1, .title, .movie-title, [itemprop=name]\")?.text()?.trim() ?: return null\n" +
            "        val poster = doc.selectFirst(\"img.poster, .thumbnail img, [itemprop=image]\")?.let {\n" +
            "            it.attr(\"data-src\").ifBlank { it.attr(\"abs:src\") }\n" +
            "        }\n" +
            "        val plot = doc.selectFirst(\".description, .synopsis, [itemprop=description]\")?.text()\n" +
            "        val episodeLinks = doc.select(\"a[href*=episode], a[href*=/ep-], .episodes a\")\n" +
            "        return if (episodeLinks.isNotEmpty()) {\n" +
            "            val episodes = episodeLinks.mapIndexed { i, el ->\n" +
            "                newEpisode(el.attr(\"abs:href\")) {\n" +
            "                    this.name = el.text().trim().ifBlank { \"Episode \${i + 1}\" }\n" +
            "                    this.episode = el.attr(\"data-num\").toIntOrNull() ?: (i + 1)\n" +
            "                }\n" +
            "            }\n" +
            "            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {\n" +
            "                this.posterUrl = poster; this.plot = plot\n" +
            "            }\n" +
            "        } else {\n" +
            "            newMovieLoadResponse(title, url, TvType.Movie, url) {\n" +
            "                this.posterUrl = poster; this.plot = plot\n" +
            "            }\n" +
            "        }\n" +
            "    }\n\n" +
            "    override suspend fun loadLinks(\n" +
            "        data: String, isCasting: Boolean,\n" +
            "        subtitleCallback: (SubtitleFile) -> Unit,\n" +
            "        callback: (ExtractorLink) -> Unit\n" +
            "    ): Boolean {\n" +
            "$loadLinksBlock\n" +
            "    }\n\n" +
            "    private fun Element.toSearchResult(): SearchResponse? {\n" +
            "        val href = attr(\"abs:href\").takeIf { it.isNotBlank() }\n" +
            "            ?: selectFirst(\"a\")?.attr(\"abs:href\") ?: return null\n" +
            "        val title = selectFirst(\"h2, h3, .title\")?.text()?.trim() ?: return null\n" +
            "        val poster = selectFirst(\"img\")?.attr(\"abs:src\")\n" +
            "        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }\n" +
            "    }\n\n" +
            "    private fun extractStreamFromScripts(doc: Document): String? {\n" +
            "        val patterns = listOf(\n" +
            "            Regex(\"\\\"(https?://[^\\\"]+\\\\.m3u8[^\\\"]*)\\\"\"),\n" +
            "            Regex(\"\\\"(https?://[^\\\"]+\\\\.mp4[^\\\"]*)\\\"\"),\n" +
            "            Regex(\"file\\\\s*:\\\\s*[\\\"'](https?://[^\\\"']+)[\\\"']\"),\n" +
            "            Regex(\"hls\\\\s*:\\\\s*[\\\"'](https?://[^\\\"']+)[\\\"']\"),\n" +
            "            Regex(\"src\\\\s*:\\\\s*[\\\"'](https?://[^\\\"']+[.](m3u8|mp4))[\\\"']\")\n" +
            "        )\n" +
            "        for (script in doc.select(\"script\").map { it.html() }) {\n" +
            "            for (p in patterns) { p.find(script)?.let { return it.groupValues[1] } }\n" +
            "        }\n" +
            "        return null\n" +
            "    }\n\n" +
            "    private suspend fun extractFromIframe(doc: Document, referer: String): String? {\n" +
            "        val src = doc.selectFirst(\"iframe[src]\")?.attr(\"abs:src\")?.takeIf { it.isNotBlank() } ?: return null\n" +
            "        return try { extractStreamFromScripts(app.get(src, referer = referer, headers = defaultHeaders).document) }\n" +
            "        catch (_: Exception) { null }\n" +
            "    }\n\n" +
            "    data class SearchApiResponse(val results: List<SearchItem>? = null, val data: List<SearchItem>? = null)\n" +
            "    data class SearchItem(val title: String? = null, val name: String? = null,\n" +
            "        val url: String? = null, val link: String? = null,\n" +
            "        val poster: String? = null, val image: String? = null)\n" +
            "    data class EpisodeResponse(val sources: List<StreamSource>? = null)\n" +
            "    data class StreamSource(val url: String? = null, val label: String? = null)\n" +
            "}\n"
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
        val statusCounts = allLogs.filter { it.responseCode > 0 }
            .groupBy { it.responseCode / 100 * 100 }.mapValues { it.value.size }

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
            if (headers.isEmpty()) appendLine("  (none)") else headers.forEach { (k, v) -> appendLine("  $k: ${v.take(80)}") }
            appendLine()
            appendLine("--- Cookies Set ---")
            if (cookies.isEmpty()) appendLine("  (none)") else {
                cookies.take(15).forEach { appendLine("  ${it.take(100)}") }
                if (cookies.size > 15) appendLine("  ... +${cookies.size - 15} more")
            }
            appendLine()
            appendLine("--- All Domains ---")
            domains.sorted().forEach { appendLine("  $it") }
        }
    }
}
