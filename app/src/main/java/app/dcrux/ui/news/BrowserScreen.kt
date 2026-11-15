package app.dcrux.ui.news

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.dcrux.ui.components.appBackground
import app.dcrux.data.Settings
import app.dcrux.net.AdBlock
import app.dcrux.ui.LocalNav
import app.dcrux.ui.Screen
import app.dcrux.ui.ScreenBar
import app.dcrux.ui.components.ReadModeButton
import app.dcrux.data.AppJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import java.io.ByteArrayInputStream

/**
 * A plain in-app browser for reading articles and e-papers on the publisher's own site.
 * Pages are never cached and nothing is downloaded: the page cache is cleared when the
 * screen closes. Cookies are kept so a subscription login is remembered.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(url: String, title: String, settings: Settings) {
    val context = LocalContext.current
    val nav = LocalNav.current
    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf(title) }
    var canGoBack by remember { mutableStateOf(false) }
    var readerMessage by remember { mutableStateOf<String?>(null) }
    val blockAds = settings.blockAds

    val web = remember(url) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            this.settings.javaScriptEnabled = true
            this.settings.domStorageEnabled = true
            this.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            this.settings.builtInZoomControls = true
            this.settings.displayZoomControls = false
            this.settings.loadWithOverviewMode = true
            this.settings.useWideViewPort = true
            CookieManager.getInstance().setAcceptCookie(true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                override fun onReceivedTitle(view: WebView, t: String?) { if (!t.isNullOrBlank()) pageTitle = t }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme.orEmpty()
                    if (scheme == "http" || scheme == "https") return false
                    // App links, mail and the like go to whichever app handles them.
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    if (blockAds && !request.isForMainFrame && AdBlock.blocks(request.url.host))
                        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    else null

                override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) { canGoBack = view.canGoBack() }
                override fun onPageFinished(view: WebView, u: String?) {
                    canGoBack = view.canGoBack(); progress = 100
                    if (blockAds) view.evaluateJavascript(HIDE_ADS_JS, null)
                }
            }
            // Files are never saved by DCrux; a download link is handed to the system instead.
            setDownloadListener { link, _, _, _, _ ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            loadUrl(url)
        }
    }

    DisposableEffect(web) {
        onDispose {
            web.stopLoading()
            web.clearCache(true)
            web.clearHistory()
            web.clearFormData()
            web.loadUrl("about:blank")
            web.destroy()
        }
    }

    BackHandler { if (web.canGoBack()) web.goBack() else nav.pop() }

    Column(Modifier.fillMaxSize().appBackground().statusBarsPadding().navigationBarsPadding()) {
        ScreenBar(pageTitle, onBack = { nav.pop() }) {
            ReadModeButton(onClick = {
                // Read mode reformats the page that is already open, on the phone; nothing is stored.
                web.evaluateJavascript(READER_JS) { raw ->
                    val article = runCatching {
                        AppJson.parseToJsonElement(AppJson.decodeFromString<String>(raw)).jsonObject
                    }.getOrNull()
                    val body = article?.get("body")?.jsonPrimitive?.content.orEmpty()
                    if (body.length < 200) readerMessage = "Read mode is not available for this page."
                    else nav.push(Screen.Reader(article?.get("title")?.jsonPrimitive?.content ?: pageTitle, body, Uri.parse(web.url ?: url).host.orEmpty()))
                }
            })
            IconButton(onClick = { web.reload() }) { Icon(Icons.Rounded.Refresh, "Reload") }
            IconButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web.url ?: url))) }
            }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Open in browser") }
        }
        readerMessage?.let {
            Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        Box(Modifier.fillMaxWidth().height(6.dp)) {
            if (progress in 1..99) LinearWavyProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
    }
}

/** Collapses the empty boxes left behind when ad requests are blocked. */
private const val HIDE_ADS_JS = """
(function(){
  if (document.getElementById('dcrux-clean')) return;
  var st = document.createElement('style'); st.id = 'dcrux-clean';
  st.textContent = 'ins.adsbygoogle,[id^="div-gpt-ad"],[id*="google_ads"],iframe[src*="doubleclick"],iframe[id^="google_ads"],[aria-label="Advertisement"],.advertisement,.ad-container,.adsbygoogle,[class*="adslot"],[class*="ad-slot"],[class*="adunit"],[class*="ad-unit"],[data-ad-slot],[data-google-query-id]{display:none!important;height:0!important;min-height:0!important}';
  document.documentElement.appendChild(st);
  var all = document.querySelectorAll('div,span,p');
  for (var i = 0; i < all.length; i++) {
    var e = all[i];
    if (e.children.length === 0 && /^advertisement$/i.test((e.textContent || '').trim())) {
      var box = e.parentElement && e.parentElement.innerText.trim().length < 20 ? e.parentElement : e;
      box.style.setProperty('display', 'none', 'important');
    }
  }
})();
"""

/** Picks the block with the most paragraph text on the open page and returns it as plain text. */
private const val READER_JS = """
(function(){
  function txt(e){ return (e.innerText || '').replace(/\s+/g, ' ').trim(); }
  var best = null, score = 0;
  var cands = document.querySelectorAll('article,[itemprop="articleBody"],main,section,div');
  for (var i = 0; i < cands.length; i++) {
    var ps = cands[i].querySelectorAll(':scope > p, :scope > div > p');
    var s = 0;
    for (var j = 0; j < ps.length; j++) { var l = txt(ps[j]).length; if (l > 60) s += l; }
    if (s > score) { score = s; best = cands[i]; }
  }
  var out = [];
  if (best) {
    var nodes = best.querySelectorAll('h2,h3,p,li');
    for (var k = 0; k < nodes.length; k++) {
      var n = nodes[k];
      if (n.closest('aside,nav,footer,figure,form,button,[class*="related"],[class*="comment"],[class*="share"],[class*="subscribe"]')) continue;
      var t = txt(n);
      if (t.length < 2) continue;
      if (n.tagName === 'H2' || n.tagName === 'H3') out.push('## ' + t);
      else if (n.tagName === 'LI') out.push('- ' + t);
      else out.push(t);
    }
  }
  var h = document.querySelector('h1');
  return JSON.stringify({ title: h ? txt(h) : document.title, body: out.join('\n\n') });
})()
"""
