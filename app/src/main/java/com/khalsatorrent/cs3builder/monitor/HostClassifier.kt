package com.khalsatorrent.cs3builder.monitor

/** Sorts the hostnames an app looks up into the ones worth reverse engineering first. */
object HostClassifier {

    enum class Kind(val label: String, val rank: Int) {
        API("API", 0), MEDIA("Video / CDN", 1), APP("App", 2), AUTH("Login / config", 3),
        ADS("Ads / analytics", 4), SYSTEM("System", 5)
    }

    private val ADS = listOf(
        "doubleclick", "googlesyndication", "googleadservices", "google-analytics", "googletagmanager", "app-measurement",
        "firebaselogging", "crashlytics", "appsflyer", "adjust.", "branch.io", "facebook.", "fbcdn", "graph.facebook",
        "unityads", "unity3d", "applovin", "applvn", "ironsrc", "ironsource", "vungle", "chartboost", "mopub", "inmobi",
        "pangle", "bytedance", "tiktokv", "mintegral", "rayjump", "admob", "adcolony", "onesignal", "amplitude",
        "mixpanel", "segment.", "sentry.io", "bugsnag", "newrelic", "datadoghq", "clevertap", "moengage", "webengage",
        "singular.net", "kochava", "tapjoy", "liftoff", "yandex", "criteo", "taboola", "outbrain", "smaato", "startapp"
    )
    private val SYSTEM = listOf(
        "googleapis.com", "gstatic.com", "android.com", "google.com", "gvt1.com", "gvt2.com", "play.googleapis",
        "connectivitycheck", "time.android", "pool.ntp.org", "samsung", "xiaomi", "miui", "heytap", "oppo", "vivo",
        "huawei", "hicloud", "apple.com", "icloud.com", "msftconnecttest", "windowsupdate"
    )
    private val AUTH = listOf(
        "firebaseio", "firebaseinstallations", "firebaseremoteconfig", "identitytoolkit", "securetoken",
        "accounts.", "auth.", "login.", "oauth", "sso.", "config.", "remoteconfig", "update.", "version."
    )
    /** CDN / video providers: matched anywhere in the host. */
    private val MEDIA_PROVIDERS = listOf(
        "akamai", "cloudfront", "fastly", "bunnycdn", "b-cdn", "llnwd", "edgecast", "byteplus", "volces", "bytecdn",
        "ksyun", "aliyuncs", "myqcloud", "qcloud", "wasabisys", "backblaze", "cdn77", "jwpcdn", "mux.com", "brightcove"
    )
    /** Words matched only against subdomain labels, so "dashreels.com" is not mistaken for "dash". */
    private val MEDIA_WORDS = setOf(
        "cdn", "video", "videos", "vod", "stream", "streams", "media", "hls", "dash", "play", "playback", "storage",
        "static", "img", "image", "images", "pic", "pics", "assets", "s3", "files", "content", "edge", "cover", "thumb"
    )
    private val API_WORDS = setOf("api", "apis", "gateway", "gw", "service", "services", "backend", "graphql", "rpc", "server", "mobile", "app", "h5", "m")

    fun classify(host: String, appPackage: String? = null): Kind {
        val h = host.lowercase().trimEnd('.')
        if (ADS.any { h.contains(it) }) return Kind.ADS
        if (SYSTEM.any { h.contains(it) } && !appMatch(h, appPackage)) return Kind.SYSTEM
        if (AUTH.any { h.contains(it) }) return Kind.AUTH
        val labels = h.split('.')
        val words = labels.dropLast(2).flatMap { it.split('-', '_') }.map { it.trimEnd { c -> c.isDigit() } }
        if (words.any { it in API_WORDS || it.startsWith("api") }) return Kind.API
        if (MEDIA_PROVIDERS.any { h.contains(it) } || words.any { it in MEDIA_WORDS }) return Kind.MEDIA
        return Kind.APP
    }

    /** True when the host shares a word with the app's package name (com.dashreels.app → dashreels). */
    fun appMatch(host: String, appPackage: String?): Boolean {
        appPackage ?: return false
        val words = appPackage.lowercase().split('.', '_').filter { it.length >= 4 && it !in setOf("com", "android", "mobile", "app", "apps", "video", "tv") }
        return words.any { host.contains(it) }
    }
}
