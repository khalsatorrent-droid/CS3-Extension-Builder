package com.khalsatorrent.cs3builder.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.khalsatorrent.cs3builder.model.CS3Template
import java.io.File

object ShareUtils {

    fun copyToClipboard(context: Context, text: String, label: String = "CS3 Code") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareAsText(context: Context, text: String, title: String = "CS3 Extension Code") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share via"))
    }

    fun shareAsFile(context: Context, code: String, fileName: String = "GeneratedProvider.kt") {
        val cacheDir = File(context.cacheDir, "exports")
        cacheDir.mkdirs()
        val file = File(cacheDir, fileName)
        file.writeText(code)

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Save/Share .kt file"))
    }

    fun buildClaudePrompt(template: CS3Template): String {
        return buildString {
            appendLine("Hi Claude! I captured network traffic from a streaming site using the CS3 Extension Builder app.")
            appendLine("Please review the auto-generated CloudStream 3 extension code below and:")
            appendLine("1. Fix any syntax or logic errors")
            appendLine("2. Improve the CSS selectors where possible")
            appendLine("3. Fill in any missing logic based on the detected patterns")
            appendLine("4. Suggest any improvements")
            appendLine()
            appendLine("=".repeat(60))
            appendLine("NETWORK LOG SUMMARY")
            appendLine("=".repeat(60))
            appendLine(template.logSummary)
            appendLine()
            appendLine("=".repeat(60))
            appendLine("GENERATED CS3 KOTLIN CODE")
            appendLine("=".repeat(60))
            appendLine(template.generatedCode)
        }
    }

    fun shareWithClaude(context: Context, template: CS3Template) {
        val prompt = buildClaudePrompt(template)
        shareAsText(context, prompt, "CS3 Extension — Review Request")
    }
}
