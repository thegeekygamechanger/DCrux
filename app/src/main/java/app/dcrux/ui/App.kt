package app.dcrux.ui

import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import app.dcrux.ui.components.LocalWide
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import app.dcrux.LocalGraph
import app.dcrux.Host
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
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import app.dcrux.data.Settings
import app.dcrux.lock.LockScreen
import app.dcrux.ui.home.HomeTab
import app.dcrux.ui.mind.MindFlowTab
import app.dcrux.ui.news.BrowserScreen
import app.dcrux.ui.news.NewsTab
import app.dcrux.ui.news.ReaderScreen
import app.dcrux.ui.news.SearchSheet
import app.dcrux.ui.home.Tour
import app.dcrux.ui.tests.MainsRunScreen
import app.dcrux.ui.settings.AboutScreen
import app.dcrux.ui.settings.HistoryScreen
import app.dcrux.ui.settings.SettingsScreen
import app.dcrux.ui.tests.PdfPreviewScreen
import app.dcrux.ui.tests.TestReviewScreen
import app.dcrux.ui.tests.TestRunScreen
import app.dcrux.ui.tests.TestsTab
import app.dcrux.ui.theme.DCruxTheme
import app.dcrux.ui.theme.isDark
import app.dcrux.ui.video.VideoScreen

@Composable
fun DCruxRoot(host: Host) {
    val g = host.graph
    val loaded by g.prefs.flow.collectAsState(initial = null)
    val settings = loaded ?: return
    val nav = remember { Nav() }

    CompositionLocalProvider(LocalGraph provides g) {
    DCruxTheme(settings.themeMode, settings.dynamicColor) {
        val dark = isDark(settings.themeMode)
        DisposableEffect(dark) {
            host.systemBars(dark)
            onDispose { }
        }
        CompositionLocalProvider(LocalNav provides nav) {
            // A Surface (not a plain background) so text everywhere defaults to the theme's on-surface colour.
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
              BoxWithConstraints(Modifier.fillMaxSize()) {
                // A computer window wide enough for it gets the landscape layout; narrowed, it falls back to the phone's.
                val wide = g.platform.isDesktop && maxWidth >= 900.dp
                CompositionLocalProvider(LocalWide provides wide) {
                AnimatedContent(
                    host.locked.value,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)) togetherWith (fadeOut() + scaleOut(targetScale = 1.04f)) },
                    label = "lock",
                ) { locked ->
                    if (locked) LockScreen(settings.biometric) { host.locked.value = false }
                    else AppNav(nav, settings, host)
                }
                }
              }
            }
        }
    }
    }
}

@Composable
private fun AppNav(nav: Nav, settings: Settings, host: Host) {
    val holder = rememberSaveableStateHolder()
    OnBack(enabled = nav.stack.size > 1) { nav.pop() }
    if (nav.searchOpen) SearchSheet(settings) { nav.searchOpen = false }
    val g = host.graph
    val resumeMains by g.mains.resumeNow.collectAsState()
    LaunchedEffect(resumeMains) {
        if (resumeMains) {
            g.mains.resumeNow.value = false
            if (nav.current != Screen.MainsRun && g.mains.draft.value != null) { nav.tab = 2; nav.push(Screen.MainsRun) }
        }
    }
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
            val page: @Composable () -> Unit = {
            when (screen) {
                Screen.Tabs -> TabsScreen(nav, settings, host)
                is Screen.VideoDetail -> VideoScreen(screen.id, settings)
                is Screen.Reader -> ReaderScreen(screen, settings)
                is Screen.Browser -> BrowserScreen(screen.url, screen.title, settings)
                Screen.About -> AboutScreen()
                Screen.History -> HistoryScreen(settings)
                is Screen.TestRun -> TestRunScreen(screen)
                is Screen.TestReview -> TestReviewScreen(screen)
                Screen.MainsRun -> MainsRunScreen()
                is Screen.PdfPreview -> PdfPreviewScreen(screen.id)
            }
            }
            // In landscape, pages meant for reading keep a comfortable width in the middle of the window.
            if (LocalWide.current && screen != Screen.Tabs) {
                Box(Modifier.fillMaxSize().appBackground(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 1040.dp).fillMaxHeight()) { page() }
                }
            } else page()
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
private fun TabsScreen(nav: Nav, settings: Settings, host: Host) {
    val holder = rememberSaveableStateHolder()
    // No bar area: the tab fills the whole screen and the pill floats over it, so nothing
    // is cut off above the bar and there is no band of background behind it.
    val wide = LocalWide.current
    Box(Modifier.fillMaxSize().appBackground()) {
        AnimatedContent(
            nav.tab, if (wide) Modifier.fillMaxSize().padding(start = 212.dp) else Modifier.fillMaxSize(),
            transitionSpec = {
                if (!wide) (fadeIn() + scaleIn(initialScale = 0.97f)) togetherWith fadeOut()
                else {
                    // Beside a vertical rail the page follows the highlight: it eases in from the side you moved towards.
                    val dir = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(240, delayMillis = 50)) + slideInVertically(spring(dampingRatio = 0.9f, stiffness = 300f)) { dir * it / 16 }) togetherWith
                        (fadeOut(tween(110)) + slideOutVertically(tween(160)) { -dir * it / 24 })
                }
            },
            label = "tabs",
        ) { tab ->
            holder.SaveableStateProvider("tab$tab") {
                when (tab) {
                    0 -> HomeTab(settings, host)
                    1 -> NewsTab(settings)
                    2 -> TestsTab(settings)
                    3 -> MindFlowTab()
                    else -> SettingsScreen(settings, asTab = true)
                }
            }
        }

        // Landscape: the same five places as a rail down the left side, each with its name.
        if (wide) Box(
            Modifier.align(Alignment.CenterStart).padding(start = 16.dp).width(180.dp).clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(32.dp)).padding(8.dp),
        ) {
            // One highlight that glides from the place you were to the place you chose, as the pill does on a phone.
            val slot = 56.dp
            val at by animateDpAsState(slot * nav.tab, spring(dampingRatio = 0.72f, stiffness = 380f), label = "railAt")
            Box(Modifier.offset(y = at).fillMaxWidth().height(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tabs.forEachIndexed { i, t ->
                    val selected = nav.tab == i
                    val tint by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, label = "railTint")
                    val scale by animateFloatAsState(if (selected) 1.12f else 1f, spring(dampingRatio = 0.45f, stiffness = 380f), label = "railIcon")
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).clip(CircleShape)
                            .selectable(selected = selected, role = Role.Tab, onClick = { nav.tab = i }).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (selected) t.on else t.off, null, Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale }, tint = tint)
                        Text(t.label, Modifier.padding(start = 12.dp), color = tint, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
            }
        }

            // A compact floating pill, only as wide as it needs to be: icons for every tab,
        // and the name spelled out for the one you are on.
        if (!wide) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier.shadow(12.dp, CircleShape).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape).padding(6.dp),
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

        // Shown once after install (and once after the update that brought pull-to-sync).
        if (!settings.tourDone) Tour(onTab = { nav.tab = it })
    }
}
