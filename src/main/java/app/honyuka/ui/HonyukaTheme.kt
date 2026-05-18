package app.honyuka.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HonyukaLavenderDarkColors = darkColorScheme(
    primary = Color(0xFFA177FF),
    onPrimary = Color(0xFF380088),
    primaryContainer = Color(0xFF521CBA),
    onPrimaryContainer = Color(0xFFE7D8FF),
    secondary = Color(0xFFC9B0E6),
    onSecondary = Color(0xFF31154F),
    secondaryContainer = Color(0xFF492D67),
    onSecondaryContainer = Color(0xFFE6CCFF),
    tertiary = Color(0xFFDED0F1),
    onTertiary = Color(0xFF2E1954),
    tertiaryContainer = Color(0xFF462E6B),
    onTertiaryContainer = Color(0xFFF0E3FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF151219),
    onBackground = Color(0xFFE7E0EB),
    surface = Color(0xFF151219),
    onSurface = Color(0xFFE7E0EB),
    surfaceVariant = Color(0xFF4A4453),
    onSurfaceVariant = Color(0xFFCBC3D6),
    outline = Color(0xFF958E9F),
    outlineVariant = Color(0xFF4A4453),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFE7E0EB),
    inverseOnSurface = Color(0xFF322F38),
    inversePrimary = Color(0xFF6D41C8),
    surfaceDim = Color(0xFF151219),
    surfaceBright = Color(0xFF3B383E),
    surfaceContainerLowest = Color(0xFF0F0D13),
    surfaceContainerLow = Color(0xFF1D1A22),
    surfaceContainer = Color(0xFF211E26),
    surfaceContainerHigh = Color(0xFF2C2931),
    surfaceContainerHighest = Color(0xFF37343B),
)

@Composable
fun HonyukaTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = HonyukaLavenderDarkColors,
        content = content,
    )
}
