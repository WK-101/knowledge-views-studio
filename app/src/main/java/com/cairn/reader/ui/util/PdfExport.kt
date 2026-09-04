package com.cairn.reader.ui.util

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Exports a reader article to a PDF using the platform print framework: the clean HTML is
 * laid out in an off-screen [WebView], then handed to the system "Save as PDF" flow. No
 * dependencies and nothing leaves the device — it's the OS printing to a local file.
 */
object PdfExport {

    // The WebView must outlive the print call, so we hold a strong reference until it's done.
    private var pending: WebView? = null

    fun printArticle(context: Context, title: String, contentHtml: String?) {
        val safeTitle = title.ifBlank { "Article" }
        val doc = buildHtml(safeTitle, contentHtml.orEmpty())
        val webView = WebView(context.applicationContext)
        pending = webView
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                runCatching {
                    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
                    val adapter = view.createPrintDocumentAdapter("Cairn — $safeTitle")
                    printManager.print(
                        "Cairn — $safeTitle",
                        adapter,
                        PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                            .build(),
                    )
                }
                pending = null
            }
        }
        webView.loadDataWithBaseURL(null, doc, "text/html", "UTF-8", null)
    }

    private fun buildHtml(title: String, body: String): String = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          @page { margin: 18mm 16mm; }
          body { font-family: Georgia, 'Times New Roman', serif; font-size: 12pt; line-height: 1.55; color: #111; }
          h1 { font-size: 20pt; line-height: 1.25; margin: 0 0 4pt; }
          .meta { color: #666; font-size: 9pt; margin: 0 0 16pt; }
          img { max-width: 100%; height: auto; }
          pre, code { font-family: 'Courier New', monospace; font-size: 10pt; white-space: pre-wrap; }
          blockquote { margin: 0 0 0 12pt; padding-left: 12pt; border-left: 2px solid #ccc; color: #444; }
          a { color: #111; text-decoration: underline; }
        </style></head><body>
        <h1>${escape(title)}</h1>
        <div class="meta">Saved with Cairn</div>
        $body
        </body></html>
    """.trimIndent()

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
