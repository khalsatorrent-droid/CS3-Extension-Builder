package com.khalsatorrent.cs3builder.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebSettings
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.khalsatorrent.cs3builder.R
import com.khalsatorrent.cs3builder.databinding.FragmentBrowserBinding
import com.khalsatorrent.cs3builder.network.LoggingWebViewClient
import com.khalsatorrent.cs3builder.network.ProxyInterceptor

class BrowserFragment : Fragment() {

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!

    private lateinit var loggingClient: LoggingWebViewClient

    /** Whether ad/redirect blocking is active */
    private var adBlockEnabled = false

    // Known ad/redirect/popup domains to block
    private val AD_DOMAINS = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adnxs.com", "moatads.com", "adsafeprotected.com", "advertising.com",
        "outbrain.com", "taboola.com", "revcontent.com", "mgid.com",
        "popads.net", "popcash.net", "propellerads.com", "trafficjunky.com",
        "exoclick.com", "adsterra.com", "hilltopads.net", "juicyads.com",
        "clicksor.com", "yllix.com", "plugrush.com", "traffichaus.com",
        "go2url.net", "redirectvoluum.com", "earnhub.net", "clkads.com",
        "onclick.io", "onclkds.com", "adf.ly", "bc.vc", "linkbucks.com",
        "shorte.st", "adfoc.us", "link-shorteners.net"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupWebView()
        setupControls()
        observeLogs()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        loggingClient = LoggingWebViewClient(
            onPageStartedCallback = { url ->
                activity?.runOnUiThread {
                    binding.etUrl.setText(url)
                    binding.progressBar.visibility = View.VISIBLE
                    binding.btnBack.isEnabled = binding.webView.canGoBack()
                }
            },
            onPageFinishedCallback = { url ->
                activity?.runOnUiThread {
                    binding.etUrl.setText(url)
                    binding.progressBar.visibility = View.GONE
                    binding.btnBack.isEnabled = binding.webView.canGoBack()
                }
            },
            shouldBlockUrl = { url -> adBlockEnabled && isAdUrl(url) }
        )

        binding.webView.apply {
            webViewClient = loggingClient

            // Block popup windows (window.open)
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onCreateWindow(
                    view: android.webkit.WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    if (adBlockEnabled) {
                        // Block all new windows (popups/redirects) when ad block is on
                        return false
                    }
                    return false // always block new windows in this app
                }
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                javaScriptCanOpenWindowsAutomatically = false  // block window.open always
                setSupportMultipleWindows(false)
                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            }
            addJavascriptInterface(loggingClient.JSBridge(), "CS3Bridge")
        }

        binding.btnBack.isEnabled = false
    }

    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return AD_DOMAINS.any { lower.contains(it) }
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }

        binding.btnGo.setOnClickListener { loadUrl() }

        binding.etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadUrl()
                true
            } else false
        }

        binding.fabClear.setOnClickListener {
            ProxyInterceptor.clearLogs()
        }

        // Quick jump to Logs tab from capture bar
        binding.btnViewLogs.setOnClickListener {
            (requireActivity() as? com.khalsatorrent.cs3builder.MainActivity)?.navigateToLogs()
        }

        // Ad block toggle button
        binding.btnAdBlock.setOnClickListener {
            showAdBlockMenu()
        }

        // Dismiss chip closes ad block
        binding.chipAdBlock.setOnCloseIconClickListener {
            setAdBlock(false)
        }
    }

    private fun showAdBlockMenu() {
        val currentState = if (adBlockEnabled) "ON ✅" else "OFF"
        AlertDialog.Builder(requireContext())
            .setTitle("🛡 Ad & Redirect Blocker")
            .setMessage(
                "Status: $currentState\n\n" +
                "When enabled:\n" +
                "• Blocks popup windows (window.open)\n" +
                "• Blocks navigation to known ad networks\n" +
                "• Blocks redirect domains (outbrain, taboola, popads…)\n" +
                "• Blocks new-window creation\n\n" +
                "Note: Some sites may not work correctly with blocking on. " +
                "Turn it off if a video player or login breaks."
            )
            .setPositiveButton(if (adBlockEnabled) "Turn OFF" else "Turn ON") { _, _ ->
                setAdBlock(!adBlockEnabled)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setAdBlock(enabled: Boolean) {
        adBlockEnabled = enabled
        if (enabled) {
            binding.btnAdBlock.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.method_get)
            )
            binding.chipAdBlock.visibility = View.VISIBLE
            // Inject JS to additionally block setTimeout redirects and window.open
            injectAdBlockJs()
        } else {
            binding.btnAdBlock.imageTintList = ColorStateList.valueOf(
                Color.parseColor("#88888888")
            )
            binding.chipAdBlock.visibility = View.GONE
        }
    }

    private fun injectAdBlockJs() {
        val js = """
(function() {
    // Block window.open popups
    window.open = function(url, name, features) {
        console.log('[CS3AdBlock] Blocked window.open: ' + url);
        return null;
    };
    // Block common ad scripts from executing
    var _setTimeout = window.setTimeout;
    window.setTimeout = function(fn, delay) {
        if (typeof fn === 'string' && (fn.indexOf('window.open') >= 0 || fn.indexOf('location') >= 0)) {
            console.log('[CS3AdBlock] Blocked setTimeout redirect');
            return 0;
        }
        return _setTimeout.apply(this, arguments);
    };
    // Prevent invisible overlay clicks (common ad trick)
    document.addEventListener('click', function(e) {
        var el = e.target;
        if (el && el.tagName === 'A') {
            var href = el.href || '';
            var adDomains = ['doubleclick.net','googlesyndication.com','popads.net',
                'popcash.net','outbrain.com','taboola.com','exoclick.com',
                'adsterra.com','propellerads.com'];
            for (var i = 0; i < adDomains.length; i++) {
                if (href.indexOf(adDomains[i]) >= 0) {
                    e.preventDefault();
                    e.stopPropagation();
                    console.log('[CS3AdBlock] Blocked ad link: ' + href);
                    return false;
                }
            }
        }
    }, true);
})();
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    private fun observeLogs() {
        ProxyInterceptor.logs.observe(viewLifecycleOwner) { logs ->
            val count = logs.size
            binding.tvLogCount.text = "$count request${if (count == 1) "" else "s"}"
            if (count > 0) {
                binding.tvCaptureStatus.text = "Capturing — $count logged"
                binding.captureIndicator.setBackgroundResource(R.drawable.bg_indicator_dot)
            } else {
                binding.tvCaptureStatus.text = "Ready — browse to start capturing"
            }
        }
    }

    private fun loadUrl() {
        val input = binding.etUrl.text.toString().trim()
        if (input.isBlank()) return

        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${android.net.Uri.encode(input)}"
        }

        binding.webView.loadUrl(url)
    }

    fun canGoBack(): Boolean = _binding?.webView?.canGoBack() == true

    fun goBack() {
        _binding?.webView?.goBack()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
