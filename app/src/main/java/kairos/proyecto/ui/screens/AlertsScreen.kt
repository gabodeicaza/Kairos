package kairos.proyecto.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kairos.proyecto.domain.model.Severity
import kairos.proyecto.ui.AlertWithChecklist
import kairos.proyecto.ui.UiState
import kairos.proyecto.ui.cleanHeadline
import kairos.proyecto.ui.components.KCard
import kairos.proyecto.ui.components.Pill
import kairos.proyecto.ui.formatDuration
import kairos.proyecto.ui.sourceLabel
import kairos.proyecto.ui.theme.accent

/** Alertas activas con su fuente y qué significa cada color: transparencia para generar confianza. */
@Composable
fun AlertsScreen(state: UiState) {
    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Alertas", style = MaterialTheme.typography.headlineSmall)
        }
        if (state.items.isEmpty()) {
            item { Text("No hay alertas activas en tu zona.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.items, key = { it.alert.id }) { AlertCard(it, state) }
        item { Legend() }
    }
}

@Composable
private fun AlertCard(item: AlertWithChecklist, state: UiState) {
    val a = item.alert
    KCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(a.severity.accent()))
            Text("${a.severity.label} · ${a.hazard.label}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Pill(if (a.simulated) "SIMULADO" else "DATO REAL", if (a.simulated) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
        }
        Text(a.cleanHeadline(), style = MaterialTheme.typography.bodyMedium)
        Text("Fuente: ${sourceLabel(a.sourceId)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Emitida hace ${formatDuration(state.nowMillis - a.issuedAtMillis)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        a.expectedImpactMillis?.let {
            Text(
                if (state.nowMillis < it) "Impacto estimado en ${formatDuration(it - state.nowMillis)}" else "El impacto ya ocurrió",
                style = MaterialTheme.typography.titleSmall, color = a.severity.accent(),
            )
        }
        Text(
            if (a.postalCodes.isEmpty()) "Aplica a toda tu región." else "Aplica a los códigos postales ${a.postalCodes.sorted().joinToString(", ")}.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Legend() {
    val meaning = mapOf(
        Severity.GREEN to "Informativa: mantente al tanto.",
        Severity.YELLOW to "Prepárate: hay tiempo para actuar.",
        Severity.ORANGE to "Actúa ya: completa los pasos críticos.",
        Severity.RED to "Peligro inmediato: sigue las indicaciones oficiales.",
    )
    KCard {
        Text("Cómo leer los colores", style = MaterialTheme.typography.titleMedium)
        Severity.entries.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(s.accent()))
                Text("${s.label}: ${meaning.getValue(s)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
