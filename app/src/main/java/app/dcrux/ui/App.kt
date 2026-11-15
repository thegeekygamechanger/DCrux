package app.dcrux.ui

import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import app.dcrux.ui.components.appBackground
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import app.dcrux.MainActivity
import app.dcrux.data.Settings
import app.dcrux.graph
import app.dcrux.lock.LockScreen
import app.dcrux.ui.home.HomeTab
import app.dcrux.ui.mind.MindFlowTab
import app.dcrux.ui.news.BrowserScreen
import app.dcrux.ui.news.NewsTab
import app.dcrux.ui.news.ReaderScreen
import app.dcrux.ui.news.SearchScreen
import app.dcrux.ui.settings.AboutScreen
import app.dcrux.ui.settings.SettingsScreen
import app.dcrux.ui.tests.PdfPreviewScreen
import app.dcrux.ui.tests.TestReviewScreen
import app.dcrux.ui.tests.TestRunScreen
import app.dcrux.ui.tests.TestsTab
import app.dcrux.ui.theme.DCruxTheme
import app.dcrux.ui.theme.isDark
import app.dcrux.ui.video.VideoScreen

@Composable
fun DCruxRoot(activity: MainActivity) {
    val g = activity.graph
    val loaded by g.prefs.flow.collectAsState(initial = null)
    val settings = loaded ?: return
    val nav = remember { Nav() }

    DCruxTheme(settings.themeMode, settings.dynamicColor) {
        val dark = isDark(settings.themeMode)
        DisposableEffect(dark) {
            val t = Color.Transparent.toArgb()
            activity.enableEdgeToEdge(
                statusBarStyle = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t),
                navigationBarStyle = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t),
            )
            onDispose { }
        }
        CompositionLocalProvider(LocalNav provides nav) {
            // A Surface (not a plain background) so text everywhere defaults to the theme's on-surface colour.
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
                AnimatedContent(
                    activity.locked.value,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith (fadeOut() + scaleOut(targetScale = 1.04f)) },
                    label = "lock",
                ) { locked ->
                    if (locked) LockScreen(settings.biometric) { activity.locked.value = false }
                    else AppNav(nav, settings, activity)
                }
            }
        }
    }
}

@Composable
private fun AppNav(nav: Nav, settings: Settings, activity: MainActivity) {
    val holder = rememberSaveableStateHolder()
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    AnimatedContent(
        targetState = nav.stack.size to nav.current,
        transitionSpec = {
            val forward = !nav.lastWasPop
            (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 320f)) { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally { if (forward) -it / 6 else it / 6 } + fadeOut())
        },
        label = "nav",
    ) { (depth, screen) ->
        holder.SaveableStateProvider("$depth:${screen.hashCode()}") {
            when (screen) {
                Screen.Tabs -> TabsScreen(nav, settings, activity)
                is Screen.VideoDetail -> VideoScreen(screen.id, settings)
                is Screen.Reader -> ReaderScreen(screen, settings)
                is Screen.Browser -> BrowserScreen(screen.url, screen.title, settings)
                Screen.Search -> SearchScreen(settings)
                Screen.About -> AboutScreen()
                is Screen.TestRun -> TestRunScreen(screen)
                is Screen.TestReview -> TestReviewScreen(screen.id, screen.fresh)
                is Screen.PdfPreview -> PdfPreviewScreen(screen.id)
            }
        }
    }
}

private data class TabSpec(val label: String, val on: ImageVector, val off: ImageVector)

private val tabs = listOf(
    TabSpec("Crux", Icons.Rounded.AutoAwesome, Icons.Outlined.AutoAwesome),
    TabSpec("Papers", Icons.Rounded.Newspaper, Icons.Outlined.Newspaper),
    TabSpec("Tests", Icons.Rounded.Quiz, Icons.Outlined.Quiz),
    TabSpec("Mind Flow", Icons.Rounded.SelfImprovement, Icons.Outlined.SelfImprovement),
    TabSpec("Settings", Icons.Rounded.Settings, Icons.Outlined.Settings),
)

@Composable
private fun TabsScreen(nav: Nav, settings: Settings, activity: MainActivity) {
    val holder = rememberSaveableStateHolder()
    Scaffold(
        modifier = Modifier.appBackground(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // A compact floating pill, only as wide as it needs to be: icons for every tab,
            // and the name spelled out for the one you are on.
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 10.dp, top = 4.dp), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.shadow(12.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEachIndexed { i, t ->
                        val selected = nav.tab == i
                        val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, label = "tabBg")
                        val tint by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, label = "tabTint")
                        val scale by animateFloatAsState(if (selected) 1.1f else 1f, spring(dampingRatio = 0.45f, stiffness = 380f), label = "tab")
                        Row(
                            Modifier.height(48.dp).clip(CircleShape).background(bg)
                                .selectable(selected = selected, role = Role.Tab, onClick = { nav.tab = i })
                                .animateContentSize(spring(dampingRatio = 0.8f, stiffness = 400f))
                                .padding(horizontal = if (selected) 15.dp else 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (selected) t.on else t.off, t.label, Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale }, tint = tint)
                            if (selected) Text(t.label, Modifier.padding(start = 8.dp), color = tint, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        },
    ) { pad ->
        AnimatedContent(
            nav.tab, Modifier.padding(pad).fillMaxSize(),
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.97f)) togetherWith fadeOut() }, label = "tabs",
        ) { tab ->
            holder.SaveableStateProvider("tab$tab") {
                when (tab) {
                    0 -> HomeTab(settings, activity)
                    1 -> NewsTab(settings)
                    2 -> TestsTab(settings)
                    3 -> MindFlowTab()
                    else -> SettingsScreen(settings, asTab = true)
                }
            }
        }
    }
}
