package com.khalsatorrent.cs3builder.codegen

import com.khalsatorrent.cs3builder.model.LogKind
import com.khalsatorrent.cs3builder.model.NetworkLog
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder

data class SearchInfo(
    val sampleUrl: String,
    val method: String,
    val query: String,
    /** Kotlin string-literal content (already escaped) with `§{query.enc()}` where the query goes. */
    val urlTemplate: String,
    /** For POST forms: key -> literal value, null = the search query. */
    val formBody: List<Pair<String, String?>>?,
    /** For JSON POST bodies: a Kotlin expression building the body with `query.toJson()` spliced in. */
    val jsonBody: String?,
    val isJson: Boolean,
    val json: JsonShape?,
    val cards: CardSelectors?,
    val extraHeaders: Map<String, String>
)

data class StreamInfo(val url: String, val type: String, val referer: String?, val origin: String?, val host: String)
data class EmbedInfo(val url: String, val host: String, val knownAs: String?)
data class ApiInfo(val method: String, val url: String, val status: Int, val shape: JsonShape?, val requestBody: String?)

data class SiteAnalysis(
    val baseUrl: String,
    val host: String,
    val providerName: String,
    val language: String,
    val tvTypes: List<String>,
    val headers: Map<String, String>,
    val cookies: List<String>,
    val usesCloudflare: Boolean,
    val search: SearchInfo?,
    val listingCards: CardSelectors?,
    val sections: List<Pair<String, String>>,
    val pagination: String?,           // "path" (/page/2/), "query:<param>" (?page=2) or null
    val detail: DetailSelectors?,
    val detailUrl: String?,
    val streams: List<StreamInfo>,
    val embeds: List<EmbedInfo>,
    val apis: List<ApiInfo>,
    val frameworks: List<String>,
    val pagesAnalyzed: Int,
    val notes: List<String>
)

object SiteAnalyzer {

    private val NOISE_HOSTS = listOf(
        "google-analytics", "googletagmanager", "doubleclick", "googlesyndication", "facebook.", "fbcdn",
        "twitter.", "recaptcha", "gstatic.com", "hotjar", "disqus", "cloudflareinsights", "sentry.io",
        "clarity.ms", "yandex", "histats", "statcounter", "onesignal", "jsdelivr.net", "cdnjs.", "unpkg.com",
        "fonts.googleapis", "googleapis.com/ajax", "jquery.com", "bootstrapcdn", "fontawesome", "amazon-adsystem",
        "duckduckgo.com", "google.com/search", "gravatar", "wp.com", "pinterest", "tiktok", "scorecardresearch"
    )

    val KNOWN_EXTRACTORS = linkedMapOf(
        "streamtape" to "StreamTape", "dood" to "DoodStream", "d000d" to "DoodStream", "ds2play" to "DoodStream",
        "filemoon" to "FileMoon", "mixdrop" to "MixDrop", "voe." to "Voe", "streamwish" to "StreamWish",
        "wishembed" to "StreamWish", "vidhide" to "VidHide", "filelions" to "FileLions", "mp4upload" to "Mp4Upload",
        "ok.ru" to "Odnoklassniki", "vidmoly" to "Vidmoly", "megacloud" to "MegaCloud", "rabbitstream" to "RabbitStream",
        "dokicloud" to "DokiCloud", "gogocdn" to "GogoCDN", "embtaku" to "Gogo", "streamsb" to "StreamSB", "sbembed" to "StreamSB",
        "uqload" to "Uqload", "upstream" to "UpStream", "vidguard" to "VidGuard", "listeamed" to "VidGuard",
        "lulustream" to "LuluStream", "abyss" to "Abyss", "kwik" to "Kwik", "vtube" to "Vtube", "streamlare" to "Streamlare",
        "fembed" to "Fembed", "vidplay" to "VidPlay", "mcloud" to "MyCloud", "chillx" to "Chillx", "boosterx" to "Chillx",
        "rapid-cloud" to "RapidCloud", "youtube" to "YouTube", "dailymotion" to "Dailymotion", "vimeo" to "Vimeo",
        "sendvid" to "SendVid", "yourupload" to "YourUpload", "vidoza" to "Vidoza", "hqq" to "Netu", "waaw" to "Netu",
        "supervideo" to "SuperVideo", "dropload" to "DropLoad", "pixeldrain" to "PixelDrain", "gofile" to "GoFile",
        "krakenfiles" to "Krakenfiles", "streamhub" to "StreamHub", "vixcloud" to "VixCloud", "savefiles" to "SaveFiles",
        "drive.google" to "GDrive", "vidsrc" to "VidSrc (needs custom code)", "2embed" to "2Embed (needs custom code)",
        "multiembed" to "MultiEmbed (needs custom code)"
    )

    private val SEARCH_PARAMS = listOf("s", "q", "query", "keyword", "keywords", "search", "term", "k", "key", "searchword", "search_query", "title", "name", "wd")
    private val STRONG_PARAMS = setOf("s", "q", "query", "keyword", "keywords", "search", "term", "searchword", "search_query", "wd")

    fun analyze(allLogs: List<NetworkLog>, nameOverride: String? = null): SiteAnalysis {
        // oldest first is easier to reason about
        val logs = allLogs.sortedBy { it.timestamp }.filter { l -> NOISE_HOSTS.none { l.url.contains(it, true) } }
        val notes = mutableListOf<String>()

        val baseUrl = detectBaseUrl(logs)
        val host = hostOf(baseUrl) ?: "example.com"
        val name = nameOverride?.takeIf { it.isNotBlank() }?.let { KotlinText.typeName(it, "Generated") } ?: providerName(host)

        // ---- HTML pages (prefer rendered DOM; fall back to raw HTML from deep capture)
        val pages = LinkedHashMap<String, Document>()
        logs.filter { it.kind == LogKind.DOC && it.responseCode in 200..299 && looksLikeHtml(it.responseBody) }
            .forEach { l -> HtmlAnalyzer.parse(l.responseBody!!, l.url)?.let { pages[norm(l.url)] = it } }
        logs.filter { it.kind == LogKind.DOM && looksLikeHtml(it.responseBody) }
            .forEach { l -> HtmlAnalyzer.parse(l.responseBody!!, l.url)?.let { pages[norm(l.url)] = it } }
        val sitePages = pages.filterKeys { HtmlAnalyzer.sameSite(it, host) }

        val home = sitePages.entries.firstOrNull { (u, _) -> pathOf(u).trim('/').isEmpty() }?.value
        val language = (home ?: sitePages.values.firstOrNull())?.let { HtmlAnalyzer.language(it) } ?: "en"

        // ---- cards on every page
        val cardsByPage = sitePages.mapValues { (_, d) -> HtmlAnalyzer.detectCards(d, host) }
        val allCardHrefs = cardsByPage.values.filterNotNull().flatMap { it.hrefs }.map { norm(it) }.toSet()

        // ---- search
        val search = detectSearch(logs, host, sitePages)
        if (search == null) notes += "No search was captured — search for a title on the site, then regenerate."

        // ---- listing cards: home first, otherwise the page with the most cards
        val listingCards = home?.let { cardsByPage[norm(baseUrl)] ?: HtmlAnalyzer.detectCards(it, host) }
            ?: cardsByPage.values.filterNotNull().maxByOrNull { it.count }
            ?: search?.cards
        if (listingCards == null && search?.json == null) notes += "Could not find a repeating card layout — generic selectors were used; check toSearchResult()."

        // ---- sections for the home page
        val sections = mutableListOf<Pair<String, String>>()
        cardsByPage.forEach { (u, c) ->
            if (c != null && u != norm(baseUrl) && !u.contains('?') && search?.sampleUrl?.let { norm(it) } != u && u !in allCardHrefs && sections.size < 4) {
                sections += u to labelFromPath(u)
            }
        }
        home?.let { HtmlAnalyzer.navSections(it, host) }?.forEach { (u, t) ->
            if (sections.none { norm(it.first) == norm(u) } && sections.size < 8) sections += u to t
        }

        // ---- detail page: a captured page that a card linked to
        val detailEntry = sitePages.entries.filter { (u, _) -> u in allCardHrefs }
            .maxByOrNull { (_, d) -> (if (d.selectFirst("h1") != null) 2 else 0) + d.select("iframe").size + (HtmlAnalyzer.detectEpisodes(d, host)?.count ?: 0).coerceAtMost(5) }
            ?: sitePages.entries.firstOrNull { (u, d) ->
                u != norm(baseUrl) && (d.selectFirst("meta[property=og:type][content*=video]") != null || d.selectFirst("meta[property=og:type][content*=movie]") != null)
            }
        val detail = detailEntry?.let { HtmlAnalyzer.detectDetail(it.value, host) }
        if (detailEntry == null) notes += "No detail page captured — open a movie/show page from the list, then regenerate."

        // ---- streams, embeds, APIs
        val streams = logs.filter { (it.kind == LogKind.STREAM || it.kind == LogKind.MEDIA) && isPlayable(it.url) }
            .distinctBy { it.url.substringBefore('?') }
            .map { l ->
                StreamInfo(
                    url = l.url,
                    type = when {
                        l.url.contains(".m3u8", true) || l.mimeType?.contains("mpegurl") == true -> "m3u8"
                        l.url.contains(".mpd", true) -> "dash"
                        else -> "video"
                    },
                    referer = l.header("Referer"),
                    origin = l.header("Origin"),
                    host = l.host
                )
            }
        if (streams.isEmpty()) notes += "No video stream (.m3u8/.mp4) was captured — press play on an episode/movie and let it start, then regenerate."

        val embedUrls = LinkedHashSet<String>()
        logs.filter { it.kind == LogKind.DOC && !it.isMainFrame && it.host.isNotBlank() && !HtmlAnalyzer.sameSite(it.url, host) }
            .forEach { embedUrls += it.url }
        sitePages.values.forEach { d -> d.select("iframe[src], iframe[data-src]").forEach { f ->
            val src = f.absUrl("data-src").ifBlank { f.absUrl("src") }
            if (src.startsWith("http") && !HtmlAnalyzer.sameSite(src, host) && NOISE_HOSTS.none { src.contains(it) }) embedUrls += src
        } }
        streams.mapNotNull { it.referer }.filter { it.startsWith("http") && !HtmlAnalyzer.sameSite(it, host) }.forEach { embedUrls += it }
        val embeds = embedUrls.distinctBy { hostOf(it) }.map { u ->
            val h = hostOf(u) ?: ""
            EmbedInfo(u, h, KNOWN_EXTRACTORS.entries.firstOrNull { h.contains(it.key) }?.value)
        }

        val apis = logs.filter { it.kind == LogKind.XHR && it.responseBody != null }
            .distinctBy { it.method + it.url.substringBefore('?') }
            .map { ApiInfo(it.method, it.url, it.responseCode, JsonShape.analyze(it.responseBody), it.requestBody) }
            .sortedByDescending { (if (HtmlAnalyzer.sameSite(it.url, host)) 2 else 0) + (if (it.shape != null) 1 else 0) }

        // ---- headers & cookies
        val headers = LinkedHashMap<String, String>()
        headers["Referer"] = "$baseUrl/"
        logs.filter { it.kind == LogKind.XHR }.flatMap { it.requestHeaders.entries }
            .filter { (k, _) ->
                val lk = k.lowercase()
                (lk.startsWith("x-") && lk != "x-requested-with") || lk == "authorization" || lk.contains("token") || lk.contains("api-key") || lk == "apikey"
            }
            .groupBy { it.key.lowercase() }
            .forEach { (_, entries) ->
                val e = entries.groupingBy { it.value }.eachCount().maxByOrNull { it.value } ?: return@forEach
                headers[entries.first().key] = e.key
            }

        val cookieSet = LinkedHashSet<String>()
        logs.filter { HtmlAnalyzer.sameSite(it.url, host) }.forEach { l ->
            l.cookies.forEach { c -> c.substringBefore(';').trim().takeIf { it.contains('=') }?.let { cookieSet += it } }
            l.header("Cookie")?.split(';')?.map { it.trim() }?.filter { it.contains('=') }?.forEach { cookieSet += it }
        }
        val cookies = cookieSet.distinctBy { it.substringBefore('=') }
        val cloudflare = cookies.any { it.startsWith("cf_clearance") } ||
            logs.any { HtmlAnalyzer.sameSite(it.url, host) && it.responseCode in listOf(403, 503) && it.responseHeader("server")?.contains("cloudflare", true) == true }
        if (cloudflare) notes += "Cloudflare protection detected — if requests fail in CloudStream, add `interceptor = CloudflareKiller()` to the requests."

        // ---- pagination
        val siteUrls = logs.filter { HtmlAnalyzer.sameSite(it.url, host) }.map { it.url }
        val pageLinks = sitePages.values.flatMap { d -> d.select("a[href]").map { it.absUrl("href") } }.filter { HtmlAnalyzer.sameSite(it, host) }
        val pagination = when {
            (siteUrls + pageLinks).any { Regex("/page/\\d+").containsMatchIn(it) } -> "path"
            pageLinks.any { Regex("[?&]page=\\d+").containsMatchIn(it) } -> "query:page"
            else -> logs.filter { (it.kind == LogKind.DOC || it.kind == LogKind.DOM) && HtmlAnalyzer.sameSite(it.url, host) }.map { it.url }.firstNotNullOfOrNull { Regex("[?&](page|p|pg)=(\\d+)").find(it)?.groupValues?.get(1) }?.let { "query:$it" }
        }

        // ---- frameworks
        val frameworks = mutableListOf<String>()
        if (siteUrls.any { it.contains("/wp-content/") || it.contains("/wp-json/") || it.contains("admin-ajax.php") }) frameworks += "WordPress"
        if (detail?.isDooPlay == true || logs.any { it.requestBody?.contains("doo_player_ajax") == true }) frameworks += "DooPlay"
        if (siteUrls.any { it.contains("/_next/") }) frameworks += "Next.js"
        if (siteUrls.any { it.contains("/_nuxt/") }) frameworks += "Nuxt"

        val types = detectTypes(siteUrls + listOf(host), detail)

        return SiteAnalysis(
            baseUrl = baseUrl, host = host, providerName = name, language = language, tvTypes = types,
            headers = headers, cookies = cookies, usesCloudflare = cloudflare, search = search,
            listingCards = listingCards, sections = sections, pagination = pagination,
            detail = detail, detailUrl = detailEntry?.key, streams = streams, embeds = embeds, apis = apis,
            frameworks = frameworks, pagesAnalyzed = sitePages.size, notes = notes
        )
    }

    // ------------------------------------------------------------------------- search

    private fun detectSearch(logs: List<NetworkLog>, host: String, pages: Map<String, Document>): SearchInfo? {
        data class Cand(val log: NetworkLog, val param: String?, val value: String, val score: Int, val pathStyle: Boolean)
        val cands = mutableListOf<Cand>()
        for (l in logs) {
            if (l.kind !in setOf(LogKind.DOC, LogKind.XHR, LogKind.DOM, LogKind.OTHER)) continue
            if (l.method != "GET" && l.method != "POST" && l.method != "DOM") continue
            val onSite = HtmlAnalyzer.sameSite(l.url, host)
            val params = queryParams(l.url)
            val path = pathOf(l.url).lowercase()
            val hasSearchPath = path.contains("search") || path.contains("find") || path.contains("filter")
            params.entries.firstOrNull { (k, v) -> k.lowercase() in SEARCH_PARAMS && v.isNotBlank() && v.length < 60 && !v.matches(Regex("\\d+")) }
                ?.let { (k, v) ->
                    var s = 0
                    if (k.lowercase() in STRONG_PARAMS) s += 3
                    if (hasSearchPath) s += 3
                    if (onSite) s += 2
                    if (l.responseBody != null) s += 2
                    if (l.kind == LogKind.XHR) s += 1
                    if (s >= 4) cands += Cand(l, k, v, s, false)
                }
            Regex("/search/([^/?#]+)", RegexOption.IGNORE_CASE).find(l.url)?.let { m ->
                val v = decode(m.groupValues[1])
                if (v.isNotBlank() && v.length < 60) cands += Cand(l, null, v, 6 + (if (onSite) 2 else 0) + (if (l.responseBody != null) 2 else 0), true)
            }
            if (l.method == "POST" && !l.requestBody.isNullOrBlank() && l.requestBody.trimStart().startsWith("{")) {
                val obj = try { com.google.gson.JsonParser.parseString(l.requestBody).asJsonObject } catch (_: Exception) { null }
                obj?.entrySet()?.firstOrNull { (k, v) -> k.lowercase() in SEARCH_PARAMS && v.isJsonPrimitive && v.asJsonPrimitive.isString && v.asString.isNotBlank() }
                    ?.let { (k, v) -> cands += Cand(l, k, v.asString, 6 + (if (onSite) 2 else 0) + (if (hasSearchPath) 3 else 0) + (if (l.responseBody != null) 2 else 0), false) }
            }
            if (l.method == "POST" && !l.requestBody.isNullOrBlank() && !l.requestBody.trimStart().startsWith("{")) {
                val body = formParams(l.requestBody)
                body.entries.firstOrNull { (k, v) -> k.lowercase() in SEARCH_PARAMS && v.isNotBlank() && v.length < 60 }?.let { (k, v) ->
                    cands += Cand(l, k, v, 5 + (if (onSite) 2 else 0) + (if (hasSearchPath || l.requestBody.contains("search", true)) 2 else 0), false)
                }
            }
        }
        // Prefer requests that came back with a body; the most specific/highest score wins.
        val best = cands.maxWithOrNull(compareBy<Cand> { it.score }.thenBy { it.log.responseBody?.length ?: 0 }) ?: return null
        val l = best.log
        val body = l.responseBody ?: pages[norm(l.url)]?.outerHtml()
        val json = JsonShape.analyze(body)
        val cards = if (json == null) (pages[norm(l.url)] ?: body?.takeIf { looksLikeHtml(it) }?.let { HtmlAnalyzer.parse(it, l.url) })
            ?.let { HtmlAnalyzer.detectCards(it, host) } else null

        val extra = l.requestHeaders.filterKeys { k ->
            val lk = k.lowercase()
            lk == "x-requested-with" && l.requestHeaders[k] == "XMLHttpRequest" || (lk.startsWith("x-") && lk != "x-requested-with") || lk == "authorization"
        }

        val formBody: List<Pair<String, String?>>?
        val template: String
        var jsonBody: String? = null
        if (l.method == "POST" && !l.requestBody.isNullOrBlank() && l.requestBody.trimStart().startsWith("{") && !best.pathStyle) {
            formBody = null
            template = KotlinText.esc(l.url)
            val raw = l.requestBody
            val m = Regex("\"" + Regex.escape(best.param ?: "") + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(raw)
            jsonBody = if (m != null) {
                val g = m.groups[1]!!.range
                KotlinText.lit(raw.substring(0, g.first - 1)) + " + query.toJson() + " + KotlinText.lit(raw.substring(g.last + 2))
            } else KotlinText.lit(raw) + " // TODO: put the query into this JSON body"
        } else if (l.method == "POST" && !l.requestBody.isNullOrBlank() && !best.pathStyle) {
            formBody = formParams(l.requestBody).map { (k, v) -> k to (if (k == best.param && v == best.value) null else v) }
            template = KotlinText.esc(l.url)
        } else {
            formBody = null
            template = when {
                best.pathStyle -> {
                    val m = Regex("/search/([^/?#]+)", RegexOption.IGNORE_CASE).find(l.url)!!
                    KotlinText.esc(l.url.substring(0, m.range.first) + "/search/") + "§{query.enc()}" +
                        KotlinText.esc(l.url.substring(m.range.last + 1))
                }
                else -> {
                    // rebuild "?a=1&s=<query>&b=2" keeping other params as captured
                    val base = l.url.substringBefore('?')
                    val raw = l.url.substringAfter('?', "").substringBefore('#')
                    val parts = raw.split('&').filter { it.isNotEmpty() }.map { p ->
                        val k = p.substringBefore('=')
                        if (decode(k) == best.param) KotlinText.esc("$k=") + "§{query.enc()}" else KotlinText.esc(p)
                    }
                    KotlinText.esc(base) + "?" + parts.joinToString("&")
                }
            }
        }
        return SearchInfo(
            sampleUrl = l.url, method = if (l.method == "DOM") "GET" else l.method, query = best.value, urlTemplate = template, formBody = formBody, jsonBody = jsonBody,
            isJson = json != null, json = json, cards = cards, extraHeaders = extra
        )
    }

    // ------------------------------------------------------------------------- helpers

    private fun detectBaseUrl(logs: List<NetworkLog>): String {
        val main = logs.filter { (it.isMainFrame || it.kind == LogKind.DOM) && it.url.startsWith("http") }
        val pool = main.ifEmpty { logs.filter { it.kind == LogKind.XHR || it.kind == LogKind.DOC } }.ifEmpty { logs }
        val counts = pool.mapNotNull { l -> try { URI(l.url).let { u -> if (u.host != null) "${u.scheme}://${u.host}" else null } } catch (_: Exception) { null } }
            .groupingBy { it }.eachCount()
        return counts.maxByOrNull { it.value }?.key ?: "https://example.com"
    }

    fun providerName(host: String): String {
        val parts = host.lowercase().split('.').filter { it.isNotEmpty() }
        val label = parts.dropWhile { it in setOf("www", "m", "ww1", "ww2", "ww3", "ww4", "w1", "web", "v2", "new", "api") }
            .firstOrNull() ?: parts.firstOrNull() ?: "Generated"
        val clean = label.replace(Regex("[^a-z0-9]"), " ").split(' ').filter { it.isNotEmpty() }
            .joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }
        return when {
            clean.isEmpty() -> "Generated"
            clean.first().isDigit() -> "Site$clean"
            else -> clean
        }
    }

    private fun detectTypes(urls: List<String>, detail: DetailSelectors?): List<String> {
        val joined = urls.joinToString(" ").lowercase()
        val types = mutableListOf<String>()
        if (Regex("anime|donghua").containsMatchIn(joined)) types += "Anime"
        if (Regex("movie|film").containsMatchIn(joined) || types.isEmpty()) types += "Movie"
        if (Regex("series|/tv|season|episode|drama|show").containsMatchIn(joined) || detail?.episodes != null) types += "TvSeries"
        if (Regex("drama|kdrama|korean").containsMatchIn(joined)) types += "AsianDrama"
        return types.distinct()
    }

    private fun isPlayable(url: String): Boolean {
        val p = url.substringBefore('?').lowercase()
        return listOf(".m3u8", ".mpd", ".mp4", ".mkv", ".webm", ".m4v").any { p.endsWith(it) } ||
            (p.contains(".m3u8") || p.contains("/hls/") || p.contains("master"))
    }

    fun looksLikeHtml(s: String?): Boolean {
        if (s.isNullOrBlank()) return false
        val head = s.take(600).lowercase()
        return head.contains("<html") || head.contains("<!doctype html") || head.contains("<head") || head.contains("<body")
    }

    fun hostOf(url: String): String? = try { URI(url).host } catch (_: Exception) { null }

    private fun pathOf(url: String): String = try { URI(url).rawPath ?: "" } catch (_: Exception) { url.substringAfter("://").substringAfter('/', "") }

    /** Normalises a URL for comparison: no fragment, no trailing slash. */
    fun norm(url: String): String = url.substringBefore('#').trimEnd('/')

    private fun labelFromPath(url: String): String {
        val seg = pathOf(url).trim('/').split('/').lastOrNull { it.isNotBlank() && !it.matches(Regex("\\d+")) && it != "page" } ?: "Latest"
        return decode(seg).replace('-', ' ').replace('_', ' ').split(' ').filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }.ifBlank { "Latest" }
    }

    private fun queryParams(url: String): Map<String, String> {
        val q = url.substringAfter('?', "").substringBefore('#')
        if (q.isEmpty()) return emptyMap()
        return formParams(q)
    }

    private fun formParams(body: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        body.split('&').forEach { p ->
            if (p.isEmpty()) return@forEach
            out[decode(p.substringBefore('='))] = decode(p.substringAfter('=', ""))
        }
        return out
    }

    private fun decode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
}
