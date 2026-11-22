package app.oralis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.oralis.R
import app.oralis.data.settings.ThemeMode

// ---------------------------------------------------------------------------------------------
// Palette: warm paper and ink, with a single accent ("ribbon", like a book's ribbon marker).
// ---------------------------------------------------------------------------------------------
private object Ink {
    val paper = Color(0xFFF6F2EA)
    val paperRaised = Color(0xFFFBF8F3)
    val paperSunk = Color(0xFFECE6DB)
    val ink = Color(0xFF1E1B18)
    val inkSoft = Color(0xFF5E5750)
    val rule = Color(0xFFD9D1C5)
    val ribbon = Color(0xFF9C4A33)
    val ribbonSoft = Color(0xFFF1DED5)

    val night = Color(0xFF121110)
    val nightRaised = Color(0xFF1B1917)
    val nightSunk = Color(0xFF25221F)
    val moon = Color(0xFFECE6DC)
    val moonSoft = Color(0xFFA59C91)
    val nightRule = Color(0xFF39342F)
    val ember = Color(0xFFD9967C)
    val emberSoft = Color(0xFF3A2720)
}

private val LightColors = lightColorScheme(
    primary = Ink.ink, onPrimary = Ink.paper,
    primaryContainer = Ink.paperSunk, onPrimaryContainer = Ink.ink,
    secondary = Ink.inkSoft, onSecondary = Ink.paper,
    secondaryContainer = Ink.paperSunk, onSecondaryContainer = Ink.ink,
    tertiary = Ink.ribbon, onTertiary = Ink.paper,
    tertiaryContainer = Ink.ribbonSoft, onTertiaryContainer = Color(0xFF4A1E12),
    background = Ink.paper, onBackground = Ink.ink,
    surface = Ink.paper, onSurface = Ink.ink,
    surfaceVariant = Ink.paperSunk, onSurfaceVariant = Ink.inkSoft,
    surfaceContainerLowest = Ink.paperRaised, surfaceContainerLow = Ink.paperRaised,
    surfaceContainer = Color(0xFFF1ECE3), surfaceContainerHigh = Ink.paperSunk, surfaceContainerHighest = Color(0xFFE5DED2),
    outline = Ink.rule, outlineVariant = Color(0xFFE6DFD4),
    error = Color(0xFFA3362B), onError = Ink.paper,
    errorContainer = Color(0xFFF6DCD6), onErrorContainer = Color(0xFF4A120B),
    inverseSurface = Ink.ink, inverseOnSurface = Ink.paper,
)

private val DarkColors = darkColorScheme(
    primary = Ink.moon, onPrimary = Ink.night,
    primaryContainer = Ink.nightSunk, onPrimaryContainer = Ink.moon,
    secondary = Ink.moonSoft, onSecondary = Ink.night,
    secondaryContainer = Ink.nightSunk, onSecondaryContainer = Ink.moon,
    tertiary = Ink.ember, onTertiary = Ink.night,
    tertiaryContainer = Ink.emberSoft, onTertiaryContainer = Color(0xFFF5D3C5),
    background = Ink.night, onBackground = Ink.moon,
    surface = Ink.night, onSurface = Ink.moon,
    surfaceVariant = Ink.nightSunk, onSurfaceVariant = Ink.moonSoft,
    surfaceContainerLowest = Color(0xFF0D0C0B), surfaceContainerLow = Ink.nightRaised,
    surfaceContainer = Color(0xFF1F1D1A), surfaceContainerHigh = Ink.nightSunk, surfaceContainerHighest = Color(0xFF2E2A26),
    outline = Ink.nightRule, outlineVariant = Color(0xFF2C2824),
    error = Color(0xFFE48B7E), onError = Ink.night,
    errorContainer = Color(0xFF4A1C16), onErrorContainer = Color(0xFFF6D3CC),
    inverseSurface = Ink.moon, inverseOnSurface = Ink.night,
)

// ---------------------------------------------------------------------------------------------
// Typography: Newsreader (literary serif, for titles and reading) + Inter (interface).
// Both bundled (SIL OFL), no network font loading.
// ---------------------------------------------------------------------------------------------
val Newsreader = FontFamily(
    Font(R.font.newsreader_regular, FontWeight.Normal),
    Font(R.font.newsreader_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.newsreader_medium, FontWeight.Medium),
)
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

private val OralisTypography = Typography(
    displayLarge = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Normal, fontSize = 56.sp, lineHeight = 60.sp, letterSpacing = (-1.2).sp),
    displayMedium = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Normal, fontSize = 44.sp, lineHeight = 50.sp, letterSpacing = (-0.8).sp),
    displaySmall = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Normal, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.4).sp),
    headlineLarge = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Normal, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Medium, fontSize = 21.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.1.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.6.sp),
)

/** Reading style for long text (editor, previews). */
val ReadingStyle = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 29.sp)

private val OralisShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Immutable
data class OralisExtras(val isDark: Boolean)

val LocalOralisExtras = staticCompositionLocalOf { OralisExtras(false) }

@Composable
fun OralisTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalOralisExtras provides OralisExtras(dark)) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = OralisTypography,
            shapes = OralisShapes,
            content = content,
        )
    }
}
