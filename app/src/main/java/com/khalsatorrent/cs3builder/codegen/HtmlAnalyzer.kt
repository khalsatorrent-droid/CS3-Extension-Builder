package com.khalsatorrent.cs3builder.codegen

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Repeating "card" structure on a listing / search page. */
data class CardSelectors(
    val container: String,
    val count: Int,
    val containerIsLink: Boolean,
    val titleSelector: String?,     // CSS inside the card whose text is the title
    val titleAttr: String?,         // otherwise: attribute on the link ("title") or img ("alt")
    val titleFromImgAlt: Boolean,
    val posterAttr: String?,        // attribute of the card's <img> holding the poster
    val hrefs: List<String>,
    val sampleTitles: List<String>
)

data class EpisodeSelectors(val selector: String, val count: Int, val sample: List<String>)

data class DetailSelectors(
    val title: String?,
    val hasOgTitle: Boolean,
    val hasOgImage: Boolean,
    val posterSelector: String?,
    val hasOgDescription: Boolean,
    val plotSelector: String?,
    val tagsSelector: String?,
    val episodes: EpisodeSelectors?,
    val iframeAttr: String?,
    val iframeCount: Int,
    val isDooPlay: Boolean,
    val serverSelector: String?,
    val serverAttr: String?,
    val recommendations: CardSelectors?
)

object HtmlAnalyzer {

    private val SKIP_TAGS = setOf("html", "body", "main", "header", "footer", "nav", "form", "table", "tbody", "thead", "select", "option", "script", "style", "noscript")
    private val BAD_CLASS = Regex("^(active|current|selected|show|hide|hidden|open|clearfix|row|col|col-.*|d-.*|p-.*|m-.*|mb-.*|mt-.*|lazy|lazyload(ed)?|loaded|swiper-slide-.*|slick-.*|owl-.*|js-.*)$")
    private val VALID_CLASS = Regex("^-?[_a-zA-Z][_a-zA-Z0-9-]{0,40}$")
    private val EPISODE_RX = Regex("(episode|/ep[-/_]?\\d|[?&]ep=|-ep-|/eps?/|season|/watch/.+\\d)", RegexOption.IGNORE_CASE)

    fun parse(html: String, baseUrl: String): Document? = try {
        Jsoup.parse(if (html.length > 1_500_000) html.substring(0, 1_500_000) else html, baseUrl)
    } catch (_: Exception) { null }

    fun language(doc: Document): String? =
        doc.selectFirst("html[lang]")?.attr("lang")?.take(2)?.lowercase()?.takeIf { it.matches(Regex("[a-z]{2}")) }

    // ---------------------------------------------------------------- selectors

    private fun cleanClasses(el: Element): List<String> =
        el.classNames().filter { VALID_CLASS.matches(it) && !BAD_CLASS.matches(it) && it.count { c -> c.isDigit() } <= 3 }

    /** "tag.class1.class2" or "parent.class > tag"; null if nothing stable to anchor on. */
    fun signature(el: Element): String? {
        val classes = cleanClasses(el).take(2)
        if (classes.isNotEmpty()) return el.tagName() + classes.joinToString("") { ".$it" }
        val parent = el.parent() ?: return null
        val pClasses = cleanClasses(parent).take(2)
        if (pClasses.isNotEmpty()) return parent.tagName() + pClasses.joinToString("") { ".$it" } + " > " + el.tagName()
        if (parent.id().isNotBlank() && VALID_CLASS.matches(parent.id())) return "#${parent.id()} > ${el.tagName()}"
        return null
    }

    // ---------------------------------------------------------------- cards

    fun detectCards(doc: Document, host: String): CardSelectors? {
        val body = doc.body() ?: return null
        val groups = LinkedHashMap<String, MutableList<Element>>()
        for (el in body.allElements) {
            if (el.tagName() in SKIP_TAGS) continue
            val link = if (el.tagName() == "a" && el.hasAttr("href")) el else el.selectFirst("a[href]") ?: continue
            el.selectFirst("img") ?: continue
            if (el.select("a[href]").size > 4) continue
            if (el.text().length > 400) continue
            val href = link.absUrl("href")
            if (href.isBlank() || !href.startsWith("http")) continue
            val sig = signature(el) ?: continue
            groups.getOrPut(sig) { mutableListOf() }.add(el)
        }
        var best: CardSelectors? = null
        var bestScore = -1.0
        for ((sig, els) in groups) {
            if (els.size < 3) continue
            val hrefs = els.mapNotNull { e -> linkOf(e)?.absUrl("href") }
            val sameHost = hrefs.count { sameSite(it, host) }
            if (sameHost < hrefs.size * 0.5) continue
            if (hrefs.distinct().size < els.size * 0.6) continue
            val cs = describeCards(sig, els) ?: continue
            val avgText = els.map { it.text().length }.average()
            val score = els.size * 10.0 + (if (cs.titleSelector != null) 15 else 0) + minOf(avgText, 80.0) / 8 +
                (if (cs.posterAttr != null) 5 else 0)
            if (score > bestScore) { bestScore = score; best = cs }
        }
        return best
    }

    private fun linkOf(card: Element): Element? =
        if (card.tagName() == "a" && card.hasAttr("href")) card else card.selectFirst("a[href]")

    private fun describeCards(sig: String, els: List<Element>): CardSelectors? {
        val n = els.size.toDouble()
        val titleCands = listOf("h1", "h2", "h3", "h4", "h5", "h6", ".title", "[class*=title]", ".name", "[class*=name]", ".film-name", ".entry-title")
        val titleSel = titleCands.firstOrNull { sel -> els.count { it.selectFirst(sel)?.text()?.isNotBlank() == true } >= n * 0.7 }
        val titleAttr = if (titleSel == null && els.count { linkOf(it)?.attr("title")?.isNotBlank() == true } >= n * 0.7) "title" else null
        val fromAlt = titleSel == null && titleAttr == null &&
            els.count { it.selectFirst("img")?.attr("alt")?.isNotBlank() == true } >= n * 0.7
        val imgs = els.mapNotNull { it.selectFirst("img") }
        val posterAttr = listOf("data-src", "data-lazy-src", "data-original", "data-lazy", "data-srcset", "src").firstOrNull { a ->
            imgs.count { i -> i.attr(a).let { v -> v.isNotBlank() && !v.startsWith("data:") } } >= imgs.size * 0.6
        }
        val titles = els.take(4).mapNotNull { e ->
            when {
                titleSel != null -> e.selectFirst(titleSel)?.text()
                titleAttr != null -> linkOf(e)?.attr("title")
                fromAlt -> e.selectFirst("img")?.attr("alt")
                else -> e.text().take(60)
            }?.trim()
        }
        return CardSelectors(
            container = sig,
            count = els.size,
            containerIsLink = els.first().tagName() == "a",
            titleSelector = titleSel,
            titleAttr = titleAttr,
            titleFromImgAlt = fromAlt,
            posterAttr = posterAttr,
            hrefs = els.mapNotNull { linkOf(it)?.absUrl("href") }.distinct(),
            sampleTitles = titles
        )
    }

    // ---------------------------------------------------------------- detail page

    fun detectDetail(doc: Document, host: String): DetailSelectors {
        fun has(sel: String) = doc.selectFirst(sel) != null
        val h1 = doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
        val titleSel = when {
            h1 != null -> "h1"
            has(".entry-title") -> ".entry-title"
            has("[itemprop=name]") -> "[itemprop=name]"
            else -> null
        }
        val posterSel = listOf(".poster img", "[class*=poster] img", ".thumb img", ".cover img", "[itemprop=image]", ".film-poster img")
            .firstOrNull { has(it) }
        val plotSel = listOf("[itemprop=description]", ".description", "[class*=synopsis]", "[class*=description]", "[class*=plot]", "[class*=overview]", "[class*=storyline]", ".wp-content p", ".entry-content p")
            .firstOrNull { sel -> doc.select(sel).text().length > 40 }
        val tagsSel = listOf("a[rel=tag]", "[class*=genre] a", "a[href*=/genre/]", "a[href*=/genres/]", "[class*=categor] a")
            .firstOrNull { has(it) }
        val iframes = doc.select("iframe")
        val iframeAttr = when {
            iframes.any { it.attr("data-src").startsWith("http") || it.attr("data-src").startsWith("//") } -> "data-src"
            iframes.any { it.attr("src").let { s -> s.startsWith("http") || s.startsWith("//") } } -> "src"
            else -> null
        }
        val dooPlay = has("li.dooplay_player_option") || has("[data-post][data-nume]")
        val serverAttrs = listOf("data-embed", "data-link", "data-video", "data-url", "data-src", "data-hash")
        var serverSel: String? = null
        var serverAttr: String? = null
        for (a in serverAttrs) {
            val els = doc.select("[$a]").filter { it.tagName() != "img" && it.tagName() != "iframe" && it.tagName() != "script" }
            if (els.size >= 1) {
                val sig = signature(els.first()) ?: continue
                serverSel = "$sig[$a]"; serverAttr = a; break
            }
        }
        return DetailSelectors(
            title = titleSel,
            hasOgTitle = has("meta[property=og:title]"),
            hasOgImage = has("meta[property=og:image]"),
            posterSelector = posterSel,
            hasOgDescription = has("meta[property=og:description]") || has("meta[name=description]"),
            plotSelector = plotSel,
            tagsSelector = tagsSel,
            episodes = detectEpisodes(doc, host),
            iframeAttr = iframeAttr,
            iframeCount = iframes.size,
            isDooPlay = dooPlay,
            serverSelector = serverSel,
            serverAttr = serverAttr,
            recommendations = null
        )
    }

    fun detectEpisodes(doc: Document, host: String): EpisodeSelectors? {
        val groups = LinkedHashMap<String, MutableList<Element>>()
        for (a in doc.select("a[href]")) {
            val href = a.absUrl("href")
            if (!sameSite(href, host)) continue
            if (!EPISODE_RX.containsMatchIn(href) && !Regex("^\\s*(ep(isode)?\\.?\\s*)?\\d{1,4}\\b", RegexOption.IGNORE_CASE).containsMatchIn(a.text())) continue
            val sig = signature(a) ?: a.parent()?.let { p -> signature(p)?.let { "$it a" } } ?: continue
            groups.getOrPut(sig) { mutableListOf() }.add(a)
        }
        val (sel, els) = groups.entries.filter { it.value.size >= 2 }
            .maxByOrNull { e -> e.value.size + e.value.count { EPISODE_RX.containsMatchIn(it.absUrl("href")) } }
            ?.let { it.key to it.value } ?: return null
        return EpisodeSelectors(sel, els.size, els.take(3).map { it.text().trim().take(40) })
    }

    // ---------------------------------------------------------------- home sections

    /** Category / listing links from the site navigation, as (absolute url, label). */
    fun navSections(doc: Document, host: String): List<Pair<String, String>> {
        val skip = Regex("(login|register|sign|account|contact|dmca|privacy|terms|about|faq|request|donat|telegram|discord|facebook|twitter|app|download|schedule|random|#)", RegexOption.IGNORE_CASE)
        val out = LinkedHashMap<String, String>()
        val links = doc.select("nav a[href], header a[href], [class*=menu] a[href], [class*=nav] a[href], [id*=menu] a[href]")
        for (a in links) {
            val href = a.absUrl("href").substringBefore('#')
            val text = a.text().trim()
            if (text.length !in 2..24 || !sameSite(href, host)) continue
            val path = try { java.net.URI(href).path.orEmpty() } catch (_: Exception) { "" }
            val segs = path.trim('/').split('/').filter { it.isNotEmpty() }
            if (segs.isEmpty() || segs.size > 3) continue
            if (skip.containsMatchIn(href) || skip.containsMatchIn(text)) continue
            if (href !in out) out[href] = text
            if (out.size >= 10) break
        }
        return out.entries.map { it.key to it.value }
    }

    fun sameSite(url: String, host: String): Boolean {
        val h = try { java.net.URI(url).host?.removePrefix("www.") } catch (_: Exception) { null } ?: return false
        val base = host.removePrefix("www.")
        return h == base || h.endsWith(".$base") || base.endsWith(".$h")
    }
}
