package com.khalsatorrent.cs3builder.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView

/**
 * Plays a captured media url with ExoPlayer.
 *
 * The WebView cannot decode HLS at all - handing a .m3u8 to it shows nothing, which is why players
 * like lulustream/luluvdo look broken here even though the stream is fine. This activity plays the
 * url straight from the log with the headers the CDN actually checks.
 *
 * Referer and Origin matter on these hosts (cdn-tnmr.org, bxcdn, bkcdn all answer 403 without
 * them), and both are taken from the captured request, so what plays here is what the browser
 * played. HLS gets an HlsMediaSource, everything else a ProgressiveMediaSource, which covers the
 * direct .mp4 files these sites serve alongside the playlists.
 */
class PlayerActivity : Activity() {

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_REFERER = "referer"
        private const val EXTRA_HEADERS = "headers"

        private const val FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        fun start(
            context: Context,
            url: String,
            referer: String? = null,
            headers: Map<String, String> = emptyMap()
        ) {
            val intent = Intent(context, PlayerActivity::class.java)
            intent.putExtra(EXTRA_URL, url)
            intent.putExtra(EXTRA_REFERER, referer)
            if (headers.isNotEmpty()) {
                intent.putExtra(EXTRA_HEADERS, HashMap(headers))
            }
            context.startActivity(intent)
        }
    }

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        val referer = intent.getStringExtra(EXTRA_REFERER)
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        val captured = (intent.getSerializableExtra(EXTRA_HEADERS) as? HashMap<String, String>).orEmpty()

        val requestHeaders = HashMap<String, String>()
        captured.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank()) requestHeaders[k] = v
        }
        if (!referer.isNullOrBlank() && requestHeaders.keys.none { it.equals("Referer", true) }) {
            requestHeaders["Referer"] = referer
        }
        // Many of these CDNs check Origin as well as Referer; derive it when the capture had none.
        if (requestHeaders.keys.none { it.equals("Origin", true) }) {
            requestHeaders["Referer"]?.let { ref ->
                Regex("""^(https?://[^/]+)""").find(ref)?.groupValues?.get(1)?.let {
                    requestHeaders["Origin"] = it
                }
            }
        }
        val userAgent = requestHeaders.entries
            .firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: FALLBACK_UA
        requestHeaders["User-Agent"] = userAgent

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(requestHeaders)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)

        val exo = ExoPlayer.Builder(this).build()
        player = exo

        val mediaItem = MediaItem.fromUri(url)
        val source = if (isHls(url)) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        }

        val view = PlayerView(this).apply {
            this.player = exo
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setKeepScreenOn(true)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        setContentView(view)
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun isHls(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".m3u8") || u.contains("/hls/") || u.contains("mpegurl") || u.contains(".m3u")
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
