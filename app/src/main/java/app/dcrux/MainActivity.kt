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

class MainActivity : FragmentActivity() {
    /** True while the lock screen must cover the app. */
    val locked = mutableStateOf(false)

    /** Text shared into the app from another app (a YouTube link). */
    val sharedText = mutableStateOf<String?>(null)

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

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        // Re-lock after the app has been in the background for a short while.
        if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 20_000) {
            lifecycleScope.launch {
                if (graph.prefs.get().lockEnabled && graph.secure.hasPin()) locked.value = true
            }
        }
    }
}
