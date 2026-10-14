package com.khalsatorrent.cs3builder.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import com.khalsatorrent.cs3builder.databinding.FragmentBrowserBinding
import com.khalsatorrent.cs3builder.network.LoggingWebViewClient
import com.khalsatorrent.cs3builder.network.ProxyInterceptor

class BrowserFragment : Fragment() {

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!

    private lateinit var loggingClient: LoggingWebViewClient

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
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        loggingClient = LoggingWebViewClient(
            onPageStartedCallback = { url ->
                activity?.runOnUiThread {
                    binding.etUrl.setText(url)
                    binding.progressBar.visibility = View.VISIBLE
                }
            },
            onPageFinishedCallback = { url ->
                activity?.runOnUiThread {
                    binding.etUrl.setText(url)
                    binding.progressBar.visibility = View.GONE
                }
            }
        )

        binding.webView.apply {
            webViewClient = loggingClient
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36"
            }
            addJavascriptInterface(loggingClient.JSBridge(), "CS3Bridge")
        }
    }

    private fun setupControls() {
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

    fun canGoBack(): Boolean = binding.webView.canGoBack()

    fun goBack() {
        binding.webView.goBack()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
