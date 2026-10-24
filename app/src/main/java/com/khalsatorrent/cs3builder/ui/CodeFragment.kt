package com.khalsatorrent.cs3builder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.khalsatorrent.cs3builder.codegen.CS3CodeGenerator
import com.khalsatorrent.cs3builder.databinding.FragmentCodeBinding
import com.khalsatorrent.cs3builder.export.LogExporter
import com.khalsatorrent.cs3builder.model.CS3Template
import com.khalsatorrent.cs3builder.model.GeneratedFile
import com.khalsatorrent.cs3builder.network.LogRepository
import com.khalsatorrent.cs3builder.utils.CodeHighlighter
import com.khalsatorrent.cs3builder.utils.Prefs
import com.khalsatorrent.cs3builder.utils.ShareUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CodeFragment : Fragment() {

    private var _binding: FragmentCodeBinding? = null
    private val binding get() = _binding!!

    private var template: CS3Template? = null
    private var selected: GeneratedFile? = null
    private var pendingRegenerate = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnRegenerate.setOnClickListener { regenerate() }
        binding.etName.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { regenerate(); true } else false }

        binding.btnCopy.setOnClickListener {
            val f = selected ?: return@setOnClickListener toast("Generate first")
            ShareUtils.copyToClipboard(requireContext(), f.content, f.name)
        }
        binding.btnShare.setOnClickListener {
            val t = template ?: return@setOnClickListener toast("Generate first")
            val zip = ShareUtils.moduleZip(requireContext(), t)
            ShareUtils.shareFiles(requireContext(), listOf(zip), null, "${t.providerName} CloudStream module")
        }
        binding.btnShareClaude.setOnClickListener {
            val t = template ?: return@setOnClickListener toast("Generate first")
            val logs = LogRepository.getSnapshot()
            viewLifecycleOwner.lifecycleScope.launch {
                val log = withContext(Dispatchers.Default) { LogExporter.claudeLog(logs, t.logSummary) }
                ShareUtils.shareWithClaude(requireContext(), t, log)
            }
        }
        template?.let { show(it) }
        if (pendingRegenerate) { pendingRegenerate = false; regenerate() }
    }

    fun regenerate() {
        val b = _binding ?: run { pendingRegenerate = true; return }
        val logs = LogRepository.getSnapshot()
        if (logs.isEmpty()) {
            b.tvCode.text = "// Nothing captured yet.\n// Browse a site (home → search → title → play) or import a HAR, then tap Regenerate."
            return
        }
        val name = b.etName.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val author = Prefs(requireContext()).authorName
        b.btnRegenerate.isEnabled = false
        b.tvCode.text = "// Analysing ${logs.size} requests…"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { CS3CodeGenerator.generate(logs, name, author) }
            }
            val bb = _binding ?: return@launch
            bb.btnRegenerate.isEnabled = true
            result.onSuccess { show(it) }
                .onFailure { bb.tvCode.text = "// Generation failed: ${it.javaClass.simpleName}: ${it.message}" }
        }
    }

    private fun show(t: CS3Template) {
        template = t
        val b = _binding ?: return
        if (b.etName.text.isNullOrBlank()) b.etName.hint = "Extension name (${t.providerName})"
        b.chipFiles.removeAllViews()
        // Notes first so the analysis is the first thing seen
        val ordered = t.files.sortedBy { if (it.name.endsWith(".md")) 0 else if (it.name.endsWith("Provider.kt")) 1 else 2 }
        ordered.forEachIndexed { i, f ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                text = if (f.name.endsWith(".md")) "Analysis" else f.name
                isCheckable = true
                setOnClickListener { select(f) }
            }
            b.chipFiles.addView(chip)
            if (i == 0) chip.isChecked = true
        }
        ordered.firstOrNull()?.let { select(it) }
    }

    private fun select(f: GeneratedFile) {
        selected = f
        val b = _binding ?: return
        b.tvPath.text = "${template?.providerName}/${f.path}"
        b.tvCode.text = CodeHighlighter.highlight(f.content, f.name)
        b.scroll.scrollTo(0, 0)
        b.btnCopy.text = if (f.name.endsWith(".md")) "Copy notes" else "Copy file"
    }

    private fun toast(msg: String) { Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
