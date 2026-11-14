package app.dcrux.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
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

private fun heros(size: Int, line: Int, weight: FontWeight = FontWeight.Bold, spacing: Double = -0.02) =
    TextStyle(fontFamily = Heros, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.em)

private fun inter(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, spacing: Double = 0.0) =
    TextStyle(fontFamily = Inter, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.em)

val AppTypography = Typography(
    displayLarge = heros(52, 58),
    displayMedium = heros(42, 48),
    displaySmall = heros(34, 40),
    headlineLarge = heros(30, 36),
    headlineMedium = heros(26, 32),
    headlineSmall = heros(22, 28),
    titleLarge = heros(20, 26, spacing = -0.01),
    titleMedium = inter(16, 22, FontWeight.SemiBold),
    titleSmall = inter(14, 20, FontWeight.SemiBold),
    bodyLarge = inter(16, 25),
    bodyMedium = inter(14, 21),
    bodySmall = inter(12, 17),
    labelLarge = inter(14, 20, FontWeight.SemiBold),
    labelMedium = inter(12, 16, FontWeight.Medium, 0.01),
    labelSmall = inter(11, 14, FontWeight.Medium, 0.02),
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val Light = lightColorScheme(
    primary = Color(0xFF5B3BE8), onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF), onPrimaryContainer = Color(0xFF1A0063),
    secondary = Color(0xFFB4236A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E4), onSecondaryContainer = Color(0xFF3E0020),
    tertiary = Color(0xFF006B5F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFA6F2E3), onTertiaryContainer = Color(0xFF00201C),
    background = Color(0xFFFCF8FF), onBackground = Color(0xFF1C1B20),
    surface = Color(0xFFFCF8FF), onSurface = Color(0xFF1C1B20),
    surfaceVariant = Color(0xFFE6E0EC), onSurfaceVariant = Color(0xFF48454E),
    outline = Color(0xFF79757F), outlineVariant = Color(0xFFCAC4CF),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F2FA),
    surfaceContainer = Color(0xFFF1ECF4), surfaceContainerHigh = Color(0xFFEBE6EE),
    surfaceContainerHighest = Color(0xFFE5E1E9),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFC9BEFF), onPrimary = Color(0xFF2C0F9E),
    primaryContainer = Color(0xFF4324C9), onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFFFFB0C9), onSecondary = Color(0xFF650033),
    secondaryContainer = Color(0xFF8E0F4B), onSecondaryContainer = Color(0xFFFFD9E4),
    tertiary = Color(0xFF6FDBC9), onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005047), onTertiaryContainer = Color(0xFFA6F2E3),
    background = Color(0xFF131218), onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF131218), onSurface = Color(0xFFE5E1E9),
    surfaceVariant = Color(0xFF48454E), onSurfaceVariant = Color(0xFFCAC4CF),
    outline = Color(0xFF938F99), outlineVariant = Color(0xFF48454E),
    surfaceContainerLowest = Color(0xFF0E0D13), surfaceContainerLow = Color(0xFF1C1B20),
    surfaceContainer = Color(0xFF201F25), surfaceContainerHigh = Color(0xFF2A292F),
    surfaceContainerHighest = Color(0xFF35343A),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

val BrandColors = listOf(Color(0xFF5B2BFF), Color(0xFFB42BFF), Color(0xFFFF3D81), Color(0xFFFFA63D))
val BrandBrush: Brush get() = Brush.linearGradient(BrandColors)

/** Semantic colours for relevance levels, tuned per theme. */
data class Extra(val high: Color, val onHigh: Color, val mid: Color, val onMid: Color, val low: Color, val onLow: Color, val star: Color, val dark: Boolean)

val LocalExtra = staticCompositionLocalOf {
    Extra(Color(0xFFBDF3D0), Color(0xFF00391A), Color(0xFFFFE3A6), Color(0xFF402D00), Color(0xFFE5E1E9), Color(0xFF48454E), Color(0xFFF5A300), false)
}

@Composable
private fun ColorScheme.animated(): ColorScheme {
    @Composable
    fun a(c: Color): Color {
        val v by animateColorAsState(c, tween(450), label = "theme")
        return v
    }
    return copy(
        primary = a(primary), onPrimary = a(onPrimary), primaryContainer = a(primaryContainer), onPrimaryContainer = a(onPrimaryContainer),
        secondaryContainer = a(secondaryContainer), onSecondaryContainer = a(onSecondaryContainer),
        tertiaryContainer = a(tertiaryContainer), onTertiaryContainer = a(onTertiaryContainer),
        background = a(background), onBackground = a(onBackground), surface = a(surface), onSurface = a(onSurface),
        surfaceVariant = a(surfaceVariant), onSurfaceVariant = a(onSurfaceVariant),
        surfaceContainerLowest = a(surfaceContainerLowest), surfaceContainerLow = a(surfaceContainerLow),
        surfaceContainer = a(surfaceContainer), surfaceContainerHigh = a(surfaceContainerHigh),
        surfaceContainerHighest = a(surfaceContainerHighest), outlineVariant = a(outlineVariant),
    )
}

@Composable
fun isDark(mode: String): Boolean = when (mode) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DCruxTheme(mode: String, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val context = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    val extra = if (dark) {
        Extra(Color(0xFF0F5132), Color(0xFFBDF3D0), Color(0xFF5C4300), Color(0xFFFFE3A6), Color(0xFF35343A), Color(0xFFCAC4CF), Color(0xFFFFC94D), true)
    } else {
        Extra(Color(0xFFBDF3D0), Color(0xFF00391A), Color(0xFFFFE3A6), Color(0xFF402D00), Color(0xFFE5E1E9), Color(0xFF48454E), Color(0xFFF5A300), false)
    }
    CompositionLocalProvider(LocalExtra provides extra) {
        MaterialExpressiveTheme(
            colorScheme = scheme.animated(),
            motionScheme = MotionScheme.expressive(),
            shapes = AppShapes,
            typography = AppTypography,
            content = content,
        )
    }
}
