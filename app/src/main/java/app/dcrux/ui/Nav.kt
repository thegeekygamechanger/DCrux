package app.dcrux.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dcrux.data.QuizQuestion

sealed interface Screen {
    data object Tabs : Screen
    data class VideoDetail(val id: String) : Screen
    data class Reader(val title: String, val body: String, val meta: String = "", val link: String? = null, val relevance: String? = null, val note: String? = null) : Screen
    data class Browser(val url: String, val title: String) : Screen
    data object Search : Screen
    data object About : Screen
    data class TestRun(val title: String, val exam: String, val difficulty: String, val dayKey: String, val questions: List<QuizQuestion>) : Screen
    data class TestReview(val id: Long, val fresh: Boolean = false) : Screen
    data class PdfPreview(val id: Long) : Screen
}

/** A tiny back stack; enough for an app this size and free of surprises. */
class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Tabs)
    var tab by mutableIntStateOf(0)
    var lastWasPop by mutableStateOf(false)
        private set

    val current: Screen get() = stack.last()

    fun push(screen: Screen) {
        lastWasPop = false
        stack.add(screen)
    }

    fun replace(screen: Screen) {
        lastWasPop = false
        stack[stack.lastIndex] = screen
    }

    /** Settings lives in the bottom bar: return to the tabs and select it. */
    fun openSettings() {
        lastWasPop = true
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        tab = 4
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        lastWasPop = true
        stack.removeAt(stack.lastIndex)
        return true
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("No Nav") }

/** Top bar for pushed screens: round back button, title, actions. */
@Composable
fun ScreenBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilledTonalIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Text(
            title, Modifier.weight(1f).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}
