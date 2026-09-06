@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.picker

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val PICKER_JS = """
(function(){
  if(window.__cairnPicker) return; window.__cairnPicker=true;
  var s=document.createElement('style');
  s.innerHTML='.cairn-hl{outline:2px solid #1f7a83 !important;outline-offset:1px;background:rgba(31,122,131,.14) !important;}';
  document.head.appendChild(s);
  function clsSel(el){
    var t=el.tagName.toLowerCase();
    var raw=(el.getAttribute('class')||'').split(/\s+/);
    var c=null;
    for(var i=0;i<raw.length;i++){var x=raw[i]; if(x && !/(active|selected|current|open|hover|focus)/.test(x)){c=x;break;}}
    try{ return c ? t+'.'+CSS.escape(c) : t; }catch(e){ return c ? t+'.'+c : t; }
  }
  function build(el){
    var a=el.closest('a')||el;
    var sel=clsSel(a);
    if(document.querySelectorAll(sel).length<2 && a.parentElement){
      sel=clsSel(a.parentElement)+' '+a.tagName.toLowerCase();
    }
    return sel;
  }
  function clear(){var e=document.querySelectorAll('.cairn-hl');for(var i=0;i<e.length;i++)e[i].classList.remove('cairn-hl');}
  document.addEventListener('click',function(ev){
    ev.preventDefault(); ev.stopPropagation();
    var sel=build(ev.target);
    clear();
    var m=document.querySelectorAll(sel);
    for(var i=0;i<m.length;i++)m[i].classList.add('cairn-hl');
    if(window.CairnPicker) CairnPicker.pick(sel, m.length);
  }, true);
})();
"""

/**
 * Teach-by-example picker: the page loads in a WebView; tapping a headline computes a CSS
 * selector for that repeating element, highlights every match, and offers to build a feed
 * from those links. All on device — the page load is the only network touch.
 */
@Composable
fun SelectorPickerScreen(
    url: String,
    onBack: () -> Unit,
    onCreated: () -> Unit,
    viewModel: SelectorPickerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var selector by remember { mutableStateOf<String?>(null) }
    var matches by remember { mutableIntStateOf(0) }
    val main = remember { Handler(Looper.getMainLooper()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tap_a_headline), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close)) } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        when {
                            selector == null -> "Tap an article headline on the page. Cairn will find every similar link."
                            matches < 2 -> "Only $matches match — try tapping the headline text itself."
                            else -> "$matches similar links found."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val sel = selector ?: return@Button
                            viewModel.create(url, sel) { ok, msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                if (ok) onCreated()
                            }
                        },
                        enabled = selector != null && matches >= 2 && !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) {
                        Text(if (busy) "Creating…" else "Create feed from ${matches.coerceAtLeast(0)} links")
                    }
                }
            }
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    with(settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        allowFileAccess = false
                        allowContentAccess = false
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun pick(sel: String, count: Int) { main.post { selector = sel; matches = count } }
                    }, "CairnPicker")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, u: String?) {
                            view?.evaluateJavascript(PICKER_JS, null)
                        }
                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: android.webkit.RenderProcessGoneDetail?,
                        ): Boolean {
                            runCatching { (view?.parent as? android.view.ViewGroup)?.removeView(view); view?.destroy() }
                            onBack()
                            return true
                        }
                    }
                    loadUrl(url)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}
