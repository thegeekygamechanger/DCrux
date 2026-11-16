package app.dcrux.ui.settings

import androidx.compose.material.icons.rounded.Info
import androidx.compose.ui.unit.em
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.layout.widthIn
import app.dcrux.ui.components.QrCode
import androidx.compose.material.icons.rounded.QrCodeScanner
import app.dcrux.ui.components.CruxList
import androidx.compose.material.icons.rounded.Devices
import app.dcrux.data.wipe
import app.dcrux.LocalGraph
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import app.dcrux.ui.components.bottomBarInset
import app.dcrux.ui.components.TabTitle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import app.dcrux.data.Dates
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.dcrux.ui.components.appBackground
import app.dcrux.ai.ClaudeException
import app.dcrux.data.CustomSource
import app.dcrux.data.ExamCatalog
import app.dcrux.data.Settings
import app.dcrux.lock.PinConfirmDialog
import app.dcrux.lock.PinSetupDialog
import app.dcrux.net.Sources
import app.dcrux.ui.LocalNav
import app.dcrux.ui.Screen
import app.dcrux.ui.ScreenBar
import app.dcrux.ui.components.CruxCard
import app.dcrux.ui.components.FancySwitch
import app.dcrux.ui.components.shortExam
import app.dcrux.ui.theme.LocalExtra
import app.dcrux.ui.theme.isDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The scalloped badge behind each setting's icon: a circle with twelve soft lobes. */
private val Scallop = GenericShape { size, _ ->
    val cx = size.width / 2f; val cy = size.height / 2f; val r = minOf(cx, cy)
    for (i in 0..144) {
        val t = (i / 144.0 * 2 * Math.PI)
        val k = r * (0.93f + 0.07f * Math.cos(12 * t).toFloat())
        val x = cx + k * Math.cos(t).toFloat(); val y = cy + k * Math.sin(t).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** A heading in small capitals over a block of rows that read as one card. */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title.uppercase(), Modifier.padding(start = 10.dp, top = 6.dp, bottom = 8.dp),
            style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.09.em), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The rows keep small corners; the block as a whole is rounded, so first and last rows get the large ones.
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)), verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
    }
}

/** One row of the settings list: badge, name, what is set now, and an arrow. */
@Composable
private fun SettingRow(title: String, summary: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Monochrome on purpose, in both themes: a white badge with the icon in black at its centre.
        Box(Modifier.size(46.dp).clip(Scallop).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = Color(0xFF101010))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One setting: its row in the list and, when opened, its controls in a sheet that slides up. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Section(title: String, icon: ImageVector, summary: String, open: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    SettingRow(title, summary, icon, onToggle)
    if (open) {
        val maxHeight = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.9f).toDp() }
        ModalBottomSheet(onDismissRequest = onToggle, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding().imePadding()) {
                Row(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(10.dp))
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), content = content)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        FancySwitch(checked, onChange, enabled = enabled)
    }
}

/** A one-line summary of what is selected; tap to open the options underneath. */
@Composable
private fun Fold(summary: String, open: Boolean, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "fold")
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2)
        Icon(Icons.Rounded.ExpandMore, if (open) "Close" else "Open", Modifier.rotate(turn))
    }
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(settings: Settings, asTab: Boolean = false) {
    val g = LocalGraph.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val dark = isDark(settings.themeMode)

    // Claude key state
    var keyInput by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var hasKey by remember { mutableStateOf(g.secure.hasApiKey() || g.hasBuiltInKey) }
    var masked by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(hasKey) {
        masked = withContext(Dispatchers.Default) { g.apiKey(); g.secure.maskedApiKey() }
    }

    fun testKey(key: String?) {
        testing = true; testMessage = null
        scope.launch {
            try {
                val models = g.ai.refreshModels(key)
                tested = true
                testMessage = true to "Connected. ${models.size} models available."
            } catch (e: ClaudeException) {
                tested = false
                testMessage = false to e.friendly
            } catch (e: Exception) {
                tested = false
                testMessage = false to (e.message ?: "Test failed")
            } finally {
                testing = false
            }
        }
    }

    // adding channels
    val channels by g.db.channels().observeAll().collectAsState(initial = emptyList())
    var linkText by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var addMessage by remember { mutableStateOf<String?>(null) }
    fun addLinks() {
        if (linkText.isBlank() || adding) return
        adding = true; addMessage = null
        scope.launch {
            val report = g.sync.addLinks(linkText)
            addMessage = buildString {
                if (report.added.isNotEmpty()) append("Synced: ").append(report.added.joinToString(", "))
                if (report.failed.isNotEmpty()) {
                    if (isNotEmpty()) append("\n")
                    append(report.failed.joinToString("\n") { "Could not add ${it.first.take(48)}: ${it.second}" })
                }
            }
            if (report.failed.isEmpty()) linkText = ""
            adding = false
        }
    }
    // Which group is open; opening one closes the other, so the page stays a short, ordered list.
    var openSection by rememberSaveable { mutableStateOf("") }
    var sourcesOpen by rememberSaveable { mutableStateOf(false) }

    // dialogs
    var pinSetup by remember { mutableStateOf(false) }
    var pinConfirmFor by remember { mutableStateOf<String?>(null) }
    var resetAsk by remember { mutableStateOf(false) }
    var customName by rememberSaveable { mutableStateOf("") }
    var customUrl by rememberSaveable { mutableStateOf("") }

    fun resetApp() {
        scope.launch {
            g.platform.sounds.stopAll()
            g.mains.discard()
            g.db.wipe()
            g.prefs.wipe()
            g.secure.wipe()
            g.platform.clearWebData()
            g.platform.scheduleDailySync(enabled = true, wifiOnly = false)
            g.platform.restart()
        }
    }

    Column(Modifier.fillMaxSize().appBackground().statusBarsPadding().imePadding()) {
        if (asTab) TabTitle("Settings", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp))
        else ScreenBar("Settings", onBack = { nav.pop() })
        CruxList(
            if (asTab) Modifier.weight(1f) else Modifier.weight(1f).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = if (asTab) 32.dp + bottomBarInset() else 32.dp),
            spacing = 18.dp, minColumn = 460.dp,
        ) {
            item("study") {
                SettingsGroup("Study") {
                Section("Your crux", Icons.Rounded.AutoAwesome, if (channels.isEmpty()) "No channels yet" else channels.joinToString(", ") { it.title }, openSection == "crux", { openSection = if (openSection == "crux") "" else "crux" }) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Add YouTube channels", style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = linkText, onValueChange = { linkText = it },
                                modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp), maxLines = 3,
                                placeholder = { Text("Paste links", maxLines = 1) },
                                leadingIcon = { Icon(Icons.Rounded.Link, null) },
                                trailingIcon = {
                                    IconButton(onClick = {
                                        g.platform.clipboardText()?.let { linkText = it }
                                    }) { Icon(Icons.Rounded.ContentPaste, "Paste") }
                                },
                            )
                            Spacer(Modifier.width(10.dp))
                            if (adding) LoadingIndicator(Modifier.size(48.dp))
                            else FilledIconButton(onClick = { addLinks() }, enabled = linkText.isNotBlank(), modifier = Modifier.size(52.dp)) {
                                Icon(Icons.Rounded.Sync, "Sync links")
                            }
                        }
                        Text("Channels, videos or live streams. Several links at once is fine.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        addMessage?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    channels.forEach { ch ->
                        Row(Modifier.padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(ch.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            IconButton(onClick = { scope.launch { g.sync.removeChannel(ch.id) } }) { Icon(Icons.Rounded.DeleteOutline, "Remove channel") }
                        }
                    }
                }

                Section("Your exams", Icons.Rounded.School, settings.exams.joinToString(", ") { shortExam(it) }, openSection == "exams", { openSection = if (openSection == "exams") "" else "exams" }) {
                    AnimatedVisibility(true) {
                        Column {
                            ExamCatalog.forEach { (group, exams) ->
                                Label(group)
                                FlowRow(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    exams.forEach { e ->
                                        val on = e in settings.exams
                                        FilterChip(
                                            selected = on, shape = CircleShape, label = { Text(shortExam(e)) },
                                            onClick = {
                                                val next = if (on) settings.exams - e else settings.exams + e
                                                if (next.isNotEmpty()) scope.launch { g.prefs.setExams(next) }
                                            },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }

                Section("Papers", Icons.Rounded.Newspaper, Sources.builtIn.filter { it.id in settings.enabledSources }.joinToString(", ") { it.name }.ifBlank { "No sources" }, openSection == "papers", { openSection = if (openSection == "papers") "" else "papers" }) {
                    SwitchRow("Page-turn animation", null, settings.pageFlip) { scope.launch { g.prefs.setPageFlip(it) } }
                    SwitchRow("Block ads and trackers", "In the in-app browser", settings.blockAds) { scope.launch { g.prefs.setBlockAds(it) } }
                    Label("Crux sources")
                    Fold(Sources.builtIn.filter { it.id in settings.enabledSources }.joinToString(", ") { it.name }.ifBlank { "None" }, sourcesOpen) { sourcesOpen = !sourcesOpen }
                    AnimatedVisibility(sourcesOpen) {
                        Column {
                            Sources.builtIn.forEach { s ->
                                SwitchRow(s.name, null, s.id in settings.enabledSources) { on ->
                                    scope.launch { g.prefs.setEnabledSources(if (on) settings.enabledSources + s.id else settings.enabledSources - s.id) }
                                }
                            }
                        }
                    }
                    Label("Your own sources")
                    settings.customSources.forEach { c ->
                        Row(Modifier.padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.titleSmall)
                                Text(c.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    g.prefs.setCustomSources(settings.customSources - c)
                                    withContext(Dispatchers.IO) { g.db.news().deleteForSource(c.id) }
                                }
                            }) { Icon(Icons.Rounded.DeleteOutline, "Remove source") }
                        }
                    }
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(customName, { customName = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(20.dp), label = { Text("Name") })
                        OutlinedTextField(
                            customUrl, { customUrl = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(20.dp),
                            label = { Text("RSS feed or public Telegram channel link") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                        FilledTonalButton(
                            enabled = customName.isNotBlank() && customUrl.length > 8,
                            onClick = {
                                val c = CustomSource("c" + System.currentTimeMillis().toString(36), customName.trim(), customUrl)
                                customName = ""; customUrl = ""
                                scope.launch { g.prefs.setCustomSources(settings.customSources + c); g.sync.syncNow() }
                            },
                        ) { Text("Add source") }
                    }
                }
                }
            }

            item("ai") {
                SettingsGroup("Crux AI") {
                Section("Model and language", Icons.Rounded.Psychology, (if (settings.autoModel) "Automatic model" else settings.modelId.removePrefix("claude-").ifBlank { "Chosen model" }) + " · " + settings.language, openSection == "claude", { openSection = if (openSection == "claude") "" else "claude" }) {
                    // A build that ships with its own key shows no key controls at all.
                    if (!g.hasBuiltInKey) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("API key", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when {
                                        g.hasBuiltInKey -> "Built into this app · ${masked ?: "••••"}"
                                        hasKey -> "Saved · ${masked ?: "••••"}"
                                        else -> "Not set"
                                    },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            "Encrypted with a key held in this device's secure hardware. It is never shown in full, never backed up and never leaves the device except to call the AI service.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!g.hasBuiltInKey && !hasKey) {
                            OutlinedTextField(
                                value = keyInput, onValueChange = { keyInput = it.trim(); tested = false; testMessage = null },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(20.dp),
                                label = { Text("Paste your API key") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (testing) LoadingIndicator(Modifier.size(40.dp))
                            else FilledTonalButton(
                                enabled = hasKey || keyInput.length > 20,
                                onClick = { testKey(if (hasKey) null else keyInput) },
                            ) { Text("Test API") }
                            if (!g.hasBuiltInKey && !hasKey) {
                                Button(
                                    enabled = tested && !testing,
                                    onClick = {
                                        g.secure.saveApiKey(keyInput)
                                        keyInput = ""; hasKey = true; tested = false
                                        testMessage = true to "Key saved on this device."
                                    },
                                ) { Text("Save API key") }
                            }
                            if (!g.hasBuiltInKey && hasKey) {
                                OutlinedButton(onClick = {
                                    g.secure.clearApiKey(); hasKey = false; tested = false; testMessage = null
                                    scope.launch { g.prefs.setModels(emptyList()) }
                                }) { Text("Remove key") }
                            }
                        }
                        testMessage?.let { (ok, msg) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, Modifier.size(18.dp),
                                    tint = if (ok) LocalExtra.current.onHigh else MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(msg, style = MaterialTheme.typography.bodySmall, color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    }
                    SwitchRow(
                        "Choose the model automatically",
                        "Starts with the strongest model and steps down when one is busy or unavailable",
                        settings.autoModel,
                    ) { scope.launch { g.prefs.setAutoModel(it) } }
                    AnimatedVisibility(!settings.autoModel) {
                        Column {
                            Label("Model")
                            if (settings.models.isEmpty()) {
                                Text("The list of models loads the first time the AI is used.", Modifier.padding(horizontal = 18.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { g.ai.refreshModels() } }
                            }
                            val current = settings.modelId.ifBlank { settings.models.firstOrNull()?.id.orEmpty() }
                            settings.models.forEach { m ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { scope.launch { g.prefs.setModelId(m.id) } }.padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = m.id == current, onClick = { scope.launch { g.prefs.setModelId(m.id) } })
                                    Column {
                                        Text(m.name.removePrefix("Claude "), style = MaterialTheme.typography.titleSmall)
                                        Text(m.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    Label("Write summaries in")
                    Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("English", "Hindi", "Hinglish").forEach { l ->
                            FilterChip(selected = settings.language == l, onClick = { scope.launch { g.prefs.setLanguage(l) } }, label = { Text(l) }, shape = CircleShape)
                        }
                    }
                    SwitchRow("Open videos on Detailed", null, settings.preferDetailed) { scope.launch { g.prefs.setPreferDetailed(it) } }
                    SwitchRow("Rate exam relevance automatically", null, settings.aiCurate) { scope.launch { g.prefs.setAiCurate(it) } }
                }
                }
            }

            item("syncing") {
                SettingsGroup("Sync") {
                if (g.cloud.available) {
                val cloud by g.cloud.status.collectAsState()
                Section(
                    "Sync across devices", Icons.Rounded.Devices,
                    when {
                        !cloud.on -> "Off"
                        cloud.syncing -> "Syncing"
                        cloud.error != null -> "Could not sync"
                        settings.cloudLastSync > 0 -> "On · synced " + Dates.ago(settings.cloudLastSync)
                        else -> "On"
                    },
                    openSection == "cloud", { openSection = if (openSection == "cloud") "" else "cloud" },
                ) { CloudPanel(cloud) }
                }
                Section("Daily sync", Icons.Rounded.Sync, (if (settings.dailySync) "Daily" else "Manual") + " · last synced " + Dates.ago(settings.lastSync), openSection == "sync", { openSection = if (openSection == "sync") "" else "sync" }) {
                    SwitchRow("Daily auto-sync", "Every morning, in the background", settings.dailySync) {
                        scope.launch { g.prefs.setDailySync(it); g.platform.scheduleDailySync(it, settings.wifiOnly) }
                    }
                    SwitchRow("Only on Wi-Fi", null, settings.wifiOnly, enabled = settings.dailySync) {
                        scope.launch { g.prefs.setWifiOnly(it); g.platform.scheduleDailySync(settings.dailySync, it) }
                    }
                    SwitchRow("Show Shorts", null, settings.showShorts) { scope.launch { g.prefs.setShowShorts(it) } }
                    Label("Items per page")
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                        listOf(3, 5, 8, 10).forEachIndexed { i, n ->
                            SegmentedButton(selected = settings.pageSize == n, onClick = { scope.launch { g.prefs.setPageSize(n) } }, shape = SegmentedButtonDefaults.itemShape(i, 4)) { Text("$n") }
                        }
                    }
                }
                }
            }

            item("app") {
                SettingsGroup("App") {
                Section("Appearance", Icons.Rounded.Palette, when (settings.themeMode) { "dark" -> "Dark"; "light" -> "Light"; else -> if (g.platform.isDesktop) "Follows the system" else "Follows the phone" }, openSection == "look", { openSection = if (openSection == "look") "" else "look" }) {
                    SwitchRow("Dark theme", if (settings.themeMode == "system") "Following the system right now" else null, dark) {
                        scope.launch { g.prefs.setTheme(if (it) "dark" else "light") }
                    }
                    SwitchRow("Follow system", null, settings.themeMode == "system") {
                        scope.launch { g.prefs.setTheme(if (it) "system" else if (dark) "dark" else "light") }
                    }
                    val canDynamic = remember { g.platform.dynamicColors(false) != null }
                    SwitchRow("Wallpaper colours", if (canDynamic) "Use Material You colours from your wallpaper" else if (g.platform.isDesktop) "Not available on a computer" else "Needs Android 12 or newer", settings.dynamicColor, enabled = canDynamic) {
                        scope.launch { g.prefs.setDynamic(it) }
                    }
                }

                Section("Security", Icons.Rounded.Lock, if (settings.lockEnabled) "App lock is on" else "App lock is off", openSection == "lock", { openSection = if (openSection == "lock") "" else "lock" }) {
                    SwitchRow("App lock", "Biometrics first, then your PIN. Covers the whole app, including test results", settings.lockEnabled) { on ->
                        if (on) pinSetup = true else pinConfirmFor = "off"
                    }
                    SwitchRow(
                        "Unlock with biometrics",
                        if (g.platform.canUseBiometrics) "Fingerprint or face" else "No fingerprint or face is set up on this device",
                        settings.biometric && settings.lockEnabled, enabled = settings.lockEnabled && g.platform.canUseBiometrics,
                    ) { scope.launch { g.prefs.setBiometric(it) } }
                    if (settings.lockEnabled) {
                        Row(Modifier.fillMaxWidth().clickable { pinConfirmFor = "change" }.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text("Change PIN", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                        }
                    }
                }

                Section("App update", Icons.Rounded.SystemUpdate, "Version " + g.platform.versionName, openSection == "update", { openSection = if (openSection == "update") "" else "update" }) {
                    UpdatePanel(Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                    SwitchRow("Check for updates automatically", "Once a day, from GitHub", settings.autoUpdate) { scope.launch { g.prefs.setAutoUpdate(it) } }
                }
                }
            }

            item("yours") {
                SettingsGroup("Your data") {
                Section("Data", Icons.Rounded.Storage, "Kept on this device until you reset", openSection == "data", { openSection = if (openSection == "data") "" else "data" }) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Everything stays on this device and is kept until you reset: channels, history, summaries, headlines and encrypted test results. Nothing is backed up to the cloud.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { if (settings.lockEnabled) pinConfirmFor = "reset" else resetAsk = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                        ) { Text("Reset app") }
                    }
                }
                SettingRow("History", "Every question and test, with when and where", Icons.Rounded.History) { nav.push(Screen.History) }
                }
            }

            item("about") {
                SettingsGroup("About") {
                    SettingRow("About DCrux", "Developer, privacy, terms and licences", Icons.Rounded.Info) { nav.push(Screen.About) }
                }
            }
        }
    }

    if (pinSetup) PinSetupDialog(
        onDone = { pin ->
            pinSetup = false
            scope.launch { withContext(Dispatchers.Default) { g.secure.setPin(pin) }; g.prefs.setLock(true) }
        },
        onDismiss = { pinSetup = false },
    )
    pinConfirmFor?.let { purpose ->
        PinConfirmDialog(
            title = when (purpose) { "off" -> "Turn off app lock"; "change" -> "Change PIN"; else -> "Reset app" },
            onConfirmed = {
                pinConfirmFor = null
                when (purpose) {
                    "off" -> scope.launch { g.prefs.setLock(false); g.secure.clearPin() }
                    "change" -> pinSetup = true
                    else -> resetAsk = true
                }
            },
            onDismiss = { pinConfirmFor = null },
        )
    }
    if (resetAsk) AlertDialog(
        onDismissRequest = { resetAsk = false },
        title = { Text("Reset DCrux?") },
        text = { Text("This deletes every channel, summary, headline, test result and setting on this device" + if (g.hasBuiltInKey) "." else ", and the saved API key.") },
        confirmButton = {
            Button(
                onClick = { resetAsk = false; resetApp() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Delete everything") }
        },
        dismissButton = { TextButton(onClick = { resetAsk = false }) { Text("Cancel") } },
    )
}

/**
 * Pairing this device with the person's others. One makes a code, the rest enter it; from then on
 * channels, video notes, questions, tests and study settings are the same everywhere.
 */
@Composable
private fun ColumnScope.CloudPanel(cloud: app.dcrux.sync.CloudStatus) {
    val g = LocalGraph.current
    val scope = rememberCoroutineScope()
    var typed by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmErase by remember { mutableStateOf(false) }
    var short by remember { mutableStateOf<String?>(null) }
    val pad = Modifier.padding(horizontal = 22.dp)
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    // Reading the other device's QR code with the camera (phones only); the short code can always be typed instead.
    val scan = g.platform.rememberQrScanner { scanned ->
        val code = scanned?.let { app.dcrux.sync.CloudSync.fromQr(it) }
        if (code == null) note = if (scanned == null) "No QR code could be read from that photo. Fill the frame with the code, hold steady, and try again, or type the 4-character code." else "That QR code is not a DCrux sync code."
        else { busy = true; scope.launch { g.cloud.join(code); busy = false } }
    }
    // While this sheet is open with sync on, another device can join by a short code. Closing the sheet withdraws it.
    LaunchedEffect(cloud.on) {
        short = null
        if (cloud.on) g.cloud.offerShortCodes { short = it }
    }

    if (!cloud.on) {
        Text(
            "Keep your channels, video notes, questions, tests and study settings the same on your phone and your computer. No account needed.",
            pad.padding(bottom = 14.dp), style = MaterialTheme.typography.bodyMedium, color = quiet,
        )
        Button(enabled = !busy, modifier = pad.fillMaxWidth(), onClick = {
            busy = true; note = null
            scope.launch { g.cloud.enable(); busy = false }
        }) { Text("Turn on sync on this device") }
        Text("Already syncing on another device?", pad.padding(top = 22.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall)
        Text(
            "Open Settings → Sync across devices on it, then type the 4-character code it shows" + (if (scan != null) ", or scan its QR code." else "."),
            pad.padding(bottom = 10.dp), style = MaterialTheme.typography.bodySmall, color = quiet,
        )
        fun join() {
            if (busy || typed.isBlank()) return
            busy = true; note = null
            scope.launch {
                val short4 = app.dcrux.sync.CloudSync.normaliseShort(typed) != null
                val result = when {
                    short4 -> g.cloud.joinShort(typed)
                    g.cloud.join(typed) -> app.dcrux.sync.ShortJoin.Joined
                    else -> app.dcrux.sync.ShortJoin.Wrong
                }
                busy = false
                note = when (result) {
                    app.dcrux.sync.ShortJoin.Joined -> { typed = ""; null }
                    app.dcrux.sync.ShortJoin.Wrong ->
                        if (short4) "That code did not match. A code works once and changes every few minutes, so use the one showing on the other device right now."
                        else "A code is 4 letters and digits. Type the one showing on the other device."
                    app.dcrux.sync.ShortJoin.Busy -> "Too many codes have been tried. Wait a few minutes" + (if (scan != null) ", or scan the QR code instead." else ".")
                    app.dcrux.sync.ShortJoin.Offline -> "Could not reach the sync service. Check the connection and try again."
                }
            }
        }
        OutlinedTextField(
            value = typed, onValueChange = { typed = it.take(40).uppercase() }, modifier = pad.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(24.dp),
            textStyle = MaterialTheme.typography.headlineMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, letterSpacing = 0.3.em, textAlign = TextAlign.Center),
            placeholder = { Text("Code", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium, color = quiet) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { join() }),
        )
        Row(pad.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && typed.isNotBlank(), modifier = Modifier.weight(1f), onClick = { join() }) { Text(if (busy) "Joining" else "Join") }
            if (scan != null) FilledTonalButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { note = null; scan() }) {
                Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Scan QR")
            }
        }
        note?.let { Text(it, pad.padding(bottom = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        cloud.error?.let { Text(it, pad.padding(bottom = 10.dp), style = MaterialTheme.typography.bodySmall, color = quiet) }
    } else {
        val code = remember(cloud.on) { g.cloud.code().orEmpty() }
        Column(pad.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Add another device", style = MaterialTheme.typography.titleMedium)
            Text(
                "On the other device open Settings → Sync across devices, then scan this or type the code.",
                Modifier.padding(top = 4.dp, bottom = 14.dp), style = MaterialTheme.typography.bodySmall, color = quiet, textAlign = TextAlign.Center,
            )
            QrCode(app.dcrux.sync.CloudSync.qrText(code), Modifier.size(216.dp))
            Text("or type this code", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.labelLarge, color = quiet)
            Text(
                short ?: "····", Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.displayMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, letterSpacing = 0.25.em, fontWeight = FontWeight.SemiBold),
                color = if (short != null) MaterialTheme.colorScheme.primary else quiet, textAlign = TextAlign.Center, maxLines = 1,
            )
            Text(
                if (short != null) "Works once, and a new one appears every few minutes." else "Getting a code…",
                Modifier.padding(top = 2.dp, bottom = 14.dp), style = MaterialTheme.typography.bodySmall, color = quiet, textAlign = TextAlign.Center,
            )
            cloud.error?.let { Text(it, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(enabled = !cloud.syncing, onClick = { scope.launch { g.cloud.syncNow() } }) { Text(if (cloud.syncing) "Syncing" else "Sync now") }
                TextButton(onClick = { scope.launch { g.cloud.disable(eraseCloud = false) } }) { Text("Turn off here") }
            }
            TextButton(onClick = { confirmErase = true }) { Text("Turn off on all my devices", color = MaterialTheme.colorScheme.error) }
        }
    }
    Text(
        "Everything is encrypted on your device before it leaves, with a key only your devices hold. The service passes it between them, cannot read it, and never writes it to disk. " +
            "PDFs and page photos are never sent. Show the QR code and the short code only to your own devices.",
        pad.padding(top = 6.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = quiet,
    )

    if (confirmErase) AlertDialog(
        onDismissRequest = { confirmErase = false },
        title = { Text("Turn sync off everywhere?") },
        text = { Text("Sync stops on this device and on your other devices, and the copy the service was relaying is cleared. What is on each device stays there.") },
        confirmButton = { TextButton(onClick = { confirmErase = false; scope.launch { g.cloud.disable(eraseCloud = true) } }) { Text("Turn off") } },
        dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Keep") } },
    )
}
