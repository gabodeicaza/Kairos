package kairos.proyecto.ui.theme

import androidx.compose.ui.graphics.Color
import kairos.proyecto.domain.model.Severity
import kairos.proyecto.domain.model.StepCategory

// Marca
val BrandBlue = Color(0xFF1F6BFF)
val BrandBlueDark = Color(0xFF7FA8FF)

// Superficies (estilo tablero: fondo gris azulado suave y tarjetas blancas)
val LightBackground = Color(0xFFEAF1FF)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE9EEF6)
val DarkBackground = Color(0xFF0E1116)
val DarkSurface = Color(0xFF181C24)
val DarkSurfaceVariant = Color(0xFF232935)

// Semáforo de severidad. El acento se usa en puntos, anillos y etiquetas; el degradado, en la tarjeta principal.
val SeverityGreen = Color(0xFF12C27E)
val SeverityYellow = Color(0xFFFFB800)
val SeverityOrange = Color(0xFFFF7A00)
val SeverityRed = Color(0xFFFF3B4A)

fun Severity.accent(): Color = when (this) {
    Severity.GREEN -> SeverityGreen
    Severity.YELLOW -> SeverityYellow
    Severity.ORANGE -> SeverityOrange
    Severity.RED -> SeverityRed
}

/** Degradado de la tarjeta principal. Más oscuro que el acento para que el texto blanco tenga contraste. */
fun Severity.heroColors(): List<Color> = when (this) {
    Severity.GREEN -> listOf(Color(0xFF0BA36B), Color(0xFF2EE59D))
    Severity.YELLOW -> listOf(Color(0xFFD99100), Color(0xFFFFC53D))
    Severity.ORANGE -> listOf(Color(0xFFE85D00), Color(0xFFFF9A2E))
    Severity.RED -> listOf(Color(0xFFD91E36), Color(0xFFFF5A6A))
}

/** Un color por módulo para reconocerlos de un vistazo sin leer. */
fun StepCategory.accent(): Color = when (this) {
    StepCategory.SAFETY -> Color(0xFF1F8BFF)
    StepCategory.ASSETS -> Color(0xFF8B5CF6)
    StepCategory.PETS -> Color(0xFFFF7A1A)
    StepCategory.SHELTER -> Color(0xFF00C2B3)
}
