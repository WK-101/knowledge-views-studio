package com.todocompanion.app.ui.components

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.todocompanion.app.util.NoteRichRenderer
import java.io.ByteArrayInputStream

/**
 * Wave L — the offline rich-render surface. A [WebView] that renders a note's Markdown with bundled
 * KaTeX / Mermaid / Prism from `file:///android_asset/rich/`, themed from the current Material 3 scheme.
 * It is locked down: JavaScript runs only our own assembled document, file access is allowed (for local
 * image attachments and the bundled JS/CSS), and every http(s) request and navigation is refused — so
 * even though the app holds no INTERNET permission, nothing here can reach out.
 */
@Composable
fun RichNoteView(markdown: String, images: Map<String, String>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val theme = remember(cs) {
        NoteRichRenderer.Theme(
            bg = hex(cs.surface), fg = hex(cs.onSurface), muted = hex(cs.onSurfaceVariant),
            accent = hex(cs.primary), codeBg = hex(cs.surfaceVariant), border = hex(cs.outlineVariant),
            quoteBar = hex(cs.outline), dark = dark,
        )
    }
    val html = remember(markdown, images, theme) { NoteRichRenderer.buildDocument(markdown, theme, images) }
    val bg = cs.surface.toArgb()

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                @Suppress("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.blockNetworkLoads = true          // belt-and-suspenders: no network even if asked
                settings.builtInZoomControls = false
                settings.setSupportZoom(false)
                setBackgroundColor(bg)
                overScrollMode = WebView.OVER_SCROLL_NEVER
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                        val s = req.url.scheme?.lowercase()
                        return if (s == "http" || s == "https")
                            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        else null   // file:///android_asset/ and file:// (local images) pass through
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean = true
                }
            }
        },
        update = { web ->
            web.setBackgroundColor(bg)
            web.loadDataWithBaseURL("file:///android_asset/rich/", html, "text/html", "utf-8", null)
        },
    )
}

private fun hex(c: Color): String {
    val a = c.toArgb()
    return "#%02X%02X%02X".format((a shr 16) and 0xFF, (a shr 8) and 0xFF, a and 0xFF)
}

/**
 * Wave O — while [active] (e.g. a sealed note is open), set the window's FLAG_SECURE so the screen can't
 * be screenshotted and won't appear in the recents thumbnail — even if the app-wide "secure screen"
 * setting is off. On leave, the flag is restored to the global setting [globalOn], not blindly cleared.
 */
@androidx.compose.runtime.Composable
fun SecureFlagWhile(active: Boolean, globalOn: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(active, globalOn) {
        val window = (view.context as? android.app.Activity)?.window
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        if (active || globalOn) window?.addFlags(flag) else window?.clearFlags(flag)
        onDispose { if (globalOn) window?.addFlags(flag) else window?.clearFlags(flag) }
    }
}
