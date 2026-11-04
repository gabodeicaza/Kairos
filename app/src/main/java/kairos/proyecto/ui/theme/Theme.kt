package kairos.proyecto.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF),
    onPrimaryContainer = Color(0xFF0B2A6B),
    secondary = Color(0xFF475569),
    secondaryContainer = Color(0xFFE7ECF5),
    onSecondaryContainer = Color(0xFF1F2937),
    background = LightBackground,
    onBackground = Color(0xFF111827),
    surface = LightSurface,
    onSurface = Color(0xFF111827),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF5B6575),
    outline = Color(0xFFD5DCE8),
    outlineVariant = Color(0xFFE4E9F2),
    error = SeverityRed,
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF0B1B3F),
    primaryContainer = Color(0xFF1E3566),
    onPrimaryContainer = Color(0xFFDCE7FF),
    secondary = Color(0xFFB6C2D6),
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = Color(0xFFE5E9F0),
    background = DarkBackground,
    onBackground = Color(0xFFE8ECF2),
    surface = DarkSurface,
    onSurface = Color(0xFFE8ECF2),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFF9AA5B8),
    outline = Color(0xFF3A4252),
    outlineVariant = Color(0xFF2A3140),
    error = Color(0xFFFF6B6B),
)

private val KairosShapes = Shapes(
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
)

/** Sin colores dinámicos a propósito: la paleta de severidad y de módulos debe verse igual en todos los teléfonos. */
@Composable
fun KairosTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = KairosTypography,
        shapes = KairosShapes,
        content = content,
    )
}
