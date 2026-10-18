package com.khalsatorrent.cs3builder.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.khalsatorrent.cs3builder.R
import com.khalsatorrent.cs3builder.codegen.CS3CodeGenerator
import com.khalsatorrent.cs3builder.databinding.FragmentLogBinding
import com.khalsatorrent.cs3builder.model.NetworkLog
import com.khalsatorrent.cs3builder.network.ProxyInterceptor
import com.khalsatorrent.cs3builder.utils.ShareUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogFragment : Fragment() {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: LogAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        observeLogs()
        setupMenu()
        setupShareButtons()
    }

    private fun setupRecyclerView() {
        adapter = LogAdapter { log -> showLogDetail(log) }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun observeLogs() {
        ProxyInterceptor.logs.observe(viewLifecycleOwner) { logs ->
            adapter.submitList(logs)
            val empty = logs.isEmpty()
            binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
            // Keep share bar visible always so user can see it's there; buttons handle empty state
        }
    }

    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_main, menu)
            }
            override fun onMenuItemSelected(item: MenuItem): Boolean {
                return when (item.itemId) {
                    R.id.action_clear_logs -> { ProxyInterceptor.clearLogs(); true }
                    R.id.action_generate_cs3 -> { generateAndNavigate(); true }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun setupShareButtons() {
        // Share raw logs as plain text
        binding.btnShareLogs.setOnClickListener {
            val logs = ProxyInterceptor.getSnapshot()
            if (logs.isEmpty()) { showEmptyDialog(); return@setOnClickListener }
            val text = buildRawLogText(logs)
            ShareUtils.shareAsText(requireContext(), text, "Network Logs")
        }

        // Save logs as a .txt file
        binding.btnExportLogs.setOnClickListener {
            val logs = ProxyInterceptor.getSnapshot()
            if (logs.isEmpty()) { showEmptyDialog(); return@setOnClickListener }
            val text = buildRawLogText(logs)
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            ShareUtils.shareAsFile(requireContext(), text, "cs3_logs_$ts.txt")
        }

        // Generate code then share everything with Claude
        binding.btnGenerateAndShare.setOnClickListener {
            val logs = ProxyInterceptor.getSnapshot()
            if (logs.isEmpty()) { showEmptyDialog(); return@setOnClickListener }
            val template = CS3CodeGenerator.generate(logs)
            ShareUtils.shareWithClaude(requireContext(), template)
        }
    }

    private fun showEmptyDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("No Logs Yet")
            .setMessage("Browse a website in the Browser tab to capture network traffic first.\n\nTip: Visit a search page, click on a video, then use the share buttons.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun buildRawLogText(logs: List<NetworkLog>): String {
        val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return buildString {
            appendLine("CS3 Extension Builder — Raw Network Log")
            appendLine("Captured: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Total requests: ${logs.size}")
            appendLine("=".repeat(60))
            appendLine()
            logs.forEachIndexed { i, log ->
                appendLine("[${i + 1}] ${timeFmt.format(Date(log.timestamp))} ${log.method} ${log.responseCode.let { if (it > 0) "($it)" else "" }}")
                appendLine("    URL: ${log.url}")
                if (log.mimeType != null) appendLine("    Type: ${log.mimeType}")
                if (log.isXHR) appendLine("    XHR/Fetch: true")
                if (log.requestHeaders.isNotEmpty()) {
                    val importantHeaders = log.requestHeaders.filter { (k, _) ->
                        k.lowercase() in setOf("authorization", "cookie", "x-api-key", "user-agent",
                            "referer", "origin", "accept", "x-requested-with", "content-type")
                    }
                    if (importantHeaders.isNotEmpty()) {
                        appendLine("    Headers:")
                        importantHeaders.forEach { (k, v) -> appendLine("      $k: ${v.take(120)}") }
                    }
                }
                if (!log.requestBody.isNullOrBlank()) appendLine("    Body: ${log.requestBody.take(300)}")
                if (log.cookies.isNotEmpty()) {
                    appendLine("    Cookies:")
                    log.cookies.take(5).forEach { appendLine("      $it") }
                }
                if (!log.responseBody.isNullOrBlank()) appendLine("    Response: ${log.responseBody.take(200)}")
                appendLine()
            }
        }
    }

    private fun generateAndNavigate() {
        val logs = ProxyInterceptor.getSnapshot()
        if (logs.isEmpty()) {
            showEmptyDialog()
            return
        }
        val template = CS3CodeGenerator.generate(logs)
        (requireActivity() as? com.khalsatorrent.cs3builder.MainActivity)?.navigateToCode(template)
    }

    private fun showLogDetail(log: NetworkLog) {
        val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder()

        sb.appendLine("=== REQUEST ===")
        sb.appendLine("Time     : ${timeFmt.format(Date(log.timestamp))}")
        sb.appendLine("Method   : ${log.method}")
        sb.appendLine("URL      : ${log.url}")
        sb.appendLine("XHR/Fetch: ${log.isXHR}")
        if (log.duration > 0) sb.appendLine("Duration : ${log.duration}ms")
        sb.appendLine()

        if (log.requestHeaders.isNotEmpty()) {
            sb.appendLine("--- Request Headers ---")
            log.requestHeaders.forEach { (k, v) -> sb.appendLine("$k: $v") }
            sb.appendLine()
        }

        if (!log.requestBody.isNullOrBlank()) {
            sb.appendLine("--- Request Body ---")
            sb.appendLine(log.requestBody.take(2000))
            sb.appendLine()
        }

        sb.appendLine("=== RESPONSE ===")
        sb.appendLine("Status   : ${if (log.responseCode > 0) log.responseCode else "N/A (WebView)"}")
        sb.appendLine("MIME     : ${log.mimeType ?: "unknown"}")
        sb.appendLine()

        if (log.responseHeaders.isNotEmpty()) {
            sb.appendLine("--- Response Headers ---")
            log.responseHeaders.forEach { (k, v) -> sb.appendLine("$k: $v") }
            sb.appendLine()
        }

        if (log.cookies.isNotEmpty()) {
            sb.appendLine("--- Cookies ---")
            log.cookies.forEach { sb.appendLine(it) }
            sb.appendLine()
        }

        if (!log.responseBody.isNullOrBlank()) {
            sb.appendLine("--- Response Body ---")
            sb.appendLine(log.responseBody.take(3000))
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Request Detail")
            .setMessage(sb.toString())
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy URL") { _, _ ->
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", log.url))
            }
            .setNegativeButton("Share") { _, _ ->
                ShareUtils.shareAsText(requireContext(), sb.toString(), "Request: ${log.url.take(50)}")
            }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
