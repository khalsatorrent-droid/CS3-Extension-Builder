package com.khalsatorrent.cs3builder.ui

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.khalsatorrent.cs3builder.MainActivity
import com.khalsatorrent.cs3builder.R
import com.khalsatorrent.cs3builder.databinding.FragmentAppsBinding
import com.khalsatorrent.cs3builder.monitor.AppMonitorService
import com.khalsatorrent.cs3builder.monitor.HostClassifier
import com.khalsatorrent.cs3builder.utils.ShareUtils
import android.app.Activity
import android.net.VpnService
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watching another app's network activity.
 *
 * The built-in App Monitor ([AppMonitorService]) places only the chosen app in a local VPN that
 * routes nothing but its DNS, so it lists every server the app talks to with no certificate and
 * without touching any other app. External tools (PCAPdroid, Reqable…) remain available for
 * full captures, imported into Logs.
 */
class AppsFragment : Fragment() {

    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!

    data class AppEntry(val label: String, val pkg: String, val icon: Drawable?, val system: Boolean)

    data class Tool(val name: String, val packages: List<String>, val exportsHar: Boolean, val download: String)

    // PCAPdroid first: it captures per-app and needs no system certificate, so banking apps are safe.
    private val tools = listOf(
        Tool("PCAPdroid", listOf("com.emanuelef.remote_capture"), false, "https://github.com/emanuele-f/PCAPdroid/releases"),
        Tool("Reqable", listOf("com.reqable.android"), true, "https://reqable.com/en-US/download/"),
        Tool("HttpCanary", listOf("com.guoshi.httpcanary", "com.guoshi.httpcanary.premium"), true, "https://github.com/MegatronKing/HttpCanary")
    )

    private var apps: List<AppEntry> = emptyList()
    private val adapter = AppAdapter { showAppActions(it) }
    private var pendingMonitor: AppEntry? = null

    /** Android asks once for permission to create the local VPN. */
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val app = pendingMonitor
        pendingMonitor = null
        if (result.resultCode == Activity.RESULT_OK && app != null) beginMonitor(app)
        else if (app != null) toast("The monitor needs that permission to see ${app.label}'s lookups")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAppsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvApps.layoutManager = LinearLayoutManager(requireContext())
        binding.rvApps.adapter = adapter
        binding.btnImportHar.setOnClickListener { (activity as? MainActivity)?.importIntoLogs() }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
        })
        renderTools()
        binding.btnMonitorStop.setOnClickListener { AppMonitorService.stop(requireContext()) }
        binding.btnMonitorHosts.setOnClickListener { showHosts() }
        binding.btnMonitorOpen.setOnClickListener { AppMonitorService.session.value?.let { launch(it.pkg) } }
        AppMonitorService.session.observe(viewLifecycleOwner) { renderMonitor() }
        AppMonitorService.hosts.observe(viewLifecycleOwner) { renderMonitor() }
        AppMonitorService.error.observe(viewLifecycleOwner) { msg -> if (msg != null) { toast(msg); AppMonitorService.error.value = null } }
    }

    // ---- built-in App Monitor ----

    private fun startMonitor(app: AppEntry) {
        val consent = VpnService.prepare(requireContext())
        if (consent != null) {
            pendingMonitor = app
            vpnConsent.launch(consent)
        } else beginMonitor(app)
    }

    private fun beginMonitor(app: AppEntry) {
        AppMonitorService.start(requireContext(), app.pkg, app.label)
        toast("Monitoring ${app.label} — use it now, then come back to see its servers")
        // Give the tunnel a moment so the app's first lookups are caught
        view?.postDelayed({ launch(app.pkg) }, 700)
    }

    private fun renderMonitor() {
        val b = _binding ?: return
        val s = AppMonitorService.session.value
        b.cardMonitor.visibility = if (s == null) View.GONE else View.VISIBLE
        if (s == null) return
        val hosts = AppMonitorService.hosts.value.orEmpty()
        val api = hosts.count { it.kind == HostClassifier.Kind.API || it.kind == HostClassifier.Kind.APP }
        val media = hosts.count { it.kind == HostClassifier.Kind.MEDIA }
        val mins = ((System.currentTimeMillis() - s.started) / 60000).toInt()
        b.tvMonitorTitle.text = "● Monitoring ${s.label}"
        b.tvMonitorStats.text = if (hosts.isEmpty())
            "No lookups yet — open ${s.label} and browse, search, play something. (${mins}m)"
        else "${hosts.size} servers · $api app/API · $media video/CDN · ${mins}m. Every server is also in Logs."
    }

    private fun showHosts() {
        val s = AppMonitorService.session.value
        val hosts = AppMonitorService.hosts.value.orEmpty()
        if (hosts.isEmpty()) { toast("Nothing yet — use the app first"); return }
        val labels = hosts.map { h -> "[${h.kind.label}] ${h.host}  ×${h.count}" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("${s?.label ?: "App"} talks to")
            .setItems(labels) { _, i -> showHostActions(hosts[i]) }
            .setPositiveButton("Share list") { _, _ ->
                val text = buildString {
                    appendLine("Servers used by ${s?.label} (${s?.pkg}) — captured by CS3 Extension Builder App Monitor")
                    hosts.groupBy { it.kind }.toSortedMap(compareBy { it.rank }).forEach { (k, list) ->
                        appendLine(); appendLine("## ${k.label}")
                        list.forEach { appendLine("${it.host}  (×${it.count})  ${it.addresses.take(3).joinToString(", ")}") }
                    }
                }
                ShareUtils.shareAsText(requireContext(), text, "Servers used by ${s?.label}")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showHostActions(h: AppMonitorService.Host) {
        val info = "Category: ${h.kind.label}\nLooked up ${h.count}×\n" +
            (if (h.addresses.isEmpty()) "No answer" else "Resolves to:\n" + h.addresses.joinToString("\n"))
        AlertDialog.Builder(requireContext())
            .setTitle(h.host)
            .setMessage(info)
            .setPositiveButton("Open in Browser") { _, _ -> (activity as? MainActivity)?.navigateToBrowser("https://${h.host}/") }
            .setNeutralButton("Copy") { _, _ -> ShareUtils.copyToClipboard(requireContext(), h.host, "Host") }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            renderTools()
            if (apps.isEmpty()) loadApps()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) { renderTools(); if (apps.isEmpty()) loadApps() }
    }

    private fun installedTool(): Pair<Tool, String>? {
        val pm = requireContext().packageManager
        for (t in tools) for (p in t.packages) if (isInstalled(pm, p)) return t to p
        return null
    }

    private fun isInstalled(pm: PackageManager, pkg: String) = try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }

    private fun renderTools() {
        val b = _binding ?: return
        val pm = requireContext().packageManager
        val status = tools.joinToString("   ") { t -> (if (t.packages.any { isInstalled(pm, it) }) "✓ " else "✗ ") + t.name }
        b.tvTools.text = "Capture tools: $status"
        val installed = installedTool()
        if (installed != null) {
            b.btnTool.text = "Open ${installed.first.name}"
            b.btnTool.setOnClickListener { launch(installed.second) }
        } else {
            b.btnTool.text = "Get PCAPdroid"
            b.btnTool.setOnClickListener { openUrl(tools.first().download) }
        }
    }

    private fun loadApps() {
        val b = _binding ?: return
        b.progress.visibility = View.VISIBLE
        val pm = requireContext().packageManager
        val self = requireContext().packageName
        viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcher, 0)
                    .map { it.activityInfo.applicationInfo }
                    .distinctBy { it.packageName }
                    .filter { it.packageName != self && tools.none { t -> it.packageName in t.packages } }
                    .map { ai ->
                        AppEntry(
                            label = ai.loadLabel(pm).toString(),
                            pkg = ai.packageName,
                            icon = try { ai.loadIcon(pm) } catch (_: Exception) { null },
                            system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0 && ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
                        )
                    }
                    .sortedWith(compareBy<AppEntry> { it.system }.thenBy { it.label.lowercase() })
            }
            apps = list
            val bb = _binding ?: return@launch
            bb.progress.visibility = View.GONE
            filter(bb.etSearch.text?.toString().orEmpty())
        }
    }

    private fun filter(q: String) {
        val query = q.trim().lowercase()
        adapter.submit(if (query.isEmpty()) apps else apps.filter { it.label.lowercase().contains(query) || it.pkg.contains(query) })
    }

    private fun showAppActions(app: AppEntry) {
        val ctx = requireContext()
        val tool = installedTool()
        val hasPcapdroid = tool?.first?.name == "PCAPdroid"
        val steps = buildString {
            appendLine("Monitor in this app — built in, no certificate:")
            appendLine("Only ${app.label} is placed in a local VPN, and only its DNS goes through it. Your other apps (banking included) are never touched.")
            appendLine()
            appendLine("1. Tap Monitor — ${app.label} opens.")
            appendLine("2. Use it: home, search, open a title, press play.")
            appendLine("3. Come back: you get every server it talks to, sorted API → video CDN → ads.")
            appendLine("4. Open the API / app host in the Browser tab — if the service has a website or web player, that captures the real requests.")
            appendLine()
            append("Tip: set Private DNS to Off or Automatic while monitoring, or lookups can bypass the monitor. Request contents stay encrypted — seeing those needs a certificate (PCAPdroid with decryption, under More).")
        }
        val options = mutableListOf<Pair<String, () -> Unit>>()
        fun opt(label: String, action: () -> Unit) = label to action
        options += opt("Monitor ${app.label} (built in)") { startMonitor(app) }
        if (hasPcapdroid && tool != null) {
            val pcapPkg = tool.second
            options += opt("PCAPdroid capture — no cert") { startPcapdroid(pcapPkg, app.pkg, decrypt = false) }
            options += opt("PCAPdroid capture — decrypt (needs cert)") { startPcapdroid(pcapPkg, app.pkg, decrypt = true) }
        } else {
            options += opt("Get PCAPdroid (full packet captures)") { openUrl(tools.first { it.name == "PCAPdroid" }.download) }
        }
        if (tool != null) options += opt("Open ${tool.first.name}") { launch(tool.second) }
        options += opt("Launch ${app.label}") { launch(app.pkg) }
        options += opt("Import capture into Logs") { (activity as? MainActivity)?.importIntoLogs() }
        options += opt("Capture its website in Browser") { (activity as? MainActivity)?.navigateToBrowser(null) }
        options += opt("App info") { openAppInfo(app.pkg) }

        AlertDialog.Builder(ctx)
            .setTitle(app.label)
            .setMessage(steps)
            .setPositiveButton("Monitor") { _, _ -> startMonitor(app) }
            .setNeutralButton("More…") { _, _ ->
                AlertDialog.Builder(ctx)
                    .setTitle(app.label)
                    .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * PCAPdroid's public API: capture a single app. With [decrypt] false no certificate is used, so
     * nothing else on the phone is affected; the capture is dumped to a file to export and import here.
     */
    private fun startPcapdroid(pcapPkg: String, target: String, decrypt: Boolean) {
        val intent = Intent().apply {
            component = ComponentName(pcapPkg, "com.emanuelef.remote_capture.activities.CaptureCtrl")
            putExtra("action", "start")
            putExtra("app_filter", target)
            putExtra("pcap_dump_mode", "pcap_file")
            putExtra("pcap_name", "cs3_${target.substringAfterLast('.')}.pcap")
            putExtra("tls_decryption", decrypt)
        }
        try {
            startActivity(intent)
            val msg = if (decrypt)
                "Confirm in PCAPdroid (installs its per-app cert — banking apps unaffected). Use the app, stop, then export the PCAP and Import it here."
            else
                "Confirm in PCAPdroid — no certificate needed. Use the app, stop, then export the PCAP and Import it here."
            Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Could not start PCAPdroid: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun launch(pkg: String) {
        val intent = requireContext().packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) { Toast.makeText(requireContext(), "Can't launch $pkg", Toast.LENGTH_SHORT).show(); return }
        startActivity(intent)
    }

    private fun openAppInfo(pkg: String) {
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { (activity as? MainActivity)?.navigateToBrowser(url) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class AppAdapter(private val onClick: (AppEntry) -> Unit) : RecyclerView.Adapter<AppAdapter.VH>() {
        private var items: List<AppEntry> = emptyList()

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<AppEntry>) { items = list; notifyDataSetChanged() }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.ivIcon)
            val label: TextView = v.findViewById(R.id.tvLabel)
            val pkg: TextView = v.findViewById(R.id.tvPackage)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val a = items[position]
            holder.icon.setImageDrawable(a.icon)
            holder.label.text = a.label
            holder.pkg.text = a.pkg
            holder.itemView.setOnClickListener { onClick(a) }
        }
    }
}
