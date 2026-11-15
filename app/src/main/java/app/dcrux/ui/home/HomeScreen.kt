package app.dcrux.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import app.dcrux.net.WeatherRepo
import app.dcrux.sync.STOPPED
import app.dcrux.ui.theme.LocalExtra
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import java.util.Date
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dcrux.MainActivity
import app.dcrux.data.Channel
import app.dcrux.data.Dates
import app.dcrux.data.Quotes
import app.dcrux.data.Settings
import app.dcrux.data.Video
import app.dcrux.graph
import app.dcrux.ui.LocalNav
import app.dcrux.ui.Screen
import app.dcrux.ui.components.bottomBarInset
import app.dcrux.ui.components.CruxCard
import app.dcrux.ui.settings.UpdatePanel
import app.dcrux.update.UpdateState
import app.dcrux.ui.components.EmptyState
import app.dcrux.ui.components.PaginationBar
import app.dcrux.ui.components.PhotoCarousel
import app.dcrux.ui.components.Slide
import app.dcrux.ui.components.TabTitle
import app.dcrux.ui.components.RelevancePill
import app.dcrux.ui.components.Tag
import app.dcrux.ui.theme.BrandColors
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeTab(settings: Settings, activity: MainActivity) {
    val context = LocalContext.current
    val g = context.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val sync by g.sync.state.collectAsState()
    val channels by g.db.channels().observeAll().collectAsState(initial = null)
    var shareMessage by remember { mutableStateOf<String?>(null) }
    var askChannel by remember { mutableStateOf(false) }

    if (askChannel) AlertDialog(
        onDismissRequest = { askChannel = false },
        title = { Text("Add a channel first") },
        text = { Text("There is nothing to sync yet. Paste a YouTube channel or video link in Settings, or start with the default channel.") },
        confirmButton = { Button(onClick = { askChannel = false; nav.openSettings() }) { Text("Add a channel") } },
        dismissButton = { TextButton(onClick = { askChannel = false; g.sync.restoreDefault() }) { Text("Use the default") } },
    )

    // A link shared from the YouTube app lands here and is synced straight away.
    LaunchedEffect(activity.sharedText.value) {
        activity.sharedText.value?.let { shared ->
            activity.sharedText.value = null
            shareMessage = "Adding the shared link"
            val report = g.sync.addLinks(shared)
            shareMessage = when {
                report.added.isNotEmpty() -> "Added " + report.added.joinToString(", ")
                else -> report.failed.firstOrNull()?.second ?: "Could not add that link"
            }
        }
    }

    // The sync line appears only while a sync runs, says "Synced" for a moment, and then steps aside.
    var justSynced by remember { mutableStateOf(false) }
    var pulled by remember { mutableStateOf(false) }
    var wasRunning by remember { mutableStateOf(sync.running) }
    LaunchedEffect(sync.running) {
        if (wasRunning && !sync.running) {
            pulled = false
            if (sync.error == null) { justSynced = true; delay(2800); justSynced = false }
        }
        wasRunning = sync.running
    }
    LaunchedEffect(shareMessage) { if (shareMessage != null && shareMessage != "Adding the shared link") { delay(5000); shareMessage = null } }
    var errorHidden by remember(sync.error, sync.finishedAt) { mutableStateOf(false) }

    // Weather for the header: refreshed when it is more than half an hour old.
    var weatherOpen by remember { mutableStateOf(false) }
    var weatherTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(settings.place, weatherTick) {
        val place = settings.place ?: return@LaunchedEffect
        // A reading saved by an older version (US index, no source noted) is replaced straight away.
        val fresh = settings.weather?.let { System.currentTimeMillis() - it.at < 30 * 60_000L && (it.aqi < 0 || it.aqiSource.isNotBlank()) } == true
        if (fresh && weatherTick == 0) return@LaunchedEffect
        // Runs in the app's scope so that leaving the screen mid-way does not lose the answer; one retry for a slow start.
        g.scope.launch {
            val got = runCatching { g.weather.fetch(place) }.getOrNull() ?: run { delay(4000); runCatching { g.weather.fetch(place) }.getOrNull() }
            if (got != null && g.prefs.get().place == place) g.prefs.setWeather(got)
        }
    }
    if (weatherOpen) WeatherSheet(settings) { weatherOpen = false }

    PullToRefreshBox(
        isRefreshing = pulled && sync.running,
        onRefresh = {
            weatherTick++
            if (channels?.isEmpty() == true) askChannel = true
            else { pulled = true; g.sync.syncNow() }
        },
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
    ) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp + bottomBarInset()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("bar") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TabTitle("DCrux", Modifier.weight(1f))
                FilledTonalIconButton(onClick = { nav.searchOpen = true }) { Icon(Icons.Rounded.Search, "Search") }
            }
        }

        item("today") { TodayCard(settings) { weatherOpen = true } }

        item("status") {
            val showError = sync.error != null && !errorHidden && !sync.running
            AnimatedVisibility(
                sync.running || justSynced || showError || shareMessage != null,
                enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(),
            ) {
                Column(Modifier.animateContentSize()) {
                    when {
                        sync.running -> {
                            LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(sync.message.ifBlank { "Syncing" }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                TextButton(onClick = { g.sync.stop() }) { Icon(Icons.Rounded.Stop, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Stop") }
                            }
                        }
                        showError -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(sync.error.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                            IconButton(onClick = { errorHidden = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Dismiss", Modifier.size(16.dp)) }
                        }
                        justSynced -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(if (sync.message == STOPPED) "Sync stopped" else "Synced", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    shareMessage?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        item("update") {
            val update by g.updates.state.collectAsState()
            val pending = update is UpdateState.Available || update is UpdateState.Downloading || update is UpdateState.Confirming ||
                update is UpdateState.NeedsPermission ||
                (update as? UpdateState.Failed)?.info != null
            if (pending) CruxCard(color = MaterialTheme.colorScheme.primaryContainer) { UpdatePanel(Modifier.padding(16.dp), banner = true) }
        }

        val list = channels
        when {
            list == null -> {}
            list.isEmpty() -> item("empty") {
                EmptyState("Set a channel to get your crux", "DCrux builds your daily crux from the YouTube channels you follow. Add one to begin.") {
                    Button(onClick = { askChannel = true }) { Text("Sync now") }
                }
            }
            else -> {
                item("head") { Text("Your channels", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp)) }
                items(list, key = { it.id }) { ch -> ChannelCard(ch, settings, startExpanded = ch.id == list.first().id) }
            }
        }
    }
    }
}

/**
 * Today in one card: the day, date and time, the weather where you are, and the day's lines of
 * motivation, all over a small landscape that follows the hour, the weather and the temperature.
 * Tapping it slides the weather details up; swiping changes the quote.
 */
@Composable
private fun TodayCard(settings: Settings, onOpen: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }
    val place = settings.place
    val w = settings.weather
    val alerts = remember(w) { w?.let { WeatherRepo.advisories(it) }.orEmpty() }
    val hour = remember(now) { Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) + it.get(Calendar.MINUTE) / 60f } }
    val quotes = remember(Dates.dayKey(now)) { Quotes.today() }
    val pager = rememberPagerState { quotes.size }
    LaunchedEffect(quotes) {
        while (true) {
            delay(7000)
            if (!pager.isScrollInProgress && quotes.size > 1) pager.animateScrollToPage((pager.currentPage + 1) % quotes.size, animationSpec = tween(700))
        }
    }
    val ink = Color.White
    val shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 2f), 8f)
    Box(Modifier.fillMaxWidth().height(264.dp).clip(RoundedCornerShape(32.dp)).clickable(onClick = onOpen)) {
        WeatherScene(w?.code, w?.temp, w?.let { if (it.isDay < 0) null else it.isDay == 1 }, hour, Modifier.fillMaxSize())
        // A soft veil so the words read on any sky, heavier at the foot where the quote sits.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Black.copy(alpha = 0.08f), Color.Black.copy(alpha = 0.56f)))))
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(
                        SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(now)).uppercase(Locale.getDefault()) + "  ·  " + Dates.time(now),
                        style = MaterialTheme.typography.labelLarge.copy(shadow = shadow), color = ink.copy(alpha = 0.92f),
                    )
                    Text(SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(now)), style = MaterialTheme.typography.titleLarge.copy(shadow = shadow), color = ink)
                }
                if (place == null) {
                    Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.32f)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.LocationOn, null, Modifier.size(16.dp), tint = ink)
                        Spacer(Modifier.width(4.dp))
                        Text("Set your city", style = MaterialTheme.typography.labelLarge, color = ink)
                    }
                } else Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (w != null) {
                            Icon(weatherIcon(w.code), WeatherRepo.describe(w.code), Modifier.size(24.dp), tint = ink)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (w != null) "${w.temp.roundToInt()}°" else "–°", style = MaterialTheme.typography.headlineMedium.copy(shadow = shadow), color = ink)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.LocationOn, null, Modifier.size(13.dp), tint = ink.copy(alpha = 0.9f))
                        Spacer(Modifier.width(2.dp))
                        Text(place.name, style = MaterialTheme.typography.labelLarge.copy(shadow = shadow), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (w != null) Text(
                        "${WeatherRepo.describe(w.code)} · feels ${w.feels.roundToInt()}°",
                        style = MaterialTheme.typography.labelMedium.copy(shadow = shadow), color = ink.copy(alpha = 0.9f),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            HorizontalPager(pager, Modifier.fillMaxWidth(), pageSpacing = 16.dp, verticalAlignment = Alignment.Bottom) { i ->
                val q = quotes[i]
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "\u201C${q.text}\u201D", style = MaterialTheme.typography.titleMedium.copy(shadow = shadow), color = ink,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    Text(q.source, style = MaterialTheme.typography.labelMedium.copy(shadow = shadow), color = ink.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    quotes.indices.forEach { i ->
                        Box(Modifier.height(5.dp).width(if (i == pager.currentPage) 18.dp else 5.dp).clip(CircleShape).background(ink.copy(alpha = if (i == pager.currentPage) 0.95f else 0.45f)))
                    }
                }
                if (alerts.isNotEmpty()) {
                    val x = LocalExtra.current
                    Row(Modifier.clip(CircleShape).background(x.mid).padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WarningAmber, null, Modifier.size(13.dp), tint = x.onMid)
                        Spacer(Modifier.width(4.dp))
                        Text(if (alerts.size == 1) alerts.first().title else "${alerts.size} alerts", style = MaterialTheme.typography.labelSmall, color = x.onMid, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(channel: Channel, settings: Settings, startExpanded: Boolean) {
    val context = LocalContext.current
    val g = context.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable(channel.id) { mutableStateOf(startExpanded) }
    var page by rememberSaveable(channel.id) { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    val shorts = if (settings.showShorts) 1 else 0
    val size = settings.pageSize
    val total by remember(channel.id, shorts) { g.db.videos().count(channel.id, shorts) }.collectAsState(initial = 0)
    val pageCount = (total + size - 1) / size
    LaunchedEffect(pageCount) { if (page >= pageCount && pageCount > 0) page = pageCount - 1 }
    val videos by remember(channel.id, shorts, size, page) { g.db.videos().page(channel.id, shorts, size, page * size) }.collectAsState(initial = emptyList())
    val turn by animateFloatAsState(if (expanded) 180f else 0f, spring(dampingRatio = 0.6f), label = "chev")

    CruxCard {
        Column(Modifier.animateContentSize(spring(dampingRatio = 0.8f, stiffness = 300f))) {
            Row(Modifier.clickable { expanded = !expanded }.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Brush.linearGradient(BrandColors)), contentAlignment = Alignment.Center) {
                    Text(channel.title.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.titleLarge)
                    if (channel.avatarUrl.isNotBlank()) {
                        AsyncImage(channel.avatarUrl, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(channel.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (channel.isDefault) {
                            Spacer(Modifier.width(8.dp))
                            Tag("Default", bg = MaterialTheme.colorScheme.tertiaryContainer, fg = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                    Text(
                        "$total videos · synced ${Dates.ago(channel.lastSyncAt)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Channel options") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Open on YouTube") }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
                            onClick = {
                                menu = false
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/channel/${channel.id}"))) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove channel") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                            onClick = { menu = false; scope.launch { g.sync.removeChannel(channel.id) } },
                        )
                    }
                }
                Icon(Icons.Rounded.ExpandMore, if (expanded) "Collapse" else "Expand", Modifier.padding(end = 10.dp).rotate(turn))
            }
            AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 14.dp)) {
                    if (videos.isEmpty()) {
                        Text(
                            "No videos synced yet. Pull down to sync.", Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    var lastDay = ""
                    videos.forEach { v ->
                        val day = Dates.dayKey(v.publishedAt)
                        if (day != lastDay) {
                            lastDay = day
                            Text(
                                Dates.prettyDay(day), Modifier.padding(start = 6.dp, top = 10.dp, bottom = 6.dp),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        VideoRow(v) { nav.push(Screen.VideoDetail(v.id)) }
                    }
                    Spacer(Modifier.height(10.dp))
                    PaginationBar(page, pageCount, onPage = { page = it.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) })
                }
            }
        }
    }
}

@Composable
private fun VideoRow(v: Video, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            v.thumbUrl, null, contentScale = ContentScale.Crop,
            modifier = Modifier.width(116.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(Dates.time(v.publishedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RelevancePill(v.relevance)
                if (v.summary != null || v.detailed != null) Tag("Crux ready", bg = MaterialTheme.colorScheme.primaryContainer, fg = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}
