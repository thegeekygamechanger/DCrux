package app.dcrux.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * Headlines use TeX Gyre Heros, the freely licensed Helvetica-metric typeface
 * (Helvetica itself is proprietary and cannot be bundled); everything else is Inter.
 * The files are the Android app's own, packed into this build.
 */
val Heros = FontFamily(
    Font(resource = "font/heros_regular.otf", weight = FontWeight.Normal),
    Font(resource = "font/heros_bold.otf", weight = FontWeight.Bold),
)

val Inter = FontFamily(
    Font(resource = "font/inter_regular.ttf", weight = FontWeight.Normal),
    Font(resource = "font/inter_medium.ttf", weight = FontWeight.Medium),
    Font(resource = "font/inter_semibold.ttf", weight = FontWeight.SemiBold),
    Font(resource = "font/inter_bold.ttf", weight = FontWeight.Bold),
)
