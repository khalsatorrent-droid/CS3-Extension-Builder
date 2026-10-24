package com.khalsatorrent.cs3builder.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.chip.Chip
import com.khalsatorrent.cs3builder.MainActivity
import com.khalsatorrent.cs3builder.R
import com.khalsatorrent.cs3builder.databinding.FragmentBrowserBinding
import com.khalsatorrent.cs3builder.model.LogKind
import com.khalsatorrent.cs3builder.model.NetworkLog
import com.khalsatorrent.cs3builder.network.CaptureScripts
import com.khalsatorrent.cs3builder.network.LogRepository
import com.khalsatorrent.cs3builder.network.LoggingWebViewClient
import com.khalsatorrent.cs3builder.utils.Prefs
import com.khalsatorrent.cs3builder.utils.ShareUtils
import java.util.UUID

class BrowserFragment : Fragment() {

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: Prefs
    private lateinit var loggingClient: LoggingWebViewClient
    private var documentStartHook = false
    private var mobileUserAgent = ""

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        setupWebView()
        setupControls()
        observeLogs()
        renderRecents()
        updateModes()
        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
            if (binding.webView.url != null) binding.startPanel.visibility = View.GONE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.webView?.saveState(outState)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true) // inspect via chrome://inspect when on USB
        val webView = binding.webView
        mobileUserAgent = WebSettings.getDefaultUserAgent(requireContext())
            .replace("; wv)", ")").replace(Regex("Version/\\d+\\.\\d+ "), "")

        loggingClient = LoggingWebViewClient(
            packageName = requireContext().packageName,
            settings = object : LoggingWebViewClient.Settings {
                override val deepCapture get() = prefs.deepCapture
                override val adBlock get() = prefs.adBlock
                override val domSnapshots get() = prefs.domSnapshots
                override val userAgent get() = currentUserAgent()
                override val documentStartHook get() = this@BrowserFragment.documentStartHook
            },
            callbacks = object : LoggingWebViewClient.Callbacks {
                override fun onPageStarted(url: String) {
                    activity?.runOnUiThread {
                        val b = _binding ?: return@runOnUiThread
                        if (!b.etUrl.hasFocus()) b.etUrl.setText(url)
                        b.startPanel.visibility = View.GONE
                        b.btnBack.isEnabled = b.webView.canGoBack()
                    }
                }
                override fun onPageFinished(url: String) {
                    activity?.runOnUiThread {
                        val b = _binding ?: return@runOnUiThread
                        if (!b.etUrl.hasFocus()) b.etUrl.setText(url)
                        b.btnBack.isEnabled = b.webView.canGoBack()
                        if (url.startsWith("http")) {
                            prefs.lastUrl = url
                            prefs.addRecentSite(url)
                        }
                    }
                }
            }
        )

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true) // embedded players need these

        webView.webViewClient = loggingClient
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                val b = _binding ?: return
                b.progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
                b.progressBar.setProgressCompat(newProgress, true)
            }
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) = enterFullscreen(view, callback)
            override fun onHideCustomView() = exitFullscreen()
            // window.open / target=_blank: load in the same view unless the ad blocker is on
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                if (prefs.adBlock || !isUserGesture || view == null) return false
                val href = view.hitTestResult.extra
                if (href != null && href.startsWith("http")) { view.loadUrl(href); }
                return false
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            userAgentString = currentUserAgent()
        }
        webView.addJavascriptInterface(loggingClient.JSBridge(), "CS3Bridge")

        // Inject the capture hook before any page script runs, in every frame (iframes too)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, CaptureScripts.HOOK, setOf("*"))
                documentStartHook = true
            } catch (_: Exception) { documentStartHook = false }
        }

        // Direct download links are often the real media URL — log them
        webView.setDownloadListener { url, userAgent, _, mimetype, contentLength ->
            LogRepository.addLog(
                NetworkLog(
                    id = UUID.randomUUID().toString(), timestamp = System.currentTimeMillis(), method = "GET",
                    url = url, requestHeaders = mapOf("User-Agent" to userAgent), requestBody = null,
                    responseCode = 0, responseHeaders = emptyMap(), responseBody = "[download — ${mimetype ?: "unknown"}, $contentLength bytes]",
                    cookies = emptyList(), mimeType = mimetype, isXHR = false, duration = 0,
                    source = "webview", kind = LogKind.classify(url, mimetype).let { if (it == LogKind.OTHER) LogKind.MEDIA else it },
                    pageUrl = _binding?.webView?.url
                )
            )
            Toast.makeText(requireContext(), "Download link captured in Logs", Toast.LENGTH_SHORT).show()
        }

        binding.btnBack.isEnabled = false
    }

    private fun currentUserAgent(): String =
        if (prefs.desktopMode) DESKTOP_UA else mobileUserAgent.ifBlank { MOBILE_UA }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { if (binding.webView.canGoBack()) binding.webView.goBack() }
        binding.btnReload.setOnClickListener {
            if (binding.webView.url == null) loadUrl() else binding.webView.reload()
        }
        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) { loadUrl(); true } else false
        }
        binding.btnViewLogs.setOnClickListener { (activity as? MainActivity)?.navigateToLogs() }
        binding.btnMenu.setOnClickListener { showMenu(it) }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        val m = popup.menu
        m.add(0, M_DEEP, 0, "Deep capture (status, headers, bodies)").setCheckable(true).isChecked = prefs.deepCapture
        m.add(0, M_DOM, 1, "Save rendered page HTML").setCheckable(true).isChecked = prefs.domSnapshots
        m.add(0, M_ADS, 2, "Block ads & popups").setCheckable(true).isChecked = prefs.adBlock
        m.add(0, M_DESKTOP, 3, "Desktop site").setCheckable(true).isChecked = prefs.desktopMode
        m.add(0, M_PAUSE, 4, "Pause capture").setCheckable(true).isChecked = LogRepository.isPaused
        val recents = prefs.recentSites
        if (recents.isNotEmpty()) {
            val sub = m.addSubMenu(1, M_RECENT, 5, "Recent sites")
            recents.forEachIndexed { i, site -> sub.add(2, M_RECENT_BASE + i, i, site) }
        }
        m.add(1, M_SNAPSHOT, 6, "Snapshot this page now")
        m.add(1, M_COPY_URL, 7, "Copy page URL")
        m.add(1, M_COOKIES, 8, "Show cookies for this site")
        m.add(1, M_CLEAR_LOGS, 9, "Clear captured logs")
        m.add(1, M_CLEAR_DATA, 10, "Clear cookies & site data")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                M_DEEP -> { prefs.deepCapture = !prefs.deepCapture; updateModes()
                    toast(if (prefs.deepCapture) "Deep capture on — reload the page" else "Deep capture off (use if a site breaks)") }
                M_DOM -> { prefs.domSnapshots = !prefs.domSnapshots; updateModes() }
                M_ADS -> { prefs.adBlock = !prefs.adBlock; updateModes()
                    if (prefs.adBlock) binding.webView.evaluateJavascript(CaptureScripts.AD_BLOCK, null)
                    toast(if (prefs.adBlock) "Ad & popup blocking on" else "Ad blocking off") }
                M_DESKTOP -> { prefs.desktopMode = !prefs.desktopMode
                    binding.webView.settings.userAgentString = currentUserAgent()
                    updateModes(); binding.webView.reload() }
                M_PAUSE -> LogRepository.setPaused(!LogRepository.isPaused)
                M_SNAPSHOT -> { val url = binding.webView.url
                    if (url != null) { loggingClient.snapshotDom(binding.webView, url); toast("Page HTML saved to Logs") } }
                M_COPY_URL -> binding.webView.url?.let { ShareUtils.copyToClipboard(requireContext(), it, "URL") }
                M_COOKIES -> showCookies()
                M_CLEAR_LOGS -> LogRepository.clearLogs()
                M_CLEAR_DATA -> confirmClearData()
                else -> if (item.itemId >= M_RECENT_BASE) {
                    prefs.recentSites.getOrNull(item.itemId - M_RECENT_BASE)?.let { open(it) }
                } else return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun showCookies() {
        val url = binding.webView.url ?: return
        val cookies = CookieManager.getInstance().getCookie(url)
        val text = cookies?.split(";")?.joinToString("\n") { it.trim() }?.ifBlank { null } ?: "(no cookies)"
        AlertDialog.Builder(requireContext())
            .setTitle("Cookies — ${android.net.Uri.parse(url).host}")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy") { _, _ -> ShareUtils.copyToClipboard(requireContext(), cookies ?: "", "Cookies") }
            .show()
    }

    private fun confirmClearData() {
        AlertDialog.Builder(requireContext())
            .setTitle("Clear cookies & site data?")
            .setMessage("Signs you out of every site in this browser and clears its cache. Useful to capture a site's first-visit flow (tokens, Cloudflare, etc.).")
            .setPositiveButton("Clear") { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                binding.webView.clearCache(true)
                binding.webView.clearHistory()
                toast("Cleared")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateModes() {
        val b = _binding ?: return
        val parts = mutableListOf<String>()
        if (prefs.deepCapture) parts += "DEEP"
        if (prefs.adBlock) parts += "ADBLOCK"
        if (prefs.desktopMode) parts += "DESKTOP"
        b.tvModes.text = parts.joinToString(" · ")
    }

    private fun observeLogs() {
        LogRepository.logs.observe(viewLifecycleOwner) { renderStatus(it) }
        LogRepository.paused.observe(viewLifecycleOwner) { renderStatus(LogRepository.logs.value ?: emptyList()) }
    }

    private fun renderStatus(logs: List<NetworkLog>) {
        val b = _binding ?: return
        val paused = LogRepository.isPaused
        val streams = logs.count { it.kind == LogKind.STREAM || it.kind == LogKind.MEDIA }
        val apis = logs.count { it.kind == LogKind.XHR }
        b.captureIndicator.setBackgroundResource(if (paused) R.drawable.bg_indicator_dot_paused else R.drawable.bg_indicator_dot)
        b.tvCaptureStatus.text = when {
            paused -> "Paused — ${logs.size} logged"
            logs.isEmpty() -> "Ready — browse to start capturing"
            else -> "${logs.size} req · $apis API · $streams media"
        }
    }

    private fun renderRecents() {
        val recents = prefs.recentSites
        binding.tvRecentLabel.visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE
        binding.recentChips.removeAllViews()
        recents.forEach { site ->
            binding.recentChips.addView(Chip(requireContext()).apply {
                text = site.removePrefix("https://").removePrefix("http://")
                setOnClickListener { open(site) }
            })
        }
    }

    private fun loadUrl() {
        val input = binding.etUrl.text.toString().trim()
        if (input.isBlank()) return
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://duckduckgo.com/?q=${android.net.Uri.encode(input)}"
        }
        open(url)
    }

    fun open(url: String) {
        val b = _binding ?: return
        b.startPanel.visibility = View.GONE
        b.etUrl.setText(url)
        b.etUrl.clearFocus()
        (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(b.etUrl.windowToken, 0)
        b.webView.loadUrl(url)
    }

    // ---- fullscreen video ----

    private fun enterFullscreen(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (view == null) return
        if (customView != null) { callback?.onCustomViewHidden(); return }
        val activity = activity ?: return
        customView = view
        customViewCallback = callback
        originalOrientation = activity.requestedOrientation
        (activity.window.decorView as FrameLayout).addView(
            view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun exitFullscreen() {
        val activity = activity ?: return
        val view = customView ?: return
        (activity.window.decorView as FrameLayout).removeView(view)
        customView = null
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.requestedOrientation = originalOrientation
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    /** Returns true when the back press was consumed by the browser. */
    fun handleBack(): Boolean {
        if (customView != null) { exitFullscreen(); return true }
        val wv = _binding?.webView ?: return false
        if (wv.canGoBack()) { wv.goBack(); return true }
        return false
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroyView() {
        exitFullscreen()
        _binding?.webView?.apply { stopLoading(); destroy() }
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val M_DEEP = 1
        private const val M_DOM = 2
        private const val M_ADS = 3
        private const val M_DESKTOP = 4
        private const val M_PAUSE = 5
        private const val M_RECENT = 6
        private const val M_SNAPSHOT = 7
        private const val M_COPY_URL = 8
        private const val M_COOKIES = 9
        private const val M_CLEAR_LOGS = 10
        private const val M_CLEAR_DATA = 11
        private const val M_RECENT_BASE = 100

        const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
