@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.web

import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/** Base64 (URL-safe) transport for a URL through a nav route argument, avoiding
 *  any reserved-character trouble with encoding a full URL into a path segment. */
object WebRoute {
    fun encode(url: String): String =
        Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun decode(data: String): String =
        runCatching { String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)) }.getOrDefault("")
}

/**
 * The original web page, shown inside Cairn instead of an external browser. Sandboxed:
 * third-party cookies blocked, no file/content access, dark-mode applied when the theme
 * is dark. The native reader remains the default surface — this is only for "Original".
 */
@Composable
fun WebScreen(url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    fun openExternally() {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(pageTitle.ifBlank { url }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.Close, contentDescription = "Close") } },
                    actions = {
                        IconButton(onClick = { webView?.reload() }) { Icon(Icons.Outlined.Refresh, contentDescription = "Reload") }
                        IconButton(onClick = ::openExternally) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open in browser") }
                    },
                )
                if (progress in 1..99) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx ->
                WebView(ctx).apply {
                    webView = this
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    with(settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        allowFileAccess = false
                        allowContentAccess = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    if (dark && WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val scheme = request?.url?.scheme ?: return false
                            if (scheme == "http" || scheme == "https") return false
                            // Non-web links (mailto:, tel:, intent:, market:) go to the system.
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            return true
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) { progress = newProgress }
                        override fun onReceivedTitle(view: WebView?, title: String?) { pageTitle = title.orEmpty() }
                    }
                    loadUrl(url)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
