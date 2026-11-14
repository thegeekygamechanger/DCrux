package app.dcrux.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.dcrux.graph
import app.dcrux.ui.components.AnimatedLogo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val PIN_LENGTH = 4

object AppLock {
    fun canUseBiometrics(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    /** Biometrics first; "Use PIN" (or any failure) falls back to the PIN pad underneath. */
    fun prompt(activity: FragmentActivity, onSuccess: () -> Unit) {
        val prompt = BiometricPrompt(
            activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock DCrux")
            .setSubtitle("Fingerprint or face")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        runCatching { prompt.authenticate(info) }
    }
}

@Composable
private fun PinDots(count: Int, shake: Float) {
    Row(Modifier.graphicsLayer { translationX = shake }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(PIN_LENGTH) { i ->
            val filled = i < count
            val s by animateFloatAsState(if (filled) 1.25f else 1f, spring(dampingRatio = 0.4f), label = "dot")
            Box(
                Modifier.size(16.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape)
                    .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
            )
        }
    }
}

@Composable
private fun PinPad(onDigit: (Char) -> Unit, onBackspace: () -> Unit, extra: (@Composable () -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                row.forEach { d -> PinKey(onClick = { onDigit(d) }) { Text(d.toString(), style = MaterialTheme.typography.headlineSmall) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) { extra?.invoke() }
            PinKey(onClick = { onDigit('0') }) { Text("0", style = MaterialTheme.typography.headlineSmall) }
            PinKey(onClick = onBackspace, tonal = false) { Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete") }
        }
    }
}

@Composable
private fun PinKey(onClick: () -> Unit, tonal: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier.size(72.dp).clip(CircleShape)
            .background(if (tonal) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Full-screen gate shown whenever the app is locked. */
@Composable
fun LockScreen(biometric: Boolean, onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val g = context.graph
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var fails by remember { mutableIntStateOf(0) }
    var blockedUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var checking by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val useBio = biometric && remember { AppLock.canUseBiometrics(context) }

    fun askBiometric() {
        (context as? FragmentActivity)?.let { AppLock.prompt(it, onUnlocked) }
    }

    LaunchedEffect(Unit) { if (useBio) askBiometric() }
    LaunchedEffect(blockedUntil) {
        while (System.currentTimeMillis() < blockedUntil) { now = System.currentTimeMillis(); delay(500) }
        now = System.currentTimeMillis()
    }

    val blocked = now < blockedUntil
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        AnimatedLogo(84.dp)
        Spacer(Modifier.height(18.dp))
        Text("DCrux is locked", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                blocked -> "Too many attempts. Try again in ${((blockedUntil - now) / 1000) + 1}s"
                fails > 0 -> "Wrong PIN. Try again"
                else -> "Enter your $PIN_LENGTH-digit PIN"
            },
            color = if (fails > 0 || blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(26.dp))
        PinDots(pin.length, shake.value)
        Spacer(Modifier.height(34.dp))
        PinPad(
            onDigit = { d ->
                if (blocked || checking || pin.length >= PIN_LENGTH) return@PinPad
                pin += d
                if (pin.length == PIN_LENGTH) {
                    checking = true
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) { g.secure.checkPin(pin) }
                        if (ok) onUnlocked() else {
                            fails++
                            if (fails % 5 == 0) blockedUntil = System.currentTimeMillis() + 30_000L * (fails / 5)
                            repeat(3) { shake.animateTo(18f, tween(45)); shake.animateTo(-18f, tween(45)) }
                            shake.animateTo(0f, tween(45))
                            pin = ""
                        }
                        checking = false
                    }
                }
            },
            onBackspace = { if (!checking) pin = pin.dropLast(1) },
            extra = if (useBio) {
                { PinKey(onClick = { askBiometric() }, tonal = false) { Icon(Icons.Rounded.Fingerprint, "Use biometrics", Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary) } }
            } else null,
        )
    }
}

/** Two-step "choose a PIN, repeat it" dialog. */
@Composable
fun PinSetupDialog(onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(if (first == null) "Choose a PIN" else "Repeat the PIN") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (mismatch) "The PINs did not match. Start again." else "$PIN_LENGTH digits. It is the fallback when biometrics are not available.",
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    color = if (mismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                PinDots(pin.length, 0f)
                Spacer(Modifier.height(20.dp))
                PinPad(
                    onDigit = { d ->
                        if (pin.length < PIN_LENGTH) pin += d
                        if (pin.length == PIN_LENGTH) {
                            val f = first
                            when {
                                f == null -> { first = pin; pin = ""; mismatch = false }
                                f == pin -> onDone(pin)
                                else -> { first = null; pin = ""; mismatch = true }
                            }
                        }
                    },
                    onBackspace = { pin = pin.dropLast(1) },
                )
            }
        },
    )
}

/** Asks for the current PIN before a sensitive change. */
@Composable
fun PinConfirmDialog(title: String, onConfirmed: () -> Unit, onDismiss: () -> Unit) {
    val g = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (wrong) "Wrong PIN. Try again." else "Enter your PIN to continue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                PinDots(pin.length, 0f)
                Spacer(Modifier.height(20.dp))
                PinPad(
                    onDigit = { d ->
                        if (pin.length < PIN_LENGTH) pin += d
                        if (pin.length == PIN_LENGTH) {
                            val attempt = pin
                            scope.launch {
                                if (withContext(Dispatchers.Default) { g.secure.checkPin(attempt) }) onConfirmed() else { wrong = true; pin = "" }
                            }
                        }
                    },
                    onBackspace = { pin = pin.dropLast(1) },
                )
            }
        },
    )
}
