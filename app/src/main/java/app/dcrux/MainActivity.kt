package app.dcrux

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.dcrux.ui.DCruxRoot
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity(), Host {
    /** True while the lock screen must cover the app. */
    override val locked = mutableStateOf(false)

    /** Text shared into the app from another app (a YouTube link). */
    override val sharedText = mutableStateOf<String?>(null)

    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        var ready = false
        splash.setKeepOnScreenCondition { !ready }
        lifecycleScope.launch {
            locked.value = graph.prefs.get().lockEnabled && graph.secure.hasPin()
            ready = true
        }
        readShare(intent)
        (application as DCruxApp).platform.activity = java.lang.ref.WeakReference(this)
        setContent { DCruxRoot(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readShare(intent)
    }

    private fun readShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText.value = it }
        }
    }

    override val graph: Graph get() = (application as DCruxApp).graph

    override fun systemBars(dark: Boolean) {
        val t = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = if (dark) androidx.activity.SystemBarStyle.dark(t) else androidx.activity.SystemBarStyle.light(t, t),
            navigationBarStyle = if (dark) androidx.activity.SystemBarStyle.dark(t) else androidx.activity.SystemBarStyle.light(t, t),
        )
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        (application as DCruxApp).platform.activity = java.lang.ref.WeakReference(this)
        graph.updates.onAppForeground()
        // Re-lock after the app has been in the background for a short while.
        if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 20_000) {
            lifecycleScope.launch {
                if (graph.prefs.get().lockEnabled && graph.secure.hasPin()) locked.value = true
            }
        }
    }
}
