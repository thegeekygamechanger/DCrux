package app.dcrux.ui.settings

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.dcrux.ai.ClaudeException
import app.dcrux.audio.MindFlow
import app.dcrux.data.CustomSource
import app.dcrux.data.ExamCatalog
import app.dcrux.data.Settings
import app.dcrux.graph
import app.dcrux.lock.AppLock
import app.dcrux.lock.PinConfirmDialog
import app.dcrux.lock.PinSetupDialog
import app.dcrux.net.Sources
import app.dcrux.sync.SyncManager
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

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        CruxCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 8.dp).animateContentSize(), content = content)
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
    val context = LocalContext.current
    val g = context.graph
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
    var examsOpen by rememberSaveable { mutableStateOf(false) }
    var sourcesOpen by rememberSaveable { mutableStateOf(false) }

    // dialogs
    var pinSetup by remember { mutableStateOf(false) }
    var pinConfirmFor by remember { mutableStateOf<String?>(null) }
    var resetAsk by remember { mutableStateOf(false) }
    var customName by rememberSaveable { mutableStateOf("") }
    var customUrl by rememberSaveable { mutableStateOf("") }

    fun resetApp() {
        scope.launch {
            MindFlow.stopAll(context)
            withContext(Dispatchers.IO) { g.db.clearAllTables() }
            g.prefs.wipe()
            g.secure.wipe()
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
            SyncManager.schedule(context, enabled = true, wifiOnly = false)
            (context as? Activity)?.recreate()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().imePadding()) {
        if (asTab) Text("Settings", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineMedium)
        else ScreenBar("Settings", onBack = { nav.pop() })
        LazyColumn(
            if (asTab) Modifier.weight(1f) else Modifier.weight(1f).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item("crux") {
                Section("Your crux") {
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
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let { linkText = it }
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
            }

            item("look") {
                Section("Appearance") {
                    SwitchRow("Dark theme", if (settings.themeMode == "system") "Following the system right now" else null, dark) {
                        scope.launch { g.prefs.setTheme(if (it) "dark" else "light") }
                    }
                    SwitchRow("Follow system", null, settings.themeMode == "system") {
                        scope.launch { g.prefs.setTheme(if (it) "system" else if (dark) "dark" else "light") }
                    }
                    SwitchRow("Wallpaper colours", if (Build.VERSION.SDK_INT >= 31) "Use Material You colours from your wallpaper" else "Needs Android 12 or newer", settings.dynamicColor, enabled = Build.VERSION.SDK_INT >= 31) {
                        scope.launch { g.prefs.setDynamic(it) }
                    }
                }
            }

            item("claude") {
                Section("AI") {
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
                            "Encrypted with a key held in this phone's secure hardware. It is never shown in full, never backed up and never leaves the device except to call the AI service.",
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
                                        testMessage = true to "Key saved on this phone."
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

            item("exams") {
                Section("Your exams") {
                    Fold(settings.exams.joinToString(", ") { shortExam(it) }, examsOpen) { examsOpen = !examsOpen }
                    AnimatedVisibility(examsOpen) {
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
            }

            item("sync") {
                Section("Sync") {
                    SwitchRow("Daily auto-sync", "Every morning, in the background", settings.dailySync) {
                        scope.launch { g.prefs.setDailySync(it); SyncManager.schedule(context, it, settings.wifiOnly) }
                    }
                    SwitchRow("Only on Wi-Fi", null, settings.wifiOnly, enabled = settings.dailySync) {
                        scope.launch { g.prefs.setWifiOnly(it); SyncManager.schedule(context, settings.dailySync, it) }
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

            item("papers") {
                Section("Papers") {
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

            item("lock") {
                Section("Security") {
                    SwitchRow("App lock", "Biometrics first, then your PIN. Covers the whole app, including test results", settings.lockEnabled) { on ->
                        if (on) pinSetup = true else pinConfirmFor = "off"
                    }
                    SwitchRow(
                        "Unlock with biometrics",
                        if (AppLock.canUseBiometrics(context)) "Fingerprint or face" else "No fingerprint or face is set up on this phone",
                        settings.biometric && settings.lockEnabled, enabled = settings.lockEnabled && AppLock.canUseBiometrics(context),
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
            }

            item("data") {
                Section("Data") {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Everything stays on this phone and is kept until you reset: channels, history, summaries, headlines and encrypted test results. Nothing is backed up to the cloud.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { if (settings.lockEnabled) pinConfirmFor = "reset" else resetAsk = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                        ) { Text("Reset app") }
                    }
                }
            }

            item("about") {
                CruxCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer, onClick = { nav.push(Screen.About) }) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("About DCrux", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("Developer, source code and licences", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
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
        text = { Text("This deletes every channel, summary, headline, test result and setting on this phone" + if (g.hasBuiltInKey) "." else ", and the saved API key.") },
        confirmButton = {
            Button(
                onClick = { resetAsk = false; resetApp() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Delete everything") }
        },
        dismissButton = { TextButton(onClick = { resetAsk = false }) { Text("Cancel") } },
    )
}
