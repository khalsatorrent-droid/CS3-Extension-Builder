package com.khalsatorrent.cs3builder.utils

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("cs3builder", Context.MODE_PRIVATE)

    var deepCapture: Boolean
        get() = sp.getBoolean("deep_capture", true)
        set(v) = sp.edit().putBoolean("deep_capture", v).apply()

    var domSnapshots: Boolean
        get() = sp.getBoolean("dom_snapshots", true)
        set(v) = sp.edit().putBoolean("dom_snapshots", v).apply()

    var adBlock: Boolean
        get() = sp.getBoolean("ad_block", false)
        set(v) = sp.edit().putBoolean("ad_block", v).apply()

    var desktopMode: Boolean
        get() = sp.getBoolean("desktop_mode", false)
        set(v) = sp.edit().putBoolean("desktop_mode", v).apply()

    /** Capture javascript bodies - needed to read an api key out of a site's bundle. */
    var captureScripts: Boolean
        get() = sp.getBoolean("capture_scripts", false)
        set(value) = sp.edit().putBoolean("capture_scripts", value).apply()

    /** Keep whole response bodies instead of the old per-kind caps. */
    var fullBodies: Boolean
        get() = sp.getBoolean("full_bodies", true)
        set(value) = sp.edit().putBoolean("full_bodies", value).apply()

    var hideNoise: Boolean
        get() = sp.getBoolean("hide_noise", true)
        set(v) = sp.edit().putBoolean("hide_noise", v).apply()

    var authorName: String
        get() = sp.getString("author_name", "") ?: ""
        set(v) = sp.edit().putString("author_name", v).apply()

    var lastUrl: String?
        get() = sp.getString("last_url", null)
        set(v) = sp.edit().putString("last_url", v).apply()

    /** Most-recent-first list of sites (origins) visited. */
    val recentSites: List<String>
        get() = sp.getString("recent_sites", "")!!.split('\n').filter { it.isNotBlank() }

    fun addRecentSite(url: String) {
        val origin = try {
            val u = android.net.Uri.parse(url)
            if (u.scheme == null || u.host == null) return
            "${u.scheme}://${u.host}"
        } catch (_: Exception) { return }
        val list = (listOf(origin) + recentSites.filter { it != origin }).take(12)
        sp.edit().putString("recent_sites", list.joinToString("\n")).apply()
    }

    fun clearRecentSites() = sp.edit().remove("recent_sites").apply()

    /** "mobile", "desktop", "iphone", "tv" or "custom". Old "Desktop site" toggle maps onto it. */
    var uaPreset: String
        get() = sp.getString("ua_preset", null) ?: if (desktopMode) "desktop" else "mobile"
        set(v) { sp.edit().putString("ua_preset", v).apply(); desktopMode = v == "desktop" }

    var customUa: String
        get() = sp.getString("custom_ua", "") ?: ""
        set(v) = sp.edit().putString("custom_ua", v).apply()

    var lastConsoleCode: String
        get() = sp.getString("console_code", "document.title") ?: "document.title"
        set(v) = sp.edit().putString("console_code", v).apply()

    /** Selectors picked in the browser, per site. */
    fun loadSelectorHints() {
        val raw = sp.getString("selector_hints", null) ?: return
        try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, Map<String, com.khalsatorrent.cs3builder.codegen.SelectorHints.Hint>>>() {}.type
            val parsed: Map<String, Map<String, com.khalsatorrent.cs3builder.codegen.SelectorHints.Hint>> =
                com.google.gson.Gson().fromJson(raw, type) ?: return
            com.khalsatorrent.cs3builder.codegen.SelectorHints.load(parsed.mapValues { (_, m) ->
                m.mapNotNull { (k, v) ->
                    runCatching { com.khalsatorrent.cs3builder.codegen.SelectorHints.Role.valueOf(k) }.getOrNull()?.let { it to v }
                }.toMap()
            })
        } catch (_: Exception) { }
    }

    fun saveSelectorHints() {
        val data = com.khalsatorrent.cs3builder.codegen.SelectorHints.all().mapValues { (_, m) -> m.mapKeys { it.key.name } }
        sp.edit().putString("selector_hints", com.google.gson.Gson().toJson(data)).apply()
    }
}
