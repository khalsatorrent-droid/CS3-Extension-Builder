package com.khalsatorrent.cs3builder.codegen

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Describes the most "list-like" part of a JSON response: the path to the biggest array of objects,
 * the fields those objects have, and which fields look like title / link / poster / id.
 */
data class JsonShape(
    val path: List<String>,
    val fields: LinkedHashMap<String, String>,   // json key -> Kotlin type
    val itemCount: Int,
    val titleKey: String?,
    val urlKey: String?,
    val posterKey: String?,
    val idKey: String?,
    val typeKey: String?,
    val sample: Map<String, String>               // json key -> sample value (for comments)
) {
    companion object {
        private val TITLE = listOf("title", "name", "title_english", "english_title", "original_title", "original_name", "label", "text")
        private val URL = listOf("url", "link", "href", "permalink", "slug", "path", "uri", "detail_url", "watch_url")
        private val POSTER = listOf("poster", "poster_path", "poster_url", "posterurl", "image", "image_url", "img", "thumbnail", "thumb", "cover", "cover_image", "coverimage", "banner", "backdrop_path", "picture")
        private val ID = listOf("id", "_id", "tmdb_id", "tmdbid", "movie_id", "anime_id", "show_id", "vod_id", "slug")
        private val TYPE = listOf("type", "media_type", "mediatype", "kind", "category", "format")

        fun parse(body: String?): JsonElement? {
            if (body.isNullOrBlank()) return null
            val t = body.trim()
            if (!(t.startsWith("{") || t.startsWith("["))) return null
            return try { JsonParser.parseString(t) } catch (_: Exception) { null }
        }

        fun analyze(body: String?): JsonShape? {
            val root = parse(body) ?: return null
            var best: Pair<List<String>, JsonArray>? = null
            fun visit(el: JsonElement, path: List<String>, depth: Int) {
                if (depth > 5) return
                when {
                    el.isJsonArray -> {
                        val arr = el.asJsonArray
                        val objs = arr.count { it.isJsonObject && it.asJsonObject.size() >= 2 }
                        if (objs > 0 && objs >= arr.size() / 2 && (best == null || arr.size() > best!!.second.size())) best = path to arr
                    }
                    el.isJsonObject -> el.asJsonObject.entrySet().forEach { (k, v) -> visit(v, path + k, depth + 1) }
                }
            }
            visit(root, emptyList(), 0)
            val (path, arr) = best ?: return null
            val fields = LinkedHashMap<String, String>()
            val sample = LinkedHashMap<String, String>()
            arr.take(20).filter { it.isJsonObject }.map { it.asJsonObject }.forEach { o: JsonObject ->
                o.entrySet().forEach { (k, v) ->
                    val t = kotlinType(v)
                    val prev = fields[k]
                    fields[k] = when {
                        prev == null || prev == "Any" -> t
                        prev != t && t != "Any" -> if (setOf(prev, t) == setOf("Int", "Long")) "Long" else if (setOf(prev, t).all { it in setOf("Int", "Long", "Double") }) "Double" else "String"
                        else -> prev
                    }
                    if (k !in sample && v.isJsonPrimitive) sample[k] = v.asJsonPrimitive.asString.take(60)
                }
            }
            if (fields.isEmpty()) return null
            val keys = fields.keys
            fun pick(cands: List<String>, wantString: Boolean = true) = cands.firstNotNullOfOrNull { c ->
                keys.firstOrNull { it.equals(c, true) && (!wantString || fields[it] == "String") }
            }
            val poster = pick(POSTER) ?: keys.firstOrNull { k ->
                fields[k] == "String" && sample[k]?.let { Regex("\\.(jpe?g|png|webp|avif)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true
            }
            return JsonShape(
                path = path,
                fields = fields,
                itemCount = arr.size(),
                titleKey = pick(TITLE),
                urlKey = pick(URL),
                posterKey = poster,
                idKey = pick(ID, wantString = false),
                typeKey = pick(TYPE),
                sample = sample
            )
        }

        private fun kotlinType(v: JsonElement): String = when {
            v.isJsonNull -> "Any"
            v.isJsonArray -> "List<Any>"
            v.isJsonObject -> "Map<String, Any>"
            v.asJsonPrimitive.isBoolean -> "Boolean"
            v.asJsonPrimitive.isNumber -> {
                val s = v.asJsonPrimitive.asString
                when {
                    s.contains('.') || s.contains('e', true) -> "Double"
                    (s.toLongOrNull() ?: 0L) > Int.MAX_VALUE || (s.toLongOrNull() ?: 0L) < Int.MIN_VALUE -> "Long"
                    else -> "Int"
                }
            }
            else -> "String"
        }
    }
}
