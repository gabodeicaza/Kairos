package kairos.proyecto.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kairos.proyecto.domain.notification.NotificationPrefs
import kairos.proyecto.domain.privacy.LocationMode
import kairos.proyecto.ui.UiState
import kairos.proyecto.ui.components.KCard
import kairos.proyecto.ui.components.Pill

@Composable
internal fun LabeledCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun LocationModePicker(mode: LocationMode, onMode: (LocationMode) -> Unit, gpsConsent: Boolean, onGps: (Boolean) -> Unit) {
    val options = listOf(
        LocationMode.APPROXIMATE to "Aproximada por código postal (recomendada)",
        LocationMode.ONLY_DURING_ALERT to "Solo durante una alerta activa",
        LocationMode.EXACT_GPS to "GPS exacto (requiere tu consentimiento explícito)",
    )
    options.forEach { (m, label) ->
        Row(Modifier.fillMaxWidth().selectable(mode == m) { onMode(m) }, verticalAlignment = Alignment.CenterVertically) {
            RadioButton(mode == m, { onMode(m) })
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (mode == LocationMode.EXACT_GPS) LabeledCheck("Autorizo expresamente el uso de mi ubicación exacta", gpsConsent, onGps)
}

@Composable
fun SettingsScreen(
    state: UiState,
    onPrefs: (NotificationPrefs) -> Unit,
    onMode: (LocationMode, Boolean) -> Unit,
    onShareBehavior: (Boolean) -> Unit,
    onAdvance: (Int) -> Unit,
    onResetSimulation: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    var gps by remember { mutableStateOf(state.consent.exactGpsConsentAtMillis != null) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Ajustes", style = MaterialTheme.typography.headlineSmall)

        KCard {
            Text("Tu hogar", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("CP ${state.postalCode}", MaterialTheme.colorScheme.primary)
                if (state.profile.groundFloor) Pill("Planta baja", MaterialTheme.colorScheme.secondary)
                if (state.profile.hasChildren) Pill("Niñas/niños", MaterialTheme.colorScheme.secondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.profile.hasElderly) Pill("Adultos mayores", MaterialTheme.colorScheme.secondary)
                if (state.profile.hasPets) Pill("Mascotas", MaterialTheme.colorScheme.secondary)
            }
            Text(state.zoneLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        KCard {
            Text("Notificaciones", style = MaterialTheme.typography.titleMedium)
            Text("Avisos de alerta por día: ${state.notificationPrefs.maxAlertsPerDay} (máximo ${NotificationPrefs.MAX_ALERTS_PER_DAY})", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = state.notificationPrefs.maxAlertsPerDay.toFloat(),
                onValueChange = { onPrefs(state.notificationPrefs.copy(maxAlertsPerDay = it.toInt())) },
                valueRange = 0f..NotificationPrefs.MAX_ALERTS_PER_DAY.toFloat(), steps = NotificationPrefs.MAX_ALERTS_PER_DAY - 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(
                    checked = state.notificationPrefs.weeklyPreparednessEnabled,
                    onCheckedChange = { onPrefs(state.notificationPrefs.copy(weeklyPreparednessEnabled = it)) },
                )
                Text("Aviso semanal de preparación", style = MaterialTheme.typography.bodyMedium)
            }
        }

        KCard {
            Text("Ubicación del hogar", style = MaterialTheme.typography.titleMedium)
            LocationModePicker(state.consent.locationMode, { onMode(it, gps) }, gps) { gps = it; onMode(LocationMode.EXACT_GPS, it) }
        }

        KCard {
            Text("Privacidad", style = MaterialTheme.typography.titleMedium)
            Text("Tu dirección, código postal y perfil se guardan cifrados en este teléfono.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = state.consent.behaviorSharingOptIn, onCheckedChange = onShareBehavior)
                Text("Compartir estadísticas anónimas de uso", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Eventos guardados solo en tu teléfono: ${state.localEvents}. Reportes anónimos enviados (simulado): ${state.reportsSent}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        KCard {
            Text("Simulación del piloto", style = MaterialTheme.typography.titleMedium)
            Text(
                "Escenarios SIMULADOS: huracán tipo Otis en Acapulco (tormenta tropical a categoría 5 en 11 h) y calor extremo con " +
                    "escasez de agua en Monterrey. Horas transcurridas: ${"%.1f".format(state.simulatedHours)}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ onAdvance(3) }) { Text("+3 h") }
                OutlinedButton({ onAdvance(12) }) { Text("+12 h") }
                OutlinedButton({ onAdvance(72) }) { Text("+3 d") }
                OutlinedButton(onResetSimulation) { Text("Reiniciar") }
            }
        }

        Button(
            onDeleteAll, Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) { Text("Borrar todos mis datos") }
    }
}
