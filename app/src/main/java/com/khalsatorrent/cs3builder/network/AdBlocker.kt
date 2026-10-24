package com.khalsatorrent.cs3builder.network

import android.net.Uri

/** Host-based ad / popup / redirect blocker used when "Block ads" is on. */
object AdBlocker {

    private val AD_HOSTS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "adnxs.com", "moatads.com", "adsafeprotected.com", "advertising.com", "amazon-adsystem.com",
        "outbrain.com", "taboola.com", "revcontent.com", "mgid.com", "criteo.com", "pubmatic.com",
        "popads.net", "popcash.net", "propellerads.com", "trafficjunky.com", "trafficjunky.net",
        "exoclick.com", "exosrv.com", "adsterra.com", "adsterratech.com", "hilltopads.net", "juicyads.com",
        "clicksor.com", "yllix.com", "plugrush.com", "traffichaus.com", "go2url.net", "redirectvoluum.com",
        "earnhub.net", "clkads.com", "onclick.io", "onclkds.com", "onclickads.net", "adf.ly", "bc.vc",
        "linkbucks.com", "shorte.st", "adfoc.us", "adcash.com", "a-ads.com", "realsrv.com", "bidgear.com",
        "highperformanceformat.com", "profitablegatecpm.com", "displayvertising.com", "adskeeper.com",
        "monetag.com", "galaksion.com", "clickadu.com", "zeydoo.com", "richpartners.co", "pushance.com",
        "dolohen.com", "acint.net", "betterads.co", "tsyndicate.com", "magsrv.com", "ad-maven.com", "admaven.com"
    )

    private val REDIRECT_HINTS = listOf("/redirect", "click?", "/go/", "track", "aff_", "affiliate", "smartlink", "offer", "?zoneid=", "landing")

    fun isAd(url: String): Boolean {
        val host = try { Uri.parse(url).host?.lowercase() } catch (_: Exception) { null } ?: return false
        return AD_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    fun looksLikeAdRedirect(url: String): Boolean {
        val lower = url.lowercase()
        return isAd(url) || REDIRECT_HINTS.any { lower.contains(it) }
    }
}
