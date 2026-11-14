package app.dcrux.ui.news

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.dcrux.data.Settings
import app.dcrux.graph
import app.dcrux.ui.LocalNav
import app.dcrux.ui.Screen
import app.dcrux.ui.ScreenBar
import app.dcrux.ui.components.CruxCard
import app.dcrux.ui.components.Markdown
import app.dcrux.ui.components.PaginationBar
import app.dcrux.ui.components.RelevancePanel
import app.dcrux.ui.components.paperTurn
import app.dcrux.ui.theme.Heros
import app.dcrux.ui.theme.LocalExtra
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One topic of a video (or one section of an article): its heading and the text under it. */
private data class Topic(val title: String, val body: String)

/** Cuts the text at every top-level heading. Text before the first heading comes back with an empty title. */
private fun splitTopics(body: String): List<Topic> {
    val out = mutableListOf<Topic>()
    var title = ""
    val buf = StringBuilder()
    fun flush() {
        val text = buf.toString().trim()
        if (title.isNotBlank() || text.isNotBlank()) out += Topic(title, text)
        buf.setLength(0)
    }
    for (raw in body.lines()) {
        val line = raw.trim()
        if (line.startsWith("## ") || line.startsWith("# ")) {
            flush()
            title = line.trimStart('#').trim()
        } else buf.append(raw).append('\n')
    }
    flush()
    return out
}

/**
 * Read mode. Text with several topics opens as a book: a cover with the contents, then one
 * page per topic, turned like paper. Anything shorter is a single, quiet reading page.
 */
@Composable
fun ReaderScreen(screen: Screen.Reader, settings: Settings) {
    val g = LocalContext.current.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val dark = LocalExtra.current.dark
    val desk by animateColorAsState(if (dark) Color(0xFF0F0E13) else Color(0xFFEDE3D0), label = "desk")
    val paper = if (dark) Color(0xFF1D1C22) else Color(0xFFFBF5E9)
    val ink = if (dark) Color(0xFFE9E3D8) else Color(0xFF2B2622)
    val scale = settings.readerScale

    val parts = remember(screen.body) { splitTopics(screen.body) }
    val intro = remember(parts) { parts.firstOrNull { it.title.isBlank() }?.body.orEmpty() }
    val topics = remember(parts) { parts.filter { it.title.isNotBlank() } }
    val book = topics.size >= 2

    // Full screen: the system bars, the title bar and the page numbers step aside and the page takes the whole display.
    var full by rememberSaveable { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity
    DisposableEffect(full) {
        val window = activity?.window
        if (window != null) {
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            if (full) {
                bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                bars.hide(WindowInsetsCompat.Type.systemBars())
            } else bars.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) } }
    }
    BackHandler(enabled = full) { full = false }

    CompositionLocalProvider(LocalContentColor provides ink) {
        Box(Modifier.fillMaxSize().background(desk)) {
            Column(if (full) Modifier.fillMaxSize().padding(vertical = 8.dp) else Modifier.fillMaxSize().statusBarsPadding()) {
                if (!full) ScreenBar("Read mode", onBack = { nav.pop() }) {
                    IconButton(onClick = { full = true }) { Icon(Icons.Rounded.Fullscreen, "Full screen") }
                    IconButton(onClick = { scope.launch { g.prefs.setReaderScale((scale - 0.1f).coerceAtLeast(0.8f)) } }) { Icon(Icons.Rounded.TextDecrease, "Smaller text") }
                    IconButton(onClick = { scope.launch { g.prefs.setReaderScale((scale + 0.1f).coerceAtMost(1.6f)) } }) { Icon(Icons.Rounded.TextIncrease, "Larger text") }
                }

                if (!book) {
                    // A single reading page.
                    Column(
                        (if (full) Modifier.weight(1f).padding(horizontal = 8.dp) else Modifier.weight(1f).padding(horizontal = 12.dp).padding(bottom = 12.dp).navigationBarsPadding())
                            .clip(RoundedCornerShape(28.dp)).background(paper).verticalScroll(rememberScrollState()).padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Cover(screen, intro = screen.body, ink = ink, scale = scale, topics = emptyList(), onTopic = {})
                    }
                } else {
                    val pageCount = topics.size + 1
                    val pager = rememberPagerState { pageCount }
                    // The hint shows every time read mode opens; it goes away on Skip, on the first turn, or by itself.
                    var hint by remember { mutableStateOf(true) }
                    // The reader reopens on the page it was left at, so "a page other than the first" says
                    // nothing; only an actual swipe or page change after opening dismisses the hint.
                    LaunchedEffect(Unit) { snapshotFlow { pager.isScrollInProgress }.first { it }; hint = false }
                    LaunchedEffect(Unit) { snapshotFlow { pager.currentPage }.drop(1).first(); hint = false }
                    LaunchedEffect(Unit) { delay(7000); hint = false }

                    Box(Modifier.weight(1f)) {
                        HorizontalPager(pager, Modifier.fillMaxSize().clipToBounds(), beyondViewportPageCount = 1) { page ->
                            Box(Modifier.fillMaxSize().paperTurn(pager, page, settings.pageFlip).padding(horizontal = 12.dp, vertical = 4.dp)) {
                                Column(
                                    Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(paper)
                                        .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (page == 0) {
                                        Cover(screen, intro, ink, scale, topics) { i -> scope.launch { pager.animateScrollToPage(i + 1) } }
                                    } else {
                                        val topic = topics[page - 1]
                                        Text(
                                            "TOPIC $page OF ${topics.size}", color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                        Text(
                                            topic.title, color = ink,
                                            style = MaterialTheme.typography.headlineSmall.let { it.copy(fontFamily = Heros, fontSize = it.fontSize * scale, lineHeight = it.lineHeight * scale) },
                                        )
                                        HorizontalDivider(color = ink.copy(alpha = 0.25f))
                                        if (topic.body.isNotBlank()) Markdown(topic.body, scale = scale * 1.08f, color = ink)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        if (page == 0) "Cover" else "$page", Modifier.fillMaxWidth(), color = ink.copy(alpha = 0.5f),
                                        style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(hint, enter = fadeIn(), exit = fadeOut()) {
                            TurnHint(onSkip = { hint = false })
                        }
                    }
                    if (!full) PaginationBar(
                        pager.currentPage, pageCount,
                        onPage = { p -> scope.launch { pager.animateScrollToPage(p.coerceIn(0, pageCount - 1)) } },
                        modifier = Modifier.padding(vertical = 8.dp).navigationBarsPadding(),
                    )
                }
            }
            if (full) {
                FilledTonalIconButton(
                    onClick = { full = false },
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 14.dp, end = 18.dp).size(40.dp).alpha(0.85f),
                ) { Icon(Icons.Rounded.FullscreenExit, "Leave full screen") }
            }
        }
    }
}

/** The first page: where the text comes from, its title, any opening lines, the list of topics and the exam relevance. */
@Composable
private fun Cover(screen: Screen.Reader, intro: String, ink: Color, scale: Float, topics: List<Topic>, onTopic: (Int) -> Unit) {
    val nav = LocalNav.current
    if (screen.meta.isNotBlank()) Text(screen.meta, color = ink.copy(alpha = 0.65f), style = MaterialTheme.typography.labelMedium)
    Text(
        screen.title, color = ink,
        style = MaterialTheme.typography.headlineMedium.let { it.copy(fontFamily = Heros, fontSize = it.fontSize * scale, lineHeight = it.lineHeight * scale) },
    )
    if (intro.isNotBlank()) Markdown(intro, scale = scale * 1.08f, color = ink)
    if (topics.isNotEmpty()) {
        Text(if (screen.link?.contains("youtube.com/watch") == true) "Inside this video" else "Contents", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
        topics.forEachIndexed { i, t ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onTopic(i) }.padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.width(12.dp))
                Text(t.title, Modifier.weight(1f), color = ink, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (!screen.note.isNullOrBlank()) {
        Row {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.padding(top = 3.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(screen.note, color = ink, style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic))
        }
    }
    if (screen.relevance != null) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            CruxCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(18.dp)) {
                    Text("Exam relevance", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    RelevancePanel(screen.relevance)
                }
            }
        }
    }
    screen.link?.let { link ->
        if (!link.contains("youtube.com/watch")) {
            Text(
                "This is the publisher's headline and short description. The full article is on their site.",
                color = ink.copy(alpha = 0.65f), style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { nav.push(Screen.Browser(link, "Article")) }) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Read the full article")
            }
        }
    }
}

/**
 * A short lesson in turning the page: a sheet swings over on its spine while a fingertip
 * slides across, with a Skip button for anyone who already knows.
 */
@Composable
private fun TurnHint(onSkip: () -> Unit) {
    val t = rememberInfiniteTransition(label = "hint")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1900, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "turn")
    val dark = LocalExtra.current.dark
    val sheet = if (dark) Color(0xFF2B2A31) else Color(0xFFFFFBF2)
    val under = if (dark) Color(0xFF3A3941) else Color(0xFFE9DFCB)
    Box(
        // No touch handling here on purpose: a swipe goes straight through to the pages underneath.
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Box(Modifier.size(width = 170.dp, height = 190.dp), contentAlignment = Alignment.Center) {
                // the page underneath
                Column(
                    Modifier.size(width = 120.dp, height = 160.dp).clip(RoundedCornerShape(14.dp)).background(under).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Box(Modifier.fillMaxWidth(0.55f).height(9.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)))
                    repeat(6) { i -> Box(Modifier.fillMaxWidth(if (i % 3 == 1) 0.7f else 1f).height(6.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.35f))) }
                }
                // the page being turned
                Column(
                    Modifier.size(width = 120.dp, height = 160.dp)
                        .graphicsLayer {
                            transformOrigin = TransformOrigin(0f, 0.5f)
                            cameraDistance = 20f * density
                            rotationY = -150f * p
                            alpha = if (p > 0.92f) (1f - p) / 0.08f else 1f
                        }
                        .clip(RoundedCornerShape(14.dp)).background(sheet).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Box(Modifier.fillMaxWidth(0.7f).height(9.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)))
                    repeat(6) { i -> Box(Modifier.fillMaxWidth(if (i % 3 == 2) 0.6f else 1f).height(6.dp).clip(CircleShape).background(Color.Gray.copy(alpha = 0.45f))) }
                }
                // the fingertip that does the turning
                Icon(
                    Icons.Rounded.TouchApp, null, tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).size(44.dp).offset(x = (58 - 116 * p).dp, y = 6.dp)
                        .graphicsLayer { alpha = if (p < 0.1f) p / 0.1f else if (p > 0.85f) (1f - p) / 0.15f else 1f },
                )
            }
            Spacer(Modifier.height(18.dp))
            Text("Swipe to turn the page", color = Color.White, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                "Every topic is its own page. Swipe left for the next one, right to go back.",
                color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            FilledTonalButton(onClick = onSkip) { Text("Skip") }
        }
    }
}
