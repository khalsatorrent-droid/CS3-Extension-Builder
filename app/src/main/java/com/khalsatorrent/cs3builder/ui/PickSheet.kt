package com.khalsatorrent.cs3builder.ui

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.khalsatorrent.cs3builder.codegen.SelectorHints
import com.khalsatorrent.cs3builder.utils.ShareUtils

/**
 * Shows what the element picker found: an editable CSS selector with a live match count, the
 * element's text/attributes, and buttons to save the selector as the site's card / title / poster /
 * episode… selector, which the code generator then uses instead of its guess.
 */
class PickSheet(
    private val context: Context,
    private val host: String,
    private val test: (selector: String, done: (count: Int, samples: List<String>, error: String?) -> Unit) -> Unit,
    private val parent: () -> Unit,
    private val child: () -> Unit,
    private val onSaved: () -> Unit
) {
    private var dialog: BottomSheetDialog? = null

    fun show(json: String) {
        val o = try { JsonParser.parseString(json).asJsonObject } catch (_: Exception) { return }
        dialog?.dismiss()
        val d = BottomSheetDialog(context)
        dialog = d
        val dp = context.resources.displayMetrics.density
        val pad = (14 * dp).toInt()
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        val general = o.str("general")
        val unique = o.str("unique")
        val attrs = o.getAsJsonObject("attrs") ?: JsonObject()

        root.addView(TextView(context).apply {
            text = "<${o.str("tag")}> · ${o.get("generalCount")?.asInt ?: 0} similar on this page"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })

        val selector = EditText(context).apply {
            setText(general)
            typeface = Typeface.MONOSPACE
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        root.addView(selector)

        val result = TextView(context).apply { textSize = 12f; setPadding(0, (4 * dp).toInt(), 0, (6 * dp).toInt()) }
        root.addView(result)
        fun runTest() = test(selector.text.toString().trim()) { count, samples, error ->
            result.text = error?.let { "Invalid selector: $it" }
                ?: ("$count match${if (count == 1) "" else "es"}" + if (samples.isNotEmpty()) " — e.g. " + samples.filter { it.isNotBlank() }.take(3).joinToString(" | ") { "\"${it.take(30)}\"" } else "")
        }
        runTest()

        val row1 = row()
        row1.addView(btn("Similar") { selector.setText(general); runTest() })
        row1.addView(btn("Only this") { selector.setText(unique); runTest() })
        row1.addView(btn("Test") { runTest() })
        row1.addView(btn("↑ Parent") { parent() })
        row1.addView(btn("↓ Child") { child() })
        row1.addView(btn("Copy") { ShareUtils.copyToClipboard(context, selector.text.toString(), "CSS selector") })
        root.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row1) })

        root.addView(TextView(context).apply {
            text = "Use for ${host.removePrefix("www.")} as:"
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (10 * dp).toInt(), 0, (4 * dp).toInt())
        })
        val row2 = row()
        SelectorHints.Role.values().forEach { role ->
            row2.addView(btn(shortLabel(role)) {
                val sel = selector.text.toString().trim()
                if (sel.isBlank()) return@btn
                val attr = if (role == SelectorHints.Role.POSTER) posterAttr(attrs) else null
                SelectorHints.set(host, role, SelectorHints.Hint(sel, attr))
                onSaved()
                Toast.makeText(context, "Saved as ${role.label}. Regenerate on the Code tab to use it.", Toast.LENGTH_LONG).show()
            })
        }
        root.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row2) })

        val details = StringBuilder()
        o.str("text").takeIf { it.isNotBlank() }?.let { details.appendLine("Text: $it").appendLine() }
        if (attrs.size() > 0) {
            details.appendLine("Attributes:")
            attrs.entrySet().forEach { (k, v) -> details.appendLine("  $k = ${v.asString.take(200)}") }
            details.appendLine()
        }
        o.getAsJsonArray("samples")?.takeIf { it.size() > 1 }?.let { arr ->
            details.appendLine("Similar elements:")
            arr.forEach { details.appendLine("  • ${it.asString}") }
            details.appendLine()
        }
        details.appendLine("HTML:").appendLine(o.str("html"))
        root.addView(NestedScrollView(context).apply {
            addView(TextView(context).apply {
                text = details.toString()
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextIsSelectable(true)
                setPadding(0, (10 * dp).toInt(), 0, 0)
            })
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (context.resources.displayMetrics.heightPixels * 0.32).toInt())
        })

        d.setContentView(root)
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.behavior.skipCollapsed = true
        d.show()
    }

    fun dismiss() { dialog?.dismiss(); dialog = null }

    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    private fun btn(label: String, onClick: () -> Unit) =
        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            val dp = context.resources.displayMetrics.density
            text = label
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (6 * dp).toInt() }
            setOnClickListener { onClick() }
        }

    private fun shortLabel(r: SelectorHints.Role) = when (r) {
        SelectorHints.Role.CARD -> "Card"
        SelectorHints.Role.TITLE -> "Card title"
        SelectorHints.Role.POSTER -> "Poster"
        SelectorHints.Role.EPISODES -> "Episodes"
        SelectorHints.Role.DETAIL_TITLE -> "Page title"
        SelectorHints.Role.PLOT -> "Plot"
        SelectorHints.Role.TAGS -> "Genres"
    }

    /** Which attribute holds the real poster URL (lazy-loaded sites keep it out of src). */
    private fun posterAttr(attrs: JsonObject): String? {
        val order = listOf("data-src", "data-original", "data-lazy-src", "data-lazy", "src")
        for (a in order) {
            val v = attrs.get(a)?.asString ?: attrs.get("img $a")?.asString
            if (!v.isNullOrBlank() && !v.startsWith("data:")) return a
        }
        return null
    }

    private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
}
