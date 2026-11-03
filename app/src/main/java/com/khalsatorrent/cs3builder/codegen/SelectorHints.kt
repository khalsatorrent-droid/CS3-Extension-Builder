package com.khalsatorrent.cs3builder.codegen

import java.util.concurrent.ConcurrentHashMap

/**
 * CSS selectors the user picked by tapping elements in the browser. They override what the
 * analyzer guessed for that site. Pure Kotlin: the app persists them (see Prefs) and loads them in.
 */
object SelectorHints {

    enum class Role(val label: String) {
        CARD("Listing card (one movie/show tile)"),
        TITLE("Title inside a card"),
        POSTER("Poster image inside a card"),
        EPISODES("Episode links"),
        DETAIL_TITLE("Title on the detail page"),
        PLOT("Description / plot"),
        TAGS("Genres / tags")
    }

    data class Hint(val selector: String, val attr: String? = null)

    private val byHost = ConcurrentHashMap<String, MutableMap<Role, Hint>>()

    fun key(host: String) = host.lowercase().removePrefix("www.").removePrefix("m.")

    fun set(host: String, role: Role, hint: Hint) {
        byHost.getOrPut(key(host)) { ConcurrentHashMap() }[role] = hint
    }

    fun get(host: String): Map<Role, Hint> = byHost[key(host)].orEmpty()

    fun clear(host: String) { byHost.remove(key(host)) }

    fun all(): Map<String, Map<Role, Hint>> = byHost

    fun load(data: Map<String, Map<Role, Hint>>) {
        byHost.clear()
        data.forEach { (h, m) -> byHost[h] = ConcurrentHashMap(m) }
    }

    /**
     * A full-page selector made relative to a card: "div.card h3.title" inside "div.card" → "h3.title".
     * Falls back to the last compound selector.
     */
    fun relativeTo(card: String, selector: String): String {
        val s = selector.trim()
        val c = card.trim()
        if (s.startsWith("$c ")) return s.removePrefix(c).trim().removePrefix(">").trim()
        if (s.startsWith("$c>")) return s.removePrefix("$c>").trim()
        return s.split(Regex("\\s*>\\s*|\\s+")).lastOrNull { it.isNotBlank() } ?: s
    }

    /** Applies the user's picks on top of what [SiteAnalyzer] found. */
    fun apply(host: String, cards: CardSelectors?, detail: DetailSelectors?): Pair<CardSelectors?, DetailSelectors?> {
        val h = get(host)
        if (h.isEmpty()) return cards to detail
        var c = cards
        h[Role.CARD]?.let { card ->
            c = (c ?: CardSelectors(card.selector, 0, false, null, null, false, null, emptyList(), emptyList()))
                .copy(container = card.selector)
        }
        h[Role.TITLE]?.let { t ->
            val base = c ?: CardSelectors(GENERIC_CARD, 0, false, null, null, false, null, emptyList(), emptyList())
            c = base.copy(titleSelector = relativeTo(base.container, t.selector), titleAttr = null, titleFromImgAlt = false)
        }
        h[Role.POSTER]?.let { p ->
            val base = c ?: CardSelectors(GENERIC_CARD, 0, false, null, null, false, null, emptyList(), emptyList())
            c = base.copy(posterAttr = p.attr ?: base.posterAttr)
        }
        var d = detail
        val blank = DetailSelectors(null, false, false, null, false, null, null, null, null, 0, false, null, null, null)
        h[Role.DETAIL_TITLE]?.let { d = (d ?: blank).copy(title = it.selector) }
        h[Role.PLOT]?.let { d = (d ?: blank).copy(plotSelector = it.selector) }
        h[Role.TAGS]?.let { d = (d ?: blank).copy(tagsSelector = it.selector) }
        h[Role.EPISODES]?.let { e -> d = (d ?: blank).copy(episodes = EpisodeSelectors(e.selector, 0, listOf("(picked in browser)"))) }
        return c to d
    }

    private const val GENERIC_CARD = "article, .item, .movie, .post, .card"
}
