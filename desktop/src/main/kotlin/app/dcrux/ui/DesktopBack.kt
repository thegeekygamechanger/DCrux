package app.dcrux.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** On a computer "back" is the Esc key (and the back arrow every pushed screen already shows). The newest handler on screen answers it. */
object BackStack {
    private val handlers = ArrayDeque<() -> Unit>()
    fun push(h: () -> Unit) { handlers.addLast(h) }
    fun remove(h: () -> Unit) { handlers.remove(h) }

    /** Runs the newest handler; false when nothing wants the key. */
    fun back(): Boolean = handlers.lastOrNull()?.let { it(); true } ?: false
}

/** Runs [onBack] when the user goes back while [enabled]. */
@Composable
fun OnBack(enabled: Boolean = true, onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        val handler = { current() }
        if (enabled) BackStack.push(handler)
        onDispose { BackStack.remove(handler) }
    }
}
