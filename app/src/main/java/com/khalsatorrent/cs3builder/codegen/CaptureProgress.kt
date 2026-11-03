package com.khalsatorrent.cs3builder.codegen

import com.khalsatorrent.cs3builder.model.LogKind
import com.khalsatorrent.cs3builder.model.NetworkLog
import java.net.URI

/**
 * Cheap, live check of which steps of a good capture are done, so the browser can tell you what
 * is still missing before you generate: home page → search → a title → playback.
 */
object CaptureProgress {

    data class Step(val name: String, val done: Boolean, val hint: String, val evidence: String?)

    data class State(val host: String?, val steps: List<Step>) {
        val doneCount get() = steps.count { it.done }
        val complete get() = steps.all { it.done }
        fun compact() = steps.joinToString(" ") { (if (it.done) "✓" else "○") + it.name }
    }

    private val SEARCH = Regex("([?&](s|q|query|keyword|keywords|search|term|wd|k)=[^&]+)|/search[/?]|/search$", RegexOption.IGNORE_CASE)
    private val DETAIL_HINT = Regex("/(movie|movies|film|series|tv|show|drama|anime|title|detail|watch|video|episode|ep|v|play|vod|dramas)[/-]", RegexOption.IGNORE_CASE)
    private val LISTING = Regex("/(page|genre|genres|category|categories|tag|country|year|filter|browse|list|latest|popular|trending)(/|$|\\?)", RegexOption.IGNORE_CASE)

    fun compute(logs: List<NetworkLog>): State {
        val pages = logs.filter { (it.isMainFrame && it.kind == LogKind.DOC) || it.kind == LogKind.DOM }
        val host = pages.mapNotNull { hostOf(it.url) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        fun onSite(u: String) = host != null && hostOf(u)?.let { it == host || it.endsWith(".$host") || host.endsWith(".$it") } == true

        val sitePages = pages.filter { onSite(it.url) }
        val home = sitePages.firstOrNull { pathOf(it.url).trim('/').isEmpty() }

        val search = logs.firstOrNull { l ->
            onSite(l.url) && (SEARCH.containsMatchIn(l.url) || (l.method == "POST" && l.requestBody?.let { b -> SEARCH.containsMatchIn("?$b") } == true))
        }

        val detail = sitePages.firstOrNull { p ->
            val path = pathOf(p.url)
            path.trim('/').isNotEmpty() && !SEARCH.containsMatchIn(p.url) && !LISTING.containsMatchIn(path) &&
                (DETAIL_HINT.containsMatchIn("$path/") || path.trim('/').count { it == '/' } >= 1 || path.length > 18)
        }

        val play = logs.firstOrNull { l ->
            (l.kind == LogKind.STREAM || l.kind == LogKind.MEDIA) && l.responseCode != 403 &&
                l.url.substringBefore('?').lowercase().let { p -> !p.endsWith(".ts") && !p.endsWith(".m4s") && !p.endsWith(".vtt") && !p.endsWith(".srt") }
        }

        return State(
            host,
            listOf(
                Step("Home", home != null, "Open the site's home page", home?.url),
                Step("Search", search != null, "Search for a title using the site's search box", search?.url),
                Step("Title", detail != null, "Open one movie/show page from the list", detail?.url),
                Step("Play", play != null, "Press play and let the video actually start", play?.url)
            )
        )
    }

    private fun hostOf(u: String) = try { URI(u).host?.lowercase()?.removePrefix("www.") } catch (_: Exception) { null }
    private fun pathOf(u: String) = try { URI(u).rawPath ?: "" } catch (_: Exception) { "" }
}
