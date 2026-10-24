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
    /** Browser tabs; tab 0 is the WebView from the layout. Popups (window.open) open as new tabs. */
    private val tabs = mutableListOf<WebView>()
    private val tabClients = HashMap<WebView, LoggingWebViewClient>()
    private val tabOpener = HashMap<WebView, WebView>()
    private var currentTab = 0
    private val web: WebView get() = tabs.getOrNull(currentTab) ?: binding.webView
    private val loggingClient: LoggingWebViewClient get() = tabClients[web] ?: tabClients.values.first()
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
        mobileUserAgent = WebSettings.getDefaultUserAgent(requireContext())
            .replace("; wv)", ")").replace(Regex("Version/\\d+\\.\\d+ "), "")
        CookieManager.getInstance().setAcceptCookie(true)
        tabs.clear()
        tabs += binding.webView
        currentTab = 0
        configureTab(binding.webView)
        // Requests made by service workers bypass WebViewClient — log them too
        try {
            android.webkit.ServiceWorkerController.getInstance().setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? =
                    tabClients.values.firstOrNull()?.shouldInterceptRequest(null, request)
            })
        } catch (_: Throwable) { }
        binding.btnBack.isEnabled = false
        updateTabButton()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureTab(webView: WebView) {
        val client = LoggingWebViewClient(
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
                        if (webView !== web) return@runOnUiThread
                        if (!b.etUrl.hasFocus()) b.etUrl.setText(url)
                        b.startPanel.visibility = View.GONE
                        b.btnBack.isEnabled = canGoBackAnywhere()
                    }
                }
                override fun onPageFinished(url: String) {
                    activity?.runOnUiThread {
                        val b = _binding ?: return@runOnUiThread
                        updateTabButton()
                        if (webView !== web) return@runOnUiThread
                        if (!b.etUrl.hasFocus()) b.etUrl.setText(url)
                        b.btnBack.isEnabled = canGoBackAnywhere()
                        if (url.startsWith("http")) {
                            prefs.lastUrl = url
                            prefs.addRecentSite(url)
                        }
                    }
                }
            }
        )

        tabClients[webView] = client
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true) // embedded players need these

        webView.webViewClient = client
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                val b = _binding ?: return
                if (view !== web) return
                b.progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
                b.progressBar.setProgressCompat(newProgress, true)
            }
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) = enterFullscreen(view, callback)
            override fun onHideCustomView() = exitFullscreen()
            // window.open / target=_blank: open a real new tab and keep the opener alive, so ad-gated
            // players ("watch 5 ads to unlock") see the ad open and keep their progress.
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                if (resultMsg == null) return false
                if (prefs.adBlock && !isUserGesture) return false
                return openInNewTab(webView, resultMsg)
            }
            override fun onCloseWindow(window: WebView?) {
                window?.let { closeTab(it) }
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
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            userAgentString = currentUserAgent()
        }
        webView.addJavascriptInterface(client.JSBridge(), "CS3Bridge")

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
                    pageUrl = webView.url
                )
            )
            context?.let { Toast.makeText(it, "Download link captured in Logs", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun currentUserAgent(): String =
        if (prefs.desktopMode) DESKTOP_UA else mobileUserAgent.ifBlank { MOBILE_UA }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { handleBack() }
        binding.btnReload.setOnClickListener {
            if (web.url == null) loadUrl() else web.reload()
        }
        binding.btnTabs.setOnClickListener { showTabs() }
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
                    if (prefs.adBlock) web.evaluateJavascript(CaptureScripts.AD_BLOCK, null)
                    toast(if (prefs.adBlock) "Ad & popup blocking on" else "Ad blocking off") }
                M_DESKTOP -> { prefs.desktopMode = !prefs.desktopMode
                    tabs.forEach { it.settings.userAgentString = currentUserAgent() }
                    updateModes(); web.reload() }
                M_PAUSE -> LogRepository.setPaused(!LogRepository.isPaused)
                M_SNAPSHOT -> { val url = web.url
                    if (url != null) { loggingClient.snapshotDom(web, url); toast("Page HTML saved to Logs") } }
                M_COPY_URL -> web.url?.let { ShareUtils.copyToClipboard(requireContext(), it, "URL") }
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
        val url = web.url ?: return
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
                web.clearCache(true)
                web.clearHistory()
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
        web.loadUrl(url)
    }

    // ---- tabs (popups open here so the opener page keeps its state) ----

    private fun openInNewTab(opener: WebView, resultMsg: android.os.Message): Boolean {
        val b = _binding ?: return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        if (tabs.size >= MAX_TABS) closeTab(tabs[1])
        val tab = WebView(requireContext())
        configureTab(tab)
        tab.settings.setSupportMultipleWindows(true)
        b.webContainer.addView(tab, b.webContainer.indexOfChild(b.startPanel), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tabs += tab
        tabOpener[tab] = opener
        transport.webView = tab
        resultMsg.sendToTarget()
        switchTo(tabs.lastIndex)
        toast("Opened in a new tab — press back to return")
        return true
    }

    private fun switchTo(index: Int) {
        val b = _binding ?: return
        if (index !in tabs.indices) return
        val previous = web
        currentTab = index
        val now = web
        tabs.forEach { it.visibility = if (it === now) View.VISIBLE else View.INVISIBLE }
        if (previous !== now) {
            // Pages that wait for you to "come back" from an ad listen for focus / visibility
            now.evaluateJavascript(RETURN_EVENTS, null)
        }
        b.startPanel.visibility = if (now.url == null && tabs.size == 1) View.VISIBLE else View.GONE
        b.etUrl.setText(now.url ?: "")
        b.btnBack.isEnabled = canGoBackAnywhere()
        updateTabButton()
    }

    private fun closeTab(tab: WebView) {
        val b = _binding ?: return
        val index = tabs.indexOf(tab)
        if (index <= 0) return // tab 0 is the main browser
        val opener = tabOpener.remove(tab)
        tabs.removeAt(index)
        tabClients.remove(tab)
        b.webContainer.removeView(tab)
        tab.stopLoading()
        tab.destroy()
        val target = opener?.let { tabs.indexOf(it) }?.takeIf { it >= 0 } ?: (index - 1).coerceAtLeast(0)
        currentTab = -1
        switchTo(target)
    }

    private fun showTabs() {
        if (tabs.size <= 1) { toast("Only one tab — popups from the page open here as new tabs"); return }
        val labels = tabs.mapIndexed { i, t ->
            val title = t.title?.takeIf { it.isNotBlank() } ?: t.url ?: "New tab"
            (if (i == currentTab) "● " else "") + "${i + 1}. " + title.take(60)
        }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Tabs")
            .setItems(labels) { _, i -> switchTo(i) }
            .setNeutralButton("Close this tab") { _, _ -> closeTab(web) }
            .setNegativeButton("Close all popups") { _, _ -> tabs.drop(1).forEach { closeTab(it) } }
            .show()
    }

    private fun updateTabButton() {
        val b = _binding ?: return
        b.btnTabs.text = tabs.size.coerceAtLeast(1).toString()
    }

    private fun canGoBackAnywhere() = web.canGoBack() || currentTab > 0

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
        if (_binding == null) return false
        if (web.canGoBack()) { web.goBack(); return true }
        if (currentTab > 0) { closeTab(web); return true }
        return false
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroyView() {
        exitFullscreen()
        tabs.forEach { it.stopLoading(); it.destroy() }
        tabs.clear(); tabClients.clear(); tabOpener.clear()
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
        private const val MAX_TABS = 6

        private const val RETURN_EVENTS = "(function(){try{window.dispatchEvent(new Event('focus'));" +
            "document.dispatchEvent(new Event('visibilitychange'));window.dispatchEvent(new Event('pageshow'));}catch(e){}})()"

        const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
