package com.todocompanion.app.ui.components

import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.todocompanion.app.util.NoteRichRenderer
import java.io.ByteArrayInputStream

/**
 * Wave L — the offline rich-render surface. A [WebView] that renders a note's Markdown with bundled
 * KaTeX / Mermaid / Prism from `file:///android_asset/rich/`, themed from the current Material 3 scheme.
 * It is locked down: JavaScript runs only our own assembled document, file access is allowed (for local
 * image attachments and the bundled JS/CSS), and every http(s) request and navigation is refused — so
 * even though the app holds no INTERNET permission, nothing here can reach out.
 *
 * L12/L13 — [autoHeight] sizes the view to its rendered content instead of filling its box, so the same
 * engine can embed *inside a scrolling parent* (a task's notes, an inline preview) rather than only as a
 * full-screen reader. A tiny `AndroidHeight` bridge reports `document` height (re-measured by a
 * ResizeObserver after KaTeX/Mermaid finish laying out), clamped to [maxAutoHeight]; internal scrolling is
 * off so the outer scroll owns the gesture.
 */
@Composable
fun RichNoteView(
    markdown: String,
    images: Map<String, String>,
    modifier: Modifier = Modifier,
    readingThemeId: String = "match",
    type: com.todocompanion.app.domain.NoteAppearance.NoteType = com.todocompanion.app.domain.NoteAppearance.NoteType(),
    autoHeight: Boolean = false,
    maxAutoHeight: Dp = 5000.dp,
    // R108 — render into a SOFTWARE layer instead of the WebView's own hardware surface. The split-view
    // preview lives BESIDE a live Compose editor; a hardware WebView surface makes the window re-composite
    // when it first attaches, which flashed the editor black for a beat. A software layer draws the WebView
    // into the app's own surface (no separate GPU surface, no window transition), so the neighbour never
    // blacks out. Notes render fine in software (no WebGL/video); we only pay a little raster cost, which is
    // trivial for a small preview. The full-screen reader keeps hardware (default false).
    softwareLayer: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    val theme = remember(cs, readingThemeId, type) {
        val rt = com.todocompanion.app.domain.NoteAppearance.theme(readingThemeId)
        val p = rt.palette(dark)
        val font = type.effectiveFont(rt.font)
        if (p != null) {
            NoteRichRenderer.Theme(
                bg = p.bg, fg = p.fg, muted = p.muted, accent = p.accent,
                codeBg = p.codeBg, border = p.border, quoteBar = p.quoteBar, dark = dark,
                fontFamily = font, fontScalePct = type.scalePct, lineHeight = type.lineFactor(), measureCh = type.measureCh(),
            )
        } else {
            NoteRichRenderer.Theme(
                bg = hex(cs.surface), fg = hex(cs.onSurface), muted = hex(cs.onSurfaceVariant),
                accent = hex(cs.primary), codeBg = hex(cs.surfaceVariant), border = hex(cs.outlineVariant),
                quoteBar = hex(cs.outline), dark = dark,
                fontFamily = font, fontScalePct = type.scalePct, lineHeight = type.lineFactor(), measureCh = type.measureCh(),
            )
        }
    }
    val html = remember(markdown, images, theme) { runCatching { NoteRichRenderer.buildDocument(markdown, theme, images) }.getOrElse { "<pre>" + markdown + "</pre>" } }
    // A non-"match" reading theme paints its own paper; otherwise follow the Material surface. Never throw.
    val fallbackBg = cs.surface.toArgb()
    val bg = remember(theme, fallbackBg) { runCatching { android.graphics.Color.parseColor(theme.bg) }.getOrDefault(fallbackBg) }

    val density = LocalDensity.current
    // device-px height reported by the page; 0 until the first measurement lands.
    var heightPx by remember(markdown) { mutableIntStateOf(0) }
    val maxPx = remember(maxAutoHeight, density) { with(density) { maxAutoHeight.roundToPx() } }

    val sizedModifier = if (!autoHeight) modifier
        else if (heightPx > 0) modifier.height(with(density) { heightPx.toDp() })
        else modifier.heightIn(min = 1.dp)   // collapse until the page reports its real height

    AndroidView(
        modifier = sizedModifier,
        factory = { ctx ->
            if (softwareLayer) android.util.Log.d("KairoSplitDiag", "RichNoteView WebView factory (creating; software layer)")
            WebView(ctx).apply {
                // R108 — a software layer keeps the WebView inside the app's own surface, so attaching it
                // next to the live editor doesn't make the window re-composite (which flashed the editor
                // black). Set before anything else so the very first frame is software-composited.
                if (softwareLayer) setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
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
                if (autoHeight) {
                    // Sized-to-content: the outer scroll owns vertical gestures, so kill our own scrolling.
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                    val dm = resources.displayMetrics.density
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onHeight(cssPx: Float) {
                            // Bridge callback runs off the main thread — hop back before touching Compose state.
                            post {
                                val px = (cssPx * dm).toInt().coerceIn(0, maxPx)
                                if (px > 0 && kotlin.math.abs(px - heightPx) > 1) heightPx = px
                            }
                        }
                    }, "AndroidHeight")
                } else {
                    overScrollMode = WebView.OVER_SCROLL_NEVER
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                        val s = req.url.scheme?.lowercase()
                        return if (s == "http" || s == "https")
                            WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        else null   // file:///android_asset/ and file:// (local images) pass through
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean = true
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (softwareLayer) android.util.Log.d("KairoSplitDiag", "RichNoteView onPageFinished (software=$softwareLayer)")
                        if (autoHeight) view.evaluateJavascript(HEIGHT_OBSERVER_JS, null)
                    }
                }
            }
        },
        update = { web ->
            web.setBackgroundColor(bg)
            web.loadDataWithBaseURL("file:///android_asset/rich/", html, "text/html", "utf-8", null)
        },
    )
}

// Installed after the page loads (autoHeight only): report the document height now and on every reflow —
// KaTeX and Mermaid lay out asynchronously, so a one-shot measurement would be short.
private const val HEIGHT_OBSERVER_JS = """
(function(){
  function report(){ try { AndroidHeight.onHeight(Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0)); } catch(e){} }
  try { new ResizeObserver(report).observe(document.documentElement); } catch(e){}
  window.addEventListener('load', report);
  setTimeout(report, 60); setTimeout(report, 400); setTimeout(report, 1200);
  report();
})();
"""

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
