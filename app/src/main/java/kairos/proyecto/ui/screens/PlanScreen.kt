package kairos.proyecto.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kairos.proyecto.domain.decision.activePhase
import kairos.proyecto.domain.decision.completedCount
import kairos.proyecto.domain.decision.isExpired
import kairos.proyecto.domain.decision.nextAction
import kairos.proyecto.domain.decision.phases
import kairos.proyecto.domain.decision.stepKey
import kairos.proyecto.domain.decision.unlockMillis
import kairos.proyecto.domain.model.Checklist
import kairos.proyecto.domain.model.ChecklistStep
import kairos.proyecto.domain.model.Phase
import kairos.proyecto.domain.model.StepCategory
import kairos.proyecto.ui.AlertWithChecklist
import kairos.proyecto.ui.PhaseState
import kairos.proyecto.ui.UiState
import kairos.proyecto.ui.cleanHeadline
import kairos.proyecto.ui.components.ActionableProductCard
import kairos.proyecto.ui.components.KCard
import kairos.proyecto.ui.components.Pill
import kairos.proyecto.ui.components.ProgressRing
import kairos.proyecto.ui.formatDuration
import kairos.proyecto.ui.phaseState
import kairos.proyecto.ui.theme.SeverityGreen
import kairos.proyecto.ui.theme.accent

/** Plan completo por fases. Se puede filtrar por módulo (viene preseleccionado al tocar uno en el tablero). */
@Composable
fun PlanScreen(
    state: UiState,
    filter: StepCategory?,
    onFilter: (StepCategory?) -> Unit,
    onToggleStep: (String, String) -> Unit,
    onProductClick: (String, String) -> Unit,
) {
    var expandedId by remember { mutableStateOf<String?>(null) }
    val openId = expandedId ?: state.items.firstOrNull()?.alert?.id

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Tu plan", style = MaterialTheme.typography.headlineSmall)
                Text("Una fase a la vez: lo que toca ahora aparece primero y lo demás se desbloquea con el tiempo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter == null, { onFilter(null) }, label = { Text("Todos") })
                    StepCategory.entries.forEach { cat ->
                        FilterChip(filter == cat, { onFilter(if (filter == cat) null else cat) }, label = { Text(cat.label) })
                    }
                }
            }
        }
        if (state.items.isEmpty()) item { Text("No hay alertas activas, así que no hay plan por seguir.", style = MaterialTheme.typography.bodyMedium) }
        items(state.items, key = { it.alert.id }) { item ->
            AlertPlanCard(item, state, filter, expanded = item.alert.id == openId, onExpand = { expandedId = item.alert.id }, onToggleStep, onProductClick)
        }
    }
}

@Composable
private fun AlertPlanCard(
    item: AlertWithChecklist, state: UiState, filter: StepCategory?, expanded: Boolean, onExpand: () -> Unit,
    onToggleStep: (String, String) -> Unit, onProductClick: (String, String) -> Unit,
) {
    val c = item.checklist
    val total = c.steps.size.coerceAtLeast(1)
    val done = c.completedCount(state.completed)
    KCard(Modifier.fillMaxWidth(), onClick = onExpand) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProgressRing(done.toFloat() / total, item.alert.severity.accent(), size = 52.dp, stroke = 6.dp) {
                Text("${done * 100 / total}%", style = MaterialTheme.typography.labelSmall)
            }
            Column(Modifier.weight(1f)) {
                Text("${item.alert.severity.label} · ${item.alert.hazard.label}", style = MaterialTheme.typography.titleMedium)
                Text(item.alert.cleanHeadline(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expanded) 3 else 2)
                Text("$done/${c.steps.size} pasos · ≈ ${c.totalMinutes} min en total", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (expanded) {
            val active = c.activePhase(state.nowMillis)
            val next = c.nextAction(state.completed, state.nowMillis)
            // La fase vigente va primero; el resto en orden cronológico.
            c.phases().sortedWith(compareByDescending<Phase> { it == active }.thenBy { it.ordinal }).forEach { phase ->
                PhaseSection(phase, c, state, filter, next?.id, item.alert.id, onToggleStep, onProductClick)
            }
        } else {
            Text("Toca para ver tu plan por fases", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun PhaseSection(
    phase: Phase, checklist: Checklist, state: UiState, filter: StepCategory?, nextId: String?, alertId: String,
    onToggleStep: (String, String) -> Unit, onProductClick: (String, String) -> Unit,
) {
    val impact = checklist.expectedImpactMillis
    val all = checklist.steps.filter { it.phase == phase }
    val steps = all.filter { filter == null || it.category == filter }
    if (steps.isEmpty()) return
    val st = phaseState(checklist, phase, state.completed, state.nowMillis)
    var open by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(phase.label, style = MaterialTheme.typography.titleSmall, fontWeight = if (st == PhaseState.ACTIVE) FontWeight.Bold else FontWeight.SemiBold)
            when (st) {
                PhaseState.ACTIVE -> Pill("AHORA", MaterialTheme.colorScheme.primary, filled = true)
                PhaseState.DONE -> Pill("COMPLETADA", SeverityGreen)
                PhaseState.LOCKED -> Pill("BLOQUEADA", MaterialTheme.colorScheme.onSurfaceVariant)
                PhaseState.PENDING -> Unit
            }
        }
        when (st) {
            PhaseState.LOCKED -> {
                val left = (phase.unlockMillis(impact) ?: state.nowMillis) - state.nowMillis
                Text("Se desbloquea en ${formatDuration(left)} · ${steps.size} ${if (steps.size == 1) "paso" else "pasos"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PhaseState.DONE -> Unit
            PhaseState.PENDING -> if (!open) {
                val pending = steps.count { stepKey(alertId, it.id) !in state.completed }
                Text("$pending ${if (pending == 1) "paso pendiente" else "pasos pendientes"} · toca para ver", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { open = true })
            } else StepList(steps, checklist, phase, state, nextId, alertId, false, onToggleStep, onProductClick)
            PhaseState.ACTIVE -> StepList(steps, checklist, phase, state, nextId, alertId, true, onToggleStep, onProductClick)
        }
    }
}

@Composable
private fun StepList(
    steps: List<ChecklistStep>, checklist: Checklist, phase: Phase, state: UiState, nextId: String?, alertId: String, isActive: Boolean,
    onToggleStep: (String, String) -> Unit, onProductClick: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        steps.forEach { s ->
            StepRow(
                s, done = stepKey(alertId, s.id) in state.completed, isNext = s.id == nextId, critical = isActive && s.priority <= 2,
                expired = phase.isExpired(checklist.expectedImpactMillis, state.nowMillis),
                showHours = phase != Phase.ONGOING && phase != Phase.POST_IMPACT,
                onToggle = { onToggleStep(alertId, s.id) }, onProductClick = { onProductClick(alertId, s.id) },
            )
        }
    }
}

@Composable
private fun StepRow(
    step: ChecklistStep, done: Boolean, isNext: Boolean, critical: Boolean, expired: Boolean, showHours: Boolean,
    onToggle: () -> Unit, onProductClick: () -> Unit,
) {
    val emphasized = (isNext || critical) && !done
    val accent = step.category.accent()
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(if (emphasized) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Checkbox(done, { onToggle() }, colors = CheckboxDefaults.colors(checkedColor = accent), modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(step.title, style = MaterialTheme.typography.titleSmall, fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(step.category.label, accent)
                    Text("≈ ${step.estimatedMinutes} min" + if (showHours) " · ideal ${step.hoursBeforeEvent} h antes" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (emphasized) Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (expired && !done) Text("Su ventana ideal ya pasó, pero aún puedes hacerlo.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // La compra opcional va aparte, con su propio borde, y nunca dentro de la acción de supervivencia.
        step.product?.let { ActionableProductCard(it, onProductClick) }
    }
}
