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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogFragment : Fragment() {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: LogAdapter
    private var generatedTemplate: com.khalsatorrent.cs3builder.model.CS3Template? = null

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
    }

    private fun setupRecyclerView() {
        adapter = LogAdapter { log -> showLogDetail(log) }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun observeLogs() {
        ProxyInterceptor.logs.observe(viewLifecycleOwner) { logs ->
            adapter.submitList(logs)
            binding.tvEmpty.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_main, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.action_clear_logs -> {
                        ProxyInterceptor.clearLogs()
                        true
                    }
                    R.id.action_generate_cs3 -> {
                        generateAndNavigate()
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun generateAndNavigate() {
        val logs = ProxyInterceptor.getSnapshot()
        if (logs.isEmpty()) {
            AlertDialog.Builder(requireContext())
                .setTitle("No Logs")
                .setMessage("Browse some pages first to capture network traffic.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val template = CS3CodeGenerator.generate(logs)
        generatedTemplate = template

        (requireActivity() as? com.khalsatorrent.cs3builder.MainActivity)
            ?.navigateToCode(template)
    }

    private fun showLogDetail(log: NetworkLog) {
        val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder()

        sb.appendLine("=== Request ===")
        sb.appendLine("Time    : ${timeFormat.format(Date(log.timestamp))}")
        sb.appendLine("Method  : ${log.method}")
        sb.appendLine("URL     : ${log.url}")
        sb.appendLine("XHR     : ${log.isXHR}")
        sb.appendLine("Duration: ${log.duration}ms")
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

        sb.appendLine("=== Response ===")
        sb.appendLine("Status  : ${if (log.responseCode > 0) log.responseCode.toString() else "N/A (WebView intercept)"}")
        sb.appendLine("MIME    : ${log.mimeType ?: "unknown"}")
        sb.appendLine()

        if (log.responseHeaders.isNotEmpty()) {
            sb.appendLine("--- Response Headers ---")
            log.responseHeaders.forEach { (k, v) -> sb.appendLine("$k: $v") }
            sb.appendLine()
        }

        if (log.cookies.isNotEmpty()) {
            sb.appendLine("--- Cookies (Set-Cookie) ---")
            log.cookies.forEach { sb.appendLine(it) }
            sb.appendLine()
        }

        if (!log.responseBody.isNullOrBlank()) {
            sb.appendLine("--- Response Body (first 1500 chars) ---")
            sb.appendLine(log.responseBody.take(1500))
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Log Detail")
            .setMessage(sb.toString())
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
