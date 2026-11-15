package app.dcrux.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.dcrux.R

/**
 * Headlines use TeX Gyre Heros, the freely licensed Helvetica-metric typeface
 * (Helvetica itself is proprietary and cannot be bundled); everything else is Inter.
 */
val Heros = FontFamily(
    Font(R.font.heros_regular, FontWeight.Normal),
    Font(R.font.heros_bold, FontWeight.Bold),
)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

