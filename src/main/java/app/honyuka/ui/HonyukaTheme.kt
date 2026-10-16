package app.honyuka.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.honyuka.R
import app.honyuka.model.ThemeMode

/** Kawaii-minimalist palette: washi paper by day, yoru indigo by night. */
@Immutable
data class KawaiiPalette(
    val bg: Color,
    val bgSoft: Color,
    val card: Color,
    val border: Color,
    val text: Color,
    val textSoft: Color,
    val textFaint: Color,
    val sakura: Color,
    val sakuraSoft: Color,
    val matcha: Color,
    val matchaSoft: Color,
    val yuzu: Color,
    val yuzuSoft: Color,
    val sora: Color,
    val soraSoft: Color,
    val danger: Color,
    val onAccent: Color,
    val shadow: Color,
    val rice: Color,
    val isDark: Boolean,
)

val WashiPalette = KawaiiPalette(
    bg = Color(0xFFFFF8F2),
    bgSoft = Color(0xFFFCEFE6),
    card = Color(0xFFFFFFFF),
    border = Color(0xFFF1E3DA),
    text = Color(0xFF3A3335),
    textSoft = Color(0xFF7A6E70),
    textFaint = Color(0xFFB4A8A6),
    sakura = Color(0xFFF09AB0),
    sakuraSoft = Color(0xFFFDE4EA),
    matcha = Color(0xFF8DBB8A),
    matchaSoft = Color(0xFFE5F1E2),
    yuzu = Color(0xFFF2C66D),
    yuzuSoft = Color(0xFFFDF1D6),
    sora = Color(0xFF8DB8E0),
    soraSoft = Color(0xFFE3EFFA),
    danger = Color(0xFFE07A7A),
    onAccent = Color(0xFFFFFFFF),
    shadow = Color(0x1A785046),
    rice = Color(0xFFFFFFFF),
    isDark = false,
)

val YoruPalette = KawaiiPalette(
    bg = Color(0xFF1C1A26),
    bgSoft = Color(0xFF232031),
    card = Color(0xFF2A2738),
    border = Color(0xFF36324A),
    text = Color(0xFFF3EDE7),
    textSoft = Color(0xFFB9B0BE),
    textFaint = Color(0xFF7D7590),
    sakura = Color(0xFFF29BB2),
    sakuraSoft = Color(0xFF46303F),
    matcha = Color(0xFF8FBF8C),
    matchaSoft = Color(0xFF2C3B33),
    yuzu = Color(0xFFEBC46F),
    yuzuSoft = Color(0xFF433A2C),
    sora = Color(0xFF92B9E2),
    soraSoft = Color(0xFF2D3550),
    danger = Color(0xFFEB8C8C),
    onAccent = Color(0xFF1C1A26),
    shadow = Color(0x59000000),
    rice = Color(0xFFFBF6EF),
    isDark = true,
)

val LocalKawaii = staticCompositionLocalOf { WashiPalette }

/** Shortcut: `Kawaii.colors.sakura`. */
object Kawaii {
    val colors: KawaiiPalette
        @Composable get() = LocalKawaii.current
}

val RoundedFont = FontFamily(
    Font(R.font.mplusrounded1c_regular, FontWeight.Normal),
    Font(R.font.mplusrounded1c_medium, FontWeight.Medium),
    Font(R.font.mplusrounded1c_bold, FontWeight.Bold),
    Font(R.font.mplusrounded1c_extrabold, FontWeight.ExtraBold),
)

/** Skill type scale: display 34/800, title 24/800, h2 17/700, body 15, caption 12/500, jp 11/500. */
private val KawaiiTypography = Typography(
    displaySmall = TextStyle(fontFamily = RoundedFont, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontFamily = RoundedFont, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = RoundedFont, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = RoundedFont, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 23.sp),
    titleSmall = TextStyle(fontFamily = RoundedFont, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = RoundedFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = RoundedFont, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = RoundedFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = RoundedFont, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = RoundedFont, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = RoundedFont, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.sp),
)

/** The small Japanese subtitle style (never larger than the English label). */
val JpTextStyle: TextStyle
    @Composable get() = MaterialTheme.typography.labelSmall.copy(color = Kawaii.colors.textFaint)

private val KawaiiShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private fun KawaiiPalette.toColorScheme() = if (isDark) {
    darkColorScheme(
        primary = sakura, onPrimary = onAccent,
        primaryContainer = sakuraSoft, onPrimaryContainer = text,
        secondary = matcha, onSecondary = onAccent,
        secondaryContainer = matchaSoft, onSecondaryContainer = text,
        tertiary = sora, onTertiary = onAccent,
        tertiaryContainer = soraSoft, onTertiaryContainer = text,
        error = danger, onError = onAccent,
        errorContainer = sakuraSoft, onErrorContainer = text,
        background = bg, onBackground = text,
        surface = bg, onSurface = text,
        surfaceVariant = bgSoft, onSurfaceVariant = textSoft,
        surfaceContainerLowest = bg, surfaceContainerLow = bgSoft,
        surfaceContainer = card, surfaceContainerHigh = card, surfaceContainerHighest = card,
        outline = textFaint, outlineVariant = border,
    )
} else {
    lightColorScheme(
        primary = sakura, onPrimary = onAccent,
        primaryContainer = sakuraSoft, onPrimaryContainer = text,
        secondary = matcha, onSecondary = onAccent,
        secondaryContainer = matchaSoft, onSecondaryContainer = text,
        tertiary = sora, onTertiary = onAccent,
        tertiaryContainer = soraSoft, onTertiaryContainer = text,
        error = danger, onError = onAccent,
        errorContainer = sakuraSoft, onErrorContainer = text,
        background = bg, onBackground = text,
        surface = bg, onSurface = text,
        surfaceVariant = bgSoft, onSurfaceVariant = textSoft,
        surfaceContainerLowest = card, surfaceContainerLow = bgSoft,
        surfaceContainer = card, surfaceContainerHigh = card, surfaceContainerHighest = card,
        outline = textFaint, outlineVariant = border,
    )
}

@Composable
fun HonyukaTheme(
    themeMode: ThemeMode = ThemeMode.AUTO,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.WASHI -> false
        ThemeMode.YORU -> true
    }
    val palette = if (dark) YoruPalette else WashiPalette
    CompositionLocalProvider(LocalKawaii provides palette) {
        MaterialTheme(
            colorScheme = palette.toColorScheme(),
            typography = KawaiiTypography,
            shapes = KawaiiShapes,
            content = content,
        )
    }
}
