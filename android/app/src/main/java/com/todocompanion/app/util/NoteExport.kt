package com.todocompanion.app.util

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.todocompanion.app.data.entity.NoteEntity
import com.todocompanion.app.ui.components.MarkdownDoc
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Wave I — single-note export. Fully offline: TXT / Markdown / HTML / JSON are built in memory, and PDF
 * is rendered by an offscreen [WebView] handed to the system [PrintManager] (no library, no storage
 * permission — the OS owns the output). HTML reuses the already-bundled commonmark [HtmlRenderer], so the
 * same GFM the app renders on screen is what leaves the app.
 */
object NoteExport {

    enum class Format(val label: String, val ext: String, val mime: String) {
        MARKDOWN("Markdown (.md)", "md", "text/markdown"),
        TEXT("Plain text (.txt)", "txt", "text/plain"),
        HTML("Web page (.html)", "html", "text/html"),
        PDF("PDF (print)", "pdf", "application/pdf"),
        JSON("JSON (.json)", "json", "application/json"),
    }

    private val htmlRenderer: HtmlRenderer = HtmlRenderer.builder().build()

    private fun titleOf(note: NoteEntity) = note.title.ifBlank { "Untitled note" }

    /** Markdown with the title as an H1 (unless the body already opens with one). */
    fun markdown(note: NoteEntity): String {
        val body = note.body.trim()
        val hasH1 = body.startsWith("# ")
        return if (note.title.isBlank() || hasH1) body else "# ${note.title}\n\n$body"
    }

    fun plainText(note: NoteEntity): String =
        (if (note.title.isBlank()) "" else "${note.title}\n\n") + note.body.trim()

    /** A standalone, theme-neutral HTML document (commonmark → HTML for the body). */
    fun html(note: NoteEntity): String {
        val bodyHtml = runCatching { htmlRenderer.render(MarkdownDoc.parse(note.body)) }.getOrDefault(escape(note.body))
        return buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<title>").append(escape(titleOf(note))).append("</title>")
            append("<style>")
            append("body{font-family:-apple-system,Roboto,Segoe UI,sans-serif;line-height:1.6;max-width:44rem;margin:2rem auto;padding:0 1rem;color:#1a1a1a}")
            append("h1,h2,h3{line-height:1.25}code,pre{font-family:ui-monospace,monospace}")
            append("pre{background:#f4f4f2;padding:.8rem;border-radius:8px;overflow-x:auto}")
            append("code{background:#f0f0ee;padding:.1rem .3rem;border-radius:4px}")
            append("blockquote{border-left:3px solid #ccc;margin:0;padding-left:1rem;color:#555}")
            append("table{border-collapse:collapse}th,td{border:1px solid #ddd;padding:.4rem .7rem}")
            append("@media(prefers-color-scheme:dark){body{background:#141312;color:#e8e6df}pre{background:#232019}code{background:#232019}}")
            append("</style></head><body>")
            if (note.title.isNotBlank() && !note.body.trim().startsWith("# ")) append("<h1>").append(escape(note.title)).append("</h1>")
            append(bodyHtml)
            append("</body></html>")
        }
    }

    fun json(note: NoteEntity): String = runCatching {
        kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(NoteEntity.serializer(), note)
    }.getOrDefault("{}")

    fun buildContent(note: NoteEntity, format: Format): String = when (format) {
        Format.MARKDOWN -> markdown(note)
        Format.TEXT -> plainText(note)
        Format.HTML -> html(note)
        Format.JSON -> json(note)
        Format.PDF -> html(note)   // PDF goes through print(), not a text file
    }

    /** Render the note to PDF via an offscreen WebView + the system print dialog. Main-thread only. */
    fun printPdf(context: Context, note: NoteEntity) {
        val web = WebView(context)
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val pm = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
                val jobName = titleOf(note).take(40).ifBlank { "Note" }
                runCatching {
                    pm.print(jobName, view.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
                }
            }
        }
        web.loadDataWithBaseURL(null, html(note), "text/html", "UTF-8", null)
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
