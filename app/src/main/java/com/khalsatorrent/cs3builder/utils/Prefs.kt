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
}
