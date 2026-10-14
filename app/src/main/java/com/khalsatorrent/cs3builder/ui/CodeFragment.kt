package com.khalsatorrent.cs3builder.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.khalsatorrent.cs3builder.databinding.FragmentCodeBinding
import com.khalsatorrent.cs3builder.model.CS3Template
import com.khalsatorrent.cs3builder.utils.ShareUtils

class CodeFragment : Fragment() {

    private var _binding: FragmentCodeBinding? = null
    private val binding get() = _binding!!

    private var currentTemplate: CS3Template? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCodeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
        currentTemplate?.let { displayTemplate(it) }
    }

    fun displayTemplate(template: CS3Template) {
        currentTemplate = template
        _binding?.let {
            it.tvCode.text = template.generatedCode
            it.tvSummary.text = template.logSummary
        }
    }

    private fun setupButtons() {
        binding.btnCopy.setOnClickListener {
            val code = currentTemplate?.generatedCode ?: return@setOnClickListener
            ShareUtils.copyToClipboard(requireContext(), code, "CS3 Kotlin Code")
        }

        binding.btnShare.setOnClickListener {
            val template = currentTemplate ?: return@setOnClickListener
            ShareUtils.shareAsFile(
                requireContext(),
                template.generatedCode,
                "${template.providerName}Provider.kt"
            )
        }

        binding.btnShareClaude.setOnClickListener {
            val template = currentTemplate ?: return@setOnClickListener
            ShareUtils.shareWithClaude(requireContext(), template)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
