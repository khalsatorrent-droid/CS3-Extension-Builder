package com.khalsatorrent.cs3builder.ui

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.khalsatorrent.cs3builder.BuildConfig
import com.khalsatorrent.cs3builder.MainActivity
import com.khalsatorrent.cs3builder.R
import com.khalsatorrent.cs3builder.codegen.CS3CodeGenerator
import com.khalsatorrent.cs3builder.codegen.SiteAnalyzer
import com.khalsatorrent.cs3builder.databinding.FragmentLogBinding
import com.khalsatorrent.cs3builder.export.LogExporter
import com.khalsatorrent.cs3builder.export.LogImporter
import com.khalsatorrent.cs3builder.export.PcapImporter
import com.khalsatorrent.cs3builder.model.LogKind
import com.khalsatorrent.cs3builder.model.NetworkLog
import com.khalsatorrent.cs3builder.network.LogRepository
import com.khalsatorrent.cs3builder.utils.Prefs
import com.khalsatorrent.cs3builder.utils.ShareUtils
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogFragment : Fragment() {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: LogAdapter
    private lateinit var prefs: Prefs

    private var filterText = ""
    private var allLogs: List<NetworkLog> = emptyList()

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        adapter = LogAdapter(
            onClick = { LogDetailSheet.show(requireContext(), it) },
            onLongClick = { ShareUtils.copyToClipboard(requireContext(), it.url, "URL") }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.cbHideNoise.isChecked = prefs.hideNoise
        binding.cbHideNoise.setOnCheckedChangeListener { _, checked -> prefs.hideNoise = checked; applyFilter() }
        binding.chipFilters.setOnCheckedStateChangeListener { _, _ -> applyFilter() }
        binding.etFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { filterText = s?.toString()?.trim().orEmpty(); applyFilter() }
        })

        LogRepository.logs.observe(viewLifecycleOwner) { logs -> allLogs = logs; applyFilter() }

        binding.btnExport.setOnClickListener { showExportMenu() }
        binding.btnImport.setOnClickListener { importLauncher.launch(arrayOf("*/*")) }
        binding.btnGenerate.setOnClickListener { generateAndNavigate() }
        setupMenu()
    }

    // ---- filtering

    private fun applyFilter() {
        val b = _binding ?: return
        val mainHost = mainHost()
        val chip = b.chipFilters.checkedChipId
        val q = filterText.lowercase()
        val filtered = allLogs.filter { l ->
            if (b.cbHideNoise.isChecked && LogKind.isNoise(l.kind)) return@filter false
            val chipOk = when (chip) {
                R.id.chipApi -> l.kind == LogKind.XHR || l.kind == LogKind.SOCKET
                R.id.chipMedia -> l.kind == LogKind.STREAM || l.kind == LogKind.MEDIA
                R.id.chipPages -> l.kind == LogKind.DOC || l.kind == LogKind.DOM
                R.id.chipSite -> mainHost != null && l.host.removePrefix("www.").endsWith(mainHost)
                R.id.chipErrors -> l.responseCode >= 400
                else -> true
            }
            if (!chipOk) return@filter false
            if (q.isEmpty()) true
            else l.url.lowercase().contains(q) ||
                l.requestHeaders.any { (k, v) -> k.lowercase().contains(q) || v.lowercase().contains(q) } ||
                l.requestBody?.lowercase()?.contains(q) == true ||
                (l.responseBody?.length ?: 0) < 300_000 && l.responseBody?.lowercase()?.contains(q) == true
        }
        adapter.submitList(filtered)
        b.tvCount.text = if (filtered.size == allLogs.size) "${allLogs.size} requests" else "Showing ${filtered.size} of ${allLogs.size}"
        val empty = filtered.isEmpty()
        b.tvEmpty.text = if (allLogs.isEmpty()) getString(R.string.empty_logs) else "Nothing matches these filters."
        b.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        b.recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun mainHost(): String? {
        val main = allLogs.firstOrNull { it.isMainFrame || it.kind == LogKind.DOM } ?: return null
        return main.host.removePrefix("www.").ifBlank { null }
    }

    // ---- toolbar menu (only while this tab is visible)

    private fun setupMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                if (isHidden) return
                menuInflater.inflate(R.menu.menu_main, menu)
                menu.findItem(R.id.action_pause)?.title = if (LogRepository.isPaused) "Resume capture" else "Pause capture"
            }
            override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
                R.id.action_clear_logs -> { confirmClear(); true }
                R.id.action_generate_cs3 -> { generateAndNavigate(); true }
                R.id.action_pause -> { LogRepository.setPaused(!LogRepository.isPaused); requireActivity().invalidateOptionsMenu(); true }
                else -> false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun confirmClear() {
        if (allLogs.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle("Clear ${allLogs.size} captured requests?")
            .setPositiveButton("Clear") { _, _ -> LogRepository.clearLogs() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- export / import

    private fun showExportMenu() {
        val logs = LogRepository.getSnapshot()
        if (logs.isEmpty()) { showEmptyDialog(); return }
        val options = arrayOf(
            "Send to Claude (log + generated extension)",
            "Claude-ready log (.txt)",
            "HAR file (.har) — DevTools / Charles / HAR viewers",
            "Session (.json) — re-import later",
            "Copy log text"
        )
        AlertDialog.Builder(requireContext())
            .setTitle("Export ${logs.size} requests")
            .setItems(options) { _, which -> export(which, logs) }
            .show()
    }

    private fun export(which: Int, logs: List<NetworkLog>) {
        val ctx = requireContext().applicationContext
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val host = SiteAnalyzer.hostOf(logs.firstOrNull { it.isMainFrame }?.url ?: logs.first().url)?.removePrefix("www.") ?: "capture"
        val author = prefs.authorName
        viewLifecycleOwner.lifecycleScope.launch {
            val toast = Toast.makeText(ctx, "Preparing export…", Toast.LENGTH_SHORT)
            toast.show()
            try {
                when (which) {
                    0 -> {
                        val (template, log) = withContext(Dispatchers.Default) {
                            val t = CS3CodeGenerator.generate(logs, null, author)
                            t to LogExporter.claudeLog(logs, t.logSummary)
                        }
                        ShareUtils.shareWithClaude(requireContext(), template, log)
                    }
                    1 -> {
                        val text = withContext(Dispatchers.Default) {
                            LogExporter.claudeLog(logs, runCatching { CS3CodeGenerator.generate(logs).logSummary }.getOrNull())
                        }
                        val f = withContext(Dispatchers.IO) { ShareUtils.writeExport(ctx, "${host}_log_$ts.txt", text) }
                        ShareUtils.shareFiles(requireContext(), listOf(f), null, "Network log — $host")
                    }
                    2 -> {
                        val har = withContext(Dispatchers.Default) { LogExporter.har(logs, BuildConfig.VERSION_NAME) }
                        val f = withContext(Dispatchers.IO) { ShareUtils.writeExport(ctx, "${host}_$ts.har", har) }
                        ShareUtils.shareFiles(requireContext(), listOf(f), null, "HAR — $host")
                    }
                    3 -> {
                        val json = withContext(Dispatchers.Default) { Gson().toJson(logs) }
                        val f = withContext(Dispatchers.IO) { ShareUtils.writeExport(ctx, "${host}_session_$ts.json", json) }
                        ShareUtils.shareFiles(requireContext(), listOf(f), null, "Session — $host")
                    }
                    4 -> {
                        val text = withContext(Dispatchers.Default) { LogExporter.claudeLog(logs, null) }
                        if (text.length > 900_000) Toast.makeText(ctx, "Log is too big for the clipboard — use the .txt export", Toast.LENGTH_LONG).show()
                        else ShareUtils.copyToClipboard(requireContext(), text, "Network log")
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importFrom(uri: Uri) {
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalArgumentException("Could not read the file")
                    if (PcapImporter.isPcap(bytes)) {
                        val r = PcapImporter.parse(bytes)
                        LogImporter.Result(r.logs, r.format)
                    } else {
                        LogImporter.import(bytes.toString(Charsets.UTF_8), "import")
                    }
                }
                if (result.logs.isEmpty()) {
                    Toast.makeText(ctx, "No requests found in that file", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val wasPaused = LogRepository.isPaused
                LogRepository.setPaused(false)
                result.logs.forEach { LogRepository.addLog(it) }
                LogRepository.setPaused(wasPaused)
                Toast.makeText(ctx, "Imported ${result.logs.size} requests (${result.format})", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                AlertDialog.Builder(requireContext())
                    .setTitle("Import failed")
                    .setMessage("${e.message}\n\nSupported: .pcap / .pcapng (PCAPdroid, tcpdump, Wireshark), .har exports (Reqable, HttpCanary, Chrome DevTools, Charles, mitmproxy) and session .json files from this app.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    fun launchImport() = importLauncher.launch(arrayOf("*/*"))

    private fun showEmptyDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle("No logs yet")
            .setMessage("Browse a site in the Browser tab (or import a HAR from the Apps tab) first.\n\nTip: open the home page, search for something, open a title and press play — then generate.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun generateAndNavigate() {
        val logs = LogRepository.getSnapshot()
        if (logs.isEmpty()) { showEmptyDialog(); return }
        (activity as? MainActivity)?.generateAndShowCode()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        activity?.invalidateOptionsMenu()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
