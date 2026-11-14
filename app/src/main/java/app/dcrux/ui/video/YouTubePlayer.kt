package app.dcrux.ui.video

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import app.dcrux.BuildConfig
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.dcrux.data.AppJson
import app.dcrux.net.Http
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream

class PlayerController {
    internal var web: WebView? = null

    /** True while captions are being read; the player must not be paused then, even in the background. */
    @Volatile var busy = false
    private fun js(code: String) {
        web?.let { w -> w.post { w.evaluateJavascript(code, null) } }
    }

    /** Starts muted playback and asks the player for the named caption track. */
    fun grabCaptions(lang: String, kind: String, vssId: String) {
        fun q(v: String) = "'" + v.replace(Regex("[^A-Za-z0-9._-]"), "") + "'"
        js("grabCaptions(${q(lang)},${q(kind)},${q(vssId)})")
    }
    fun doneCaptions(restore: Boolean) = js("doneCaptions($restore)")
    fun pause() = js("try{player.pauseVideo()}catch(e){}")
}

class PlayerBridge(
    private val post: (() -> Unit) -> Unit,
    private val onReady: () -> Unit,
    private val onState: (Int) -> Unit,
    private val onError: (Int) -> Unit,
) {
    @JavascriptInterface fun ready() = post { onReady() }
    @JavascriptInterface fun state(s: Int) = post { onState(s) }
    @JavascriptInterface fun error(code: Int) = post { onError(code) }
    @JavascriptInterface fun log(message: String) { if (BuildConfig.DEBUG) Log.d("DCruxPlayer", message) }
}

private fun playerHtml(videoId: String) = """
<!DOCTYPE html><html><head>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{position:absolute;top:0;left:0;width:100%;height:100%}</style>
</head><body><div id="p"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player, wantCaps=false, wanted=null;
function fit(){ try{ var f=player.getIframe(); f.style.width=window.innerWidth+'px'; f.style.height=window.innerHeight+'px'; }catch(x){} }
window.addEventListener('resize', fit);
function applyTrack(){
  try{
    player.loadModule('captions');
    if(wanted){ player.setOption('captions','track',wanted); }
  }catch(x){ DCrux.log('applyTrack '+x); }
}
function onYouTubeIframeAPIReady(){
  player=new YT.Player('p',{videoId:'$videoId',width:window.innerWidth,height:window.innerHeight,
    playerVars:{playsinline:1,controls:1,fs:1,rel:0,enablejsapi:1,origin:location.origin},
    events:{
      onReady:function(){fit(); DCrux.ready();},
      onStateChange:function(e){DCrux.state(e.data); if(wantCaps&&e.data==1){applyTrack();}},
      onError:function(e){DCrux.error(e.data);},
      onApiChange:function(){ if(wantCaps){applyTrack();} }
    }});
}
function grabCaptions(lang,kind,vss){
  wanted={languageCode:lang}; if(kind){wanted.kind=kind;} if(vss){wanted.vss_id=vss;}
  wantCaps=true;
  try{ player.mute(); player.playVideo(); applyTrack(); }catch(x){ DCrux.log('grab '+x); }
}
function doneCaptions(restore){
  wantCaps=false;
  try{ if(restore){ player.pauseVideo(); player.seekTo(0,true); player.unloadModule('captions'); } player.unMute(); }catch(x){}
}
</script></body></html>
""".trimIndent()

/** Turns a caption track response (json3 or XML) into plain text. */
internal fun parseCaptions(body: String): String {
    val t = body.trim()
    val text = if (t.startsWith("{")) {
        runCatching {
            AppJson.parseToJsonElement(t).jsonObject["events"]?.jsonArray.orEmpty().joinToString(" ") { ev ->
                ev.jsonObject["segs"]?.jsonArray.orEmpty().joinToString("") { it.jsonObject["utf8"]?.jsonPrimitive?.content.orEmpty() }
            }
        }.getOrDefault("")
    } else {
        runCatching { Jsoup.parse(t).select("text, p").joinToString(" ") { it.text() } }.getOrDefault("")
    }
    return text.replace(Regex("\\s+"), " ").trim()
}

/**
 * The official YouTube IFrame player in a WebView: every YouTube control (quality, speed,
 * captions, fullscreen) works, and the app adds no ads of its own.
 *
 * When the player loads its caption track, the same response is read here so Claude can
 * summarise what was actually said. Nothing is fetched that the player did not request.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayer(
    videoId: String,
    controller: PlayerController,
    modifier: Modifier = Modifier,
    onReady: () -> Unit = {},
    onState: (Int) -> Unit = {},
    onError: (Int) -> Unit = {},
    onTranscript: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val ready by rememberUpdatedState(onReady)
    val state by rememberUpdatedState(onState)
    val error by rememberUpdatedState(onError)
    val transcript by rememberUpdatedState(onTranscript)
    var fullscreen by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }

    fun exitFullscreen() {
        val a = activity ?: return
        fullscreen?.let { (a.window.decorView as ViewGroup).removeView(it) }
        fullscreen = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowCompat.getInsetsController(a.window, a.window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    BackHandler(enabled = fullscreen != null) { exitFullscreen() }

    val web = remember(videoId) {
        WebView(context).apply {
            // A WebView without fixed layout sizing reports a zero-height page, which collapses the player.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(PlayerBridge({ block -> post(block) }, { ready() }, { state(it) }, { error(it) }), "DCrux")
            if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (BuildConfig.DEBUG) Log.d("DCruxPlayer", "console: ${m.message()}")
                    return true
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    val a = activity ?: return callback.onCustomViewHidden()
                    if (fullscreen != null) return callback.onCustomViewHidden()
                    view.setBackgroundColor(Color.BLACK)
                    (a.window.decorView as ViewGroup).addView(view, FrameLayout.LayoutParams(-1, -1))
                    fullscreen = view
                    fullscreenCallback = callback
                    a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    WindowCompat.getInsetsController(a.window, a.window.decorView).apply {
                        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    }
                }

                override fun onHideCustomView() = exitFullscreen()
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Links out of the player (the YouTube logo, cards) open in the YouTube app or browser.
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url: Uri = request.url
                    if (request.method != "GET" || url.host?.endsWith("youtube.com") != true || url.path != "/api/timedtext") return null
                    return try {
                        val builder = Request.Builder().url(url.toString())
                        request.requestHeaders.forEach { (k, v) -> builder.header(k, v) }
                        CookieManager.getInstance().getCookie(url.toString())?.let { builder.header("Cookie", it) }
                        Http.client.newCall(builder.build()).execute().use { res ->
                            val bytes = res.body.bytes()
                            if (BuildConfig.DEBUG) Log.d("DCruxPlayer", "timedtext ${res.code} ${bytes.size} bytes ${url.query?.take(160)}")
                            if (res.isSuccessful && bytes.isNotEmpty()) {
                                val text = parseCaptions(String(bytes, Charsets.UTF_8))
                                if (text.length > 200) view.post { transcript(text) }
                            }
                            val headers = res.headers.toMultimap().mapValues { it.value.joinToString(",") }
                            val type = res.body.contentType()
                            WebResourceResponse(
                                type?.let { "${it.type}/${it.subtype}" } ?: "application/json", "utf-8",
                                res.code, res.message.ifBlank { "OK" }, headers, ByteArrayInputStream(bytes),
                            )
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            // The base URL is the app's identity for the embedded player (YouTube requires a referrer).
            loadDataWithBaseURL("https://${context.packageName}", playerHtml(videoId), "text/html", "utf-8", null)
        }
    }

    DisposableEffect(web) {
        controller.web = web
        onDispose {
            if (fullscreen != null) exitFullscreen()
            controller.web = null
            web.stopLoading()
            web.loadUrl("about:blank")
            web.destroy()
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, web) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> if (!controller.busy) { controller.pause(); web.onPause() }
                Lifecycle.Event.ON_RESUME -> web.onResume()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    AndroidView(factory = { web }, modifier = modifier)
}
