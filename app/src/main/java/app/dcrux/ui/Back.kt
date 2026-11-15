package app.dcrux.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/** Runs [onBack] when the user goes back (the system back gesture or button) while [enabled]. */
@Composable
fun OnBack(enabled: Boolean = true, onBack: () -> Unit) = BackHandler(enabled, onBack)
