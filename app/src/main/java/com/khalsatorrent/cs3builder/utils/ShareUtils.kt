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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ShareUtils {

    fun copyToClipboard(context: Context, text: String, label: String = "CS3 Code") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13+ shows its own clipboard confirmation
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareAsText(context: Context, text: String, title: String = "CS3 Extension Code") {
        // Binder transactions fail above ~1MB; send big text as a file instead
        if (text.length > 400_000) {
            shareFiles(context, listOf(writeExport(context, "shared_text.txt", text)), null, title)
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share via"))
    }

    /** Writes [content] into the shareable exports folder and returns the file. */
    fun writeExport(context: Context, fileName: String, content: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        return File(dir, fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { writeText(content) }
    }

    /** Zips [entries] (path inside zip -> content) into the exports folder. */
    fun writeZip(context: Context, fileName: String, entries: Map<String, String>): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun shareAsFile(context: Context, content: String, fileName: String) {
        shareFiles(context, listOf(writeExport(context, fileName, content)), null, fileName)
    }

    fun shareFiles(context: Context, files: List<File>, text: String?, subject: String) {
        val uris = ArrayList(files.map { uriFor(context, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeFor(files.first())
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        if (text != null) intent.putExtra(Intent.EXTRA_TEXT, text)
        intent.clipData = ClipData.newUri(context.contentResolver, subject, uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share"))
    }

    private fun mimeFor(f: File): String = when (f.extension.lowercase()) {
        "zip" -> "application/zip"
        "json", "har" -> "application/json"
        else -> "text/plain"
    }

    /** Zip of the generated plugin module: <Name>/build.gradle.kts, <Name>/src/main/... */
    fun moduleZip(context: Context, template: CS3Template, extra: Map<String, String> = emptyMap()): File {
        val root = template.providerName
        val entries = LinkedHashMap<String, String>()
        template.files.forEach { entries["$root/${it.path}"] = it.content }
        extra.forEach { (k, v) -> entries["$root/$k"] = v }
        return writeZip(context, "${root}_cs3_module.zip", entries)
    }

    fun buildClaudePrompt(template: CS3Template): String = buildString {
        appendLine("I captured this site's traffic with CS3 Extension Builder and generated a starting CloudStream 3 extension (${template.providerName}, ${template.baseUrl}).")
        appendLine("The attached files are the full network log and the generated plugin module.")
        appendLine()
        appendLine("Please turn it into a working CloudStream extension:")
        appendLine("1. Check every selector, endpoint and JSON field against the captured responses in the log.")
        appendLine("2. Make search(), getMainPage(), load() and loadLinks() work end-to-end, including the Referer/Origin/cookie requirements for the streams.")
        appendLine("3. Use CloudStream's built-in extractors (loadExtractor) for known embed hosts; write custom extraction where needed.")
        appendLine("4. Return the complete corrected files.")
        appendLine()
        appendLine("Capture analysis:")
        appendLine(template.logSummary.lines().take(40).joinToString("\n"))
    }

    /** Sends the prompt plus the log + module to Claude (or any app). The prompt is also copied. */
    fun shareWithClaude(context: Context, template: CS3Template, claudeLog: String) {
        val prompt = buildClaudePrompt(template)
        val log = writeExport(context, "${template.providerName}_network_log.txt", claudeLog)
        val provider = writeExport(context, "${template.providerName}Provider.kt", template.generatedCode)
        val zip = moduleZip(context, template)
        copyToClipboard(context, prompt, "Claude prompt")
        shareFiles(context, listOf(log, provider, zip), prompt, "CS3 extension — ${template.providerName}")
    }
}
