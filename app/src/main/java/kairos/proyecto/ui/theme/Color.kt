package kairos.proyecto.ui.theme

import androidx.compose.ui.graphics.Color
import kairos.proyecto.domain.model.Severity
import kairos.proyecto.domain.model.StepCategory

// Marca
val BrandBlue = Color(0xFF2563EB)
val BrandBlueDark = Color(0xFF8AB4FF)

// Superficies (estilo tablero: fondo gris azulado suave y tarjetas blancas)
val LightBackground = Color(0xFFF2F5FA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE9EEF6)
val DarkBackground = Color(0xFF0E1116)
val DarkSurface = Color(0xFF181C24)
val DarkSurfaceVariant = Color(0xFF232935)

// Semáforo de severidad. El acento se usa en puntos, anillos y etiquetas; el degradado, en la tarjeta principal.
val SeverityGreen = Color(0xFF2E9E6B)
val SeverityYellow = Color(0xFFF2B01E)
val SeverityOrange = Color(0xFFF07A1F)
val SeverityRed = Color(0xFFD93B3B)

fun Severity.accent(): Color = when (this) {
    Severity.GREEN -> SeverityGreen
    Severity.YELLOW -> SeverityYellow
    Severity.ORANGE -> SeverityOrange
    Severity.RED -> SeverityRed
}

/** Degradado de la tarjeta principal. Más oscuro que el acento para que el texto blanco tenga contraste. */
fun Severity.heroColors(): List<Color> = when (this) {
    Severity.GREEN -> listOf(Color(0xFF1F7A55), Color(0xFF2E9E6B))
    Severity.YELLOW -> listOf(Color(0xFF9A6B00), Color(0xFFC48A0A))
    Severity.ORANGE -> listOf(Color(0xFFC2500A), Color(0xFFE0700F))
    Severity.RED -> listOf(Color(0xFF9E1F1F), Color(0xFFD93B3B))
}

/** Un color por módulo para reconocerlos de un vistazo sin leer. */
fun StepCategory.accent(): Color = when (this) {
    StepCategory.SAFETY -> Color(0xFF2F80ED)
    StepCategory.ASSETS -> Color(0xFF8E6CEF)
    StepCategory.PETS -> Color(0xFFFF8A3D)
    StepCategory.SHELTER -> Color(0xFF1FB5A6)
}
