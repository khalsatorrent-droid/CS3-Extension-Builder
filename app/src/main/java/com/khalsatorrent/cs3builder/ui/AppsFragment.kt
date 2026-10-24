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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Capturing another app's traffic. Android does not allow one app to run another APK inside
 * itself reliably, so this drives a dedicated capture tool (Reqable / HttpCanary / PCAPdroid)
 * for the chosen app and imports the resulting HAR into the Logs tab.
 */
class AppsFragment : Fragment() {

    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!

    data class AppEntry(val label: String, val pkg: String, val icon: Drawable?, val system: Boolean)

    data class Tool(val name: String, val packages: List<String>, val exportsHar: Boolean, val download: String)

    private val tools = listOf(
        Tool("Reqable", listOf("com.reqable.android"), true, "https://reqable.com/en-US/download/"),
        Tool("HttpCanary", listOf("com.guoshi.httpcanary", "com.guoshi.httpcanary.premium"), true, "https://github.com/MegatronKing/HttpCanary"),
        Tool("PCAPdroid", listOf("com.emanuelef.remote_capture"), false, "https://github.com/emanuele-f/PCAPdroid/releases")
    )

    private var apps: List<AppEntry> = emptyList()
    private val adapter = AppAdapter { showAppActions(it) }

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
    }

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
            b.btnTool.text = "Get Reqable"
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
        val steps = buildString {
            appendLine("1. Start capturing ${app.label} in ${tool?.first?.name ?: "a capture tool (Reqable recommended)"}.")
            appendLine("   Install its CA certificate when it asks — needed to see HTTPS requests.")
            appendLine("2. Use ${app.label}: open the home screen, search, open a title, press play.")
            appendLine("3. Stop the capture and export it as HAR (in Reqable: select requests → Share/Export → HAR).")
            appendLine("4. Come back and tap Import HAR — the requests appear in Logs and you can Generate.")
            appendLine()
            append("Note: apps that pin certificates or ignore user CAs will show connections but not their contents. In that case, try the app's website in the Browser tab — most streaming apps use the same API.")
        }
        val options = mutableListOf<Pair<String, () -> Unit>>()
        fun opt(label: String, action: () -> Unit) = label to action
        if (tool != null && tool.first.name == "PCAPdroid") {
            options += opt("Capture only ${app.label} in PCAPdroid") { startPcapdroid(tool.second, app.pkg) }
        }
        if (tool != null) options += opt("Open ${tool.first.name}") { launch(tool.second) }
        else options += opt("Get Reqable (HAR export)") { openUrl(tools.first().download) }
        options += opt("Launch ${app.label}") { launch(app.pkg) }
        options += opt("Import HAR into Logs") { (activity as? MainActivity)?.importIntoLogs() }
        options += opt("App info") { openAppInfo(app.pkg) }

        AlertDialog.Builder(ctx)
            .setTitle(app.label)
            .setMessage(steps)
            .setPositiveButton(options[0].first) { _, _ -> options[0].second() }
            .setNeutralButton("More…") { _, _ ->
                AlertDialog.Builder(ctx)
                    .setTitle(app.label)
                    .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
                    .show()
            }
            .setNegativeButton("Launch app") { _, _ -> launch(app.pkg) }
            .show()
    }

    /** PCAPdroid's public API: capture a single app with TLS decryption (it asks the user to confirm). */
    private fun startPcapdroid(pcapPkg: String, target: String) {
        val intent = Intent().apply {
            component = ComponentName(pcapPkg, "com.emanuelef.remote_capture.activities.CaptureCtrl")
            putExtra("action", "start")
            putExtra("app_filter", target)
            putExtra("pcap_dump_mode", "none")
            putExtra("tls_decryption", true)
        }
        try {
            startActivity(intent)
            Toast.makeText(requireContext(), "Confirm in PCAPdroid, then use the app. Decrypted requests appear in PCAPdroid's Connections tab.", Toast.LENGTH_LONG).show()
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
