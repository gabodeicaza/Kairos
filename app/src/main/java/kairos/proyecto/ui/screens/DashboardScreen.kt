package kairos.proyecto.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kairos.proyecto.domain.decision.completedCount
import kairos.proyecto.domain.decision.nextAction
import kairos.proyecto.domain.decision.phases
import kairos.proyecto.domain.decision.unlockMillis
import kairos.proyecto.domain.model.ChecklistStep
import kairos.proyecto.domain.model.Phase
import kairos.proyecto.domain.model.Severity
import kairos.proyecto.domain.model.StepCategory
import kairos.proyecto.ui.AlertWithChecklist
import kairos.proyecto.ui.PhaseState
import kairos.proyecto.ui.UiState
import kairos.proyecto.ui.cleanHeadline
import kairos.proyecto.ui.categoryProgress
import kairos.proyecto.ui.components.KCard
import kairos.proyecto.ui.components.Pill
import kairos.proyecto.ui.components.ProgressRing
import kairos.proyecto.ui.components.SectionTitle
import kairos.proyecto.ui.components.StatTile
import kairos.proyecto.ui.formatDuration
import kairos.proyecto.ui.phaseState
import kairos.proyecto.ui.remainingMinutes
import kairos.proyecto.ui.theme.SeverityGreen
import kairos.proyecto.ui.theme.accent
import kairos.proyecto.ui.theme.heroColors
import kotlinx.coroutines.delay

/** Tablero inicial: lo que más necesitas ahora, en una pantalla y de un vistazo. */
@Composable
fun DashboardScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onToggleStep: (String, String) -> Unit,
    onOpenPlan: (StepCategory?) -> Unit,
    onOpenAlerts: () -> Unit,
) {
    // Reactivo al tiempo: cada minuto se recalculan fases, cuenta regresiva y siguiente acción.
    LaunchedEffect(Unit) { while (true) { delay(60_000); onRefresh() } }
    val top = state.items.firstOrNull()

    LazyColumn(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item { Header(state) }
        item { if (top != null) HeroCard(top, state, onOpenPlan = { onOpenPlan(null) }) else CalmCard(state) }
        top?.let { t ->
            t.checklist.nextAction(state.completed, state.nowMillis)?.let { step ->
                item { NextActionCard(step, onDone = { onToggleStep(t.alert.id, step.id) }, onOpenPlan = { onOpenPlan(null) }) }
            }
            if (t.checklist.expectedImpactMillis != null) item { PhaseTimeline(t, state) }
        }
        if (state.items.isNotEmpty()) {
            item { SectionTitle("Tu hogar por módulos") }
            item { ModuleGrid(state, onOpenPlan) }
            item { StatsRow(state, top!!) }
        }
        if (state.items.size > 1) {
            item { SectionTitle("Otras alertas") }
            item { OtherAlerts(state.items.drop(1), onOpenAlerts) }
        }
        state.insurance?.let { offer ->
            item {
                KCard {
                    Pill("OPCIONAL", MaterialTheme.colorScheme.primary)
                    Text("Protege tu hogar", style = MaterialTheme.typography.titleMedium)
                    Text("${offer.productName}: $${offer.monthlyPremiumMxn.toInt()} al mes, cobertura hasta $${offer.coverageMxn.toInt()}.", style = MaterialTheme.typography.bodyMedium)
                    Text("Disponible porque completaste tu checklist. Es separado de tu plan y no es obligatorio. Datos simulados.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        state.insuranceStepsLeft?.let { left ->
            item {
                Text(
                    "Microseguro opcional: completa $left ${if (left == 1) "paso más" else "pasos más"} de tu checklist más urgente para ver la oferta.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { Box(Modifier.size(8.dp)) }
    }
}

@Composable
private fun Header(state: UiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Kairos", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("CP ${state.postalCode}", MaterialTheme.colorScheme.primary)
            if (state.zoneLabel.isNotEmpty()) Text(state.zoneLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!state.zoneFound) Text("Tu código postal aún no está en el catálogo del piloto.", color = MaterialTheme.colorScheme.error)
    }
}

/** Tarjeta principal: severidad en color, qué pasa, cuánto falta y el avance en un anillo. */
@Composable
private fun HeroCard(item: AlertWithChecklist, state: UiState, onOpenPlan: () -> Unit) {
    val c = item.checklist
    val total = c.steps.size.coerceAtLeast(1)
    val done = c.completedCount(state.completed)
    val impact = c.expectedImpactMillis
    val timing = when {
        impact == null -> "Alerta prolongada · activa desde hace ${formatDuration(state.nowMillis - item.alert.issuedAtMillis)}"
        state.nowMillis < impact -> "Impacto estimado en ${formatDuration(impact - state.nowMillis)}"
        else -> "El impacto ya ocurrió · fase post-impacto"
    }
    Box(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(item.alert.severity.heroColors()))
            .clickable(onClick = onOpenPlan).padding(28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("ALERTA ${item.alert.severity.label.uppercase()}", Color.White.copy(alpha = 0.25f), filled = true)
                    if (item.alert.simulated) Pill("SIMULADO", Color.Black.copy(alpha = 0.25f), filled = true)
                }
                Text(item.alert.hazard.label, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(item.alert.cleanHeadline(), style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f))
                Text(timing, style = MaterialTheme.typography.titleSmall, color = Color.White)
            }
            ProgressRing(done.toFloat() / total, Color.White, size = 96.dp, stroke = 10.dp, trackColor = Color.White.copy(alpha = 0.28f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${done * 100 / total}%", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text("listo", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }
    }
}

@Composable
private fun CalmCard(state: UiState) {
    Box(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(Severity.GREEN.heroColors())).padding(28.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("TODO EN CALMA", Color.White.copy(alpha = 0.25f), filled = true)
            Text("Sin alertas activas en tu zona", style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                if (state.loading) "Buscando alertas…" else "Es buen momento para preparar tu kit de emergencia y revisar tu ruta de evacuación.",
                style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f),
            )
        }
    }
}

/** Una sola cosa por hacer, con botón para marcarla sin salir del tablero. */
@Composable
private fun NextActionCard(step: ChecklistStep, onDone: () -> Unit, onOpenPlan: () -> Unit) {
    KCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("TU SIGUIENTE ACCIÓN", MaterialTheme.colorScheme.primary)
            Pill(step.category.label, step.category.accent())
        }
        Text(step.title, style = MaterialTheme.typography.titleLarge)
        Text(step.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("≈ ${step.estimatedMinutes} min · ${step.phase.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onDone, shape = RoundedCornerShape(50)) { Text("Hecho") }
            TextButton(onOpenPlan) { Text("Ver plan completo") }
        }
    }
}

/** Línea de tiempo de fases: qué ya se hizo, qué toca ahora y qué se desbloquea después. */
@Composable
private fun PhaseTimeline(item: AlertWithChecklist, state: UiState) {
    val c = item.checklist
    val phases = c.phases().filter { it != Phase.ONGOING }
    KCard {
        Text("Línea de tiempo", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            phases.forEach { phase ->
                val st = phaseState(c, phase, state.completed, state.nowMillis)
                val (color, caption) = when (st) {
                    PhaseState.DONE -> SeverityGreen to "Completada"
                    PhaseState.ACTIVE -> item.alert.severity.accent() to "Ahora"
                    PhaseState.PENDING -> MaterialTheme.colorScheme.primary to "Pendiente"
                    PhaseState.LOCKED -> MaterialTheme.colorScheme.outline to
                        "en ${formatDuration((phase.unlockMillis(c.expectedImpactMillis) ?: state.nowMillis) - state.nowMillis)}"
                }
                Column(
                    Modifier.weight(1f).clip(MaterialTheme.shapes.medium).background(color.copy(alpha = if (st == PhaseState.LOCKED) 0.10f else 0.16f)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Text(phase.label.removePrefix("Fase "), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Cuadrícula 2×2 de módulos con anillo de avance; tocar uno abre el plan filtrado. */
@Composable
private fun ModuleGrid(state: UiState, onOpenPlan: (StepCategory?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StepCategory.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { cat ->
                    val (done, total) = categoryProgress(state.items, state.completed, cat)
                    KCard(Modifier.weight(1f), onClick = { onOpenPlan(cat) }) {
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 140.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                        ) {
                            ProgressRing(if (total == 0) 0f else done.toFloat() / total, cat.accent(), size = 76.dp, stroke = 9.dp) {
                                Text("$done/$total", style = MaterialTheme.typography.titleSmall)
                            }
                            Text(cat.label, style = MaterialTheme.typography.titleSmall, color = cat.accent())
                        }
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatsRow(state: UiState, top: AlertWithChecklist) {
    val doneAll = state.items.sumOf { it.checklist.completedCount(state.completed) }
    val totalAll = state.items.sumOf { it.checklist.steps.size }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("$doneAll/$totalAll", "pasos hechos", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
        StatTile("${remainingMinutes(top.checklist, state.completed, state.nowMillis)} min", "por hacer ahora", top.alert.severity.accent(), Modifier.weight(1f))
        StatTile("${state.items.size}", if (state.items.size == 1) "alerta activa" else "alertas activas", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
    }
}

@Composable
private fun OtherAlerts(items: List<AlertWithChecklist>, onOpenAlerts: () -> Unit) {
    KCard(onClick = onOpenAlerts) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(item.alert.severity.accent()))
                Column(Modifier.weight(1f)) {
                    Text(item.alert.hazard.label, style = MaterialTheme.typography.titleSmall)
                    Text(item.alert.cleanHeadline(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                Text(item.alert.severity.label, style = MaterialTheme.typography.labelMedium, color = item.alert.severity.accent())
            }
        }
    }
}
