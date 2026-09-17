package com.cairn.reader.domain.transcript

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.cairn.reader.util.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Fetches YouTube captions the only way that reliably works now: from *inside* a real YouTube page.
 *
 * Since 2024 YouTube gates the signed caption URL behind a per-session proof-of-origin token — a
 * plain HTTP GET (a different "session") gets an empty 200 body no matter the headers. So we load the
 * watch page in an offscreen [WebView] and, from within that authenticated page context, read the
 * player response, pick a caption track, and `fetch()` its json3 track — inheriting the page's
 * cookies and token. This is what browser extensions do ("read captions from the tab you're on").
 * Entirely on-device; nothing but the normal YouTube page load leaves the device.
 */
@Singleton
class YouTubeCaptionWebView @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val main = Handler(Looper.getMainLooper())

    /** Returns the json3 caption body for [videoId] (parse with [CaptionParsers.parseJson3]), or null. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchJson3(videoId: String, preferLang: String = "en"): String? =
        withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { cont ->
                    var webView: WebView? = null
                    var settled = false
                    fun settle(result: String?) {
                        main.post {
                            if (!settled) {
                                settled = true
                                runCatching { webView?.destroy() }
                                webView = null
                                if (cont.isActive) cont.resume(result)
                            }
                        }
                    }
                    val created = runCatching {
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = DESKTOP_UA
                            settings.blockNetworkImage = true // we only need the page's data, not its media
                            addJavascriptInterface(
                                object {
                                    @JavascriptInterface
                                    fun onResult(data: String?) { settle(data) }
                                },
                                "CairnCaptions",
                            )
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    view?.evaluateJavascript(injectJs(preferLang), null)
                                }
                            }
                        }
                    }.getOrElse { AppLog.w("yt webview: create failed", it); return@suspendCancellableCoroutine cont.resume(null) }
                    webView = created
                    // Pre-accept consent so the EU "before you continue" wall (which has no player
                    // response) doesn't replace the watch page.
                    runCatching {
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setCookie("https://www.youtube.com", "SOCS=CAISNQgDEitib3hfMjAyNDA0MTU; path=/; domain=.youtube.com")
                            setCookie("https://www.youtube.com", "CONSENT=YES+cb; path=/; domain=.youtube.com")
                        }
                    }
                    cont.invokeOnCancellation { main.post { runCatching { created.destroy() } } }
                    runCatching { created.loadUrl("https://www.youtube.com/watch?v=$videoId&hl=$preferLang") }
                        .onFailure { settle(null) }
                }
            }
        }?.takeUnless { it.startsWith("ERR:") }?.also {
            // Surface the JS-side outcome for diagnostics without dumping the whole body.
            AppLog.diag("yt webview: got ${it.length}b caption body")
        } ?: run { AppLog.w("yt webview: no caption body"); null }

    private fun injectJs(lang: String): String = """
        (function(){
          function pick(tracks){
            return tracks.filter(function(x){return x.languageCode==='$lang' && x.kind!=='asr';})[0]
                || tracks.filter(function(x){return x.languageCode==='$lang';})[0]
                || tracks.filter(function(x){return (x.languageCode||'').indexOf('$lang')===0;})[0]
                || tracks.filter(function(x){return x.kind!=='asr';})[0]
                || tracks[0];
          }
          function run(){
            try {
              var pr = window.ytInitialPlayerResponse;
              if(!pr){ CairnCaptions.onResult('ERR:no-player-response'); return; }
              var r = pr.captions && pr.captions.playerCaptionsTracklistRenderer;
              var tracks = r && r.captionTracks;
              if(!tracks || !tracks.length){ CairnCaptions.onResult('ERR:no-tracks'); return; }
              var t = pick(tracks);
              var url = t.baseUrl + (t.baseUrl.indexOf('?')>=0?'&':'?') + 'fmt=json3';
              fetch(url, {credentials:'include'})
                .then(function(resp){ return resp.text(); })
                .then(function(txt){ CairnCaptions.onResult(txt || 'ERR:empty'); })
                .catch(function(e){ CairnCaptions.onResult('ERR:fetch:'+e); });
            } catch(e){ CairnCaptions.onResult('ERR:'+e); }
          }
          var tries=0;
          var iv=setInterval(function(){
            tries++;
            if(window.ytInitialPlayerResponse || tries>24){ clearInterval(iv); run(); }
          }, 250);
        })();
    """.trimIndent()

    private companion object {
        const val TIMEOUT_MS = 40_000L
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}
