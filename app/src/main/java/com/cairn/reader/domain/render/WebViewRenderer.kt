package com.cairn.reader.domain.render

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** The fully-rendered DOM of a page after its JavaScript has run. */
data class RenderedPage(val finalUrl: String, val html: String)

/**
 * Renders a page with an offscreen [WebView] so JavaScript-built content (single-page apps,
 * client-side-rendered articles) becomes real HTML the on-device extractor can read. The
 * WebView is never attached to a window — the DOM and scripts still run, we just don't paint —
 * and it's created and destroyed for each call. Networking stays on the device; nothing is
 * uploaded. Images are blocked during the render pass since only the text DOM is needed.
 */
@Singleton
class WebViewRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Load [url], wait for the page to finish plus a short settle window for late scripts, then
     * hand back the rendered `outerHTML`. Returns null on timeout or if the DOM comes back empty.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun render(
        url: String,
        settleMs: Long = 1_400,
        timeoutMs: Long = 20_000,
    ): RenderedPage? = withContext(Dispatchers.Main.immediate) {
        withTimeoutOrNull(timeoutMs) {
            var webView: WebView? = null
            try {
                suspendCancellableCoroutine { cont ->
                    val handler = Handler(Looper.getMainLooper())
                    val done = AtomicBoolean(false)

                    val wv = WebView(context)
                    webView = wv
                    with(wv.settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadsImagesAutomatically = false
                        blockNetworkImage = true
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        // This WebView runs untrusted, attacker-controllable page JS off-screen, so
                        // deny it any access to app-private files/content (the platform default is
                        // `true` on API 26-29). Universal/file-URL access already defaults off.
                        allowFileAccess = false
                        allowContentAccess = false
                        @Suppress("DEPRECATION")
                        allowFileAccessFromFileURLs = false
                        @Suppress("DEPRECATION")
                        allowUniversalAccessFromFileURLs = false
                        // A desktop-ish UA coaxes some sites into serving their full article markup.
                        userAgentString = "$userAgentString CairnReader"
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)

                    fun finish(page: RenderedPage?) {
                        if (!done.compareAndSet(false, true)) return
                        handler.removeCallbacksAndMessages(null)
                        if (cont.isActive) cont.resume(page)
                    }

                    wv.webViewClient = object : WebViewClient() {
                        // Hard-block any navigation that isn't plain web traffic — a hostile page must
                        // not be able to send this off-screen WebView to file://, content://, intent://, etc.
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val scheme = request?.url?.scheme?.lowercase()
                            return scheme != "http" && scheme != "https"
                        }

                        override fun onPageFinished(view: WebView, finishedUrl: String?) {
                            if (done.get()) return
                            // Give client-side rendering a moment to populate the DOM, then snapshot it.
                            handler.postDelayed({
                                if (done.get()) return@postDelayed
                                view.evaluateJavascript(SNAPSHOT_JS) { json ->
                                    val html = decode(json)
                                    finish(html?.let { RenderedPage(view.url ?: finishedUrl ?: url, it) })
                                }
                            }, settleMs)
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: android.webkit.WebResourceError?,
                        ) {
                            // Only the main-frame failure aborts the render; sub-resource errors are ignored.
                            if (request?.isForMainFrame == true) finish(null)
                        }

                        // A renderer killed mid-extraction must not take the whole app down: finish
                        // this render as a failure and return true so the framework doesn't crash us.
                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: android.webkit.RenderProcessGoneDetail?,
                        ): Boolean {
                            finish(null)
                            return true
                        }
                    }

                    cont.invokeOnCancellation { finish(null) }
                    wv.loadUrl(url)
                }
            } finally {
                webView?.let { wv ->
                    wv.stopLoading()
                    wv.destroy()
                }
            }
        }
    }

    /** evaluateJavascript hands back a JSON-encoded string; decode it (and treat "null"/blank as no result). */
    private fun decode(json: String?): String? {
        if (json == null || json == "null") return null
        val html = runCatching { JSONArray("[$json]").getString(0) }.getOrNull() ?: return null
        return html.takeIf { it.isNotBlank() && it.length > 40 }
    }

    private companion object {
        const val SNAPSHOT_JS = "(function(){try{return document.documentElement.outerHTML;}catch(e){return null;}})();"
    }
}
