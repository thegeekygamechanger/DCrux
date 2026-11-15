package app.dcrux.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dcrux.BuildConfig
import app.dcrux.graph
import app.dcrux.update.UpdateInfo
import app.dcrux.update.UpdateState
import kotlinx.coroutines.launch

private fun mb(bytes: Long) = if (bytes > 0) " · %.1f MB".format(bytes / 1_048_576.0) else ""

/**
 * Everything about updating the app, in one block: the installed version, the check, the
 * download progress and what Android said. [banner] is the short form shown on Home, which
 * appears only when there is something to do.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UpdatePanel(modifier: Modifier = Modifier, banner: Boolean = false) {
    val context = LocalContext.current
    val g = context.graph
    val scope = rememberCoroutineScope()
    val state by g.updates.state.collectAsState()
    val info: UpdateInfo? = when (val s = state) {
        is UpdateState.Available -> s.info
        is UpdateState.Downloading -> s.info
        is UpdateState.Confirming -> s.info
        is UpdateState.NeedsPermission -> s.info
        is UpdateState.Failed -> s.info
        else -> null
    }
    if (banner && info == null) return

    Column(modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (info != null) "DCrux ${info.version} is available" else "DCrux ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when (val s = state) {
                        UpdateState.Checking -> "Checking GitHub for a newer version"
                        UpdateState.UpToDate -> "You have the latest version"
                        is UpdateState.Available -> "You have ${BuildConfig.VERSION_NAME}${mb(s.info.sizeBytes)}"
                        is UpdateState.Downloading -> "Downloading · ${s.percent}%"
                        is UpdateState.Confirming -> "Confirm the update in Android's prompt"
                        is UpdateState.NeedsPermission -> "One-time step: allow DCrux to install its own updates, then come back. The update continues by itself."
                        is UpdateState.Failed -> s.message
                        UpdateState.Idle -> "Updates come from the project's GitHub releases"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is UpdateState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state is UpdateState.Checking) LoadingIndicator(Modifier.size(36.dp))
        }
        (state as? UpdateState.Downloading)?.let { d ->
            LinearProgressIndicator(progress = { d.percent / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape), drawStopIndicator = {})
        }
        if (!banner && info != null && info.notes.isNotBlank() && state is UpdateState.Available) {
            Text(
                info.notes.lineSequence().map { it.trim().trimStart('#', '-', '*').trim() }.filter { it.isNotBlank() }.take(6).joinToString("\n"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (val s = state) {
                is UpdateState.Available -> Button(onClick = { g.updates.install(s.info) }) { Text("Update now") }
                is UpdateState.Confirming -> FilledTonalButton(onClick = { g.updates.install(s.info) }) { Text("Show the prompt again") }
                is UpdateState.NeedsPermission -> Button(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("Allow") }
                is UpdateState.Failed -> if (s.info != null) Button(onClick = { g.updates.install(s.info) }) { Text("Try again") }
                else FilledTonalButton(onClick = { scope.launch { g.updates.check(force = true) } }) { Text("Check again") }
                is UpdateState.Downloading, UpdateState.Checking -> {}
                else -> if (!banner) FilledTonalButton(onClick = { scope.launch { g.updates.check(force = true) } }) { Text("Check for updates") }
            }
        }
    }
}
