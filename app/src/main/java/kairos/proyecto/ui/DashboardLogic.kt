package kairos.proyecto.ui

import kairos.proyecto.domain.decision.activePhase
import kairos.proyecto.domain.decision.isUnlocked
import kairos.proyecto.domain.decision.stepKey
import kairos.proyecto.domain.model.Alert
import kairos.proyecto.domain.model.Checklist
import kairos.proyecto.domain.model.Phase
import kairos.proyecto.domain.model.StepCategory

/** Duración legible: "35 min", "5 h 20 min", "2 d 3 h". */
fun formatDuration(millis: Long): String {
    val minutes = millis.coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "menos de 1 min"
        minutes < 60 -> "$minutes min"
        minutes < 24 * 60 -> if (minutes % 60 == 0L) "${minutes / 60} h" else "${minutes / 60} h ${minutes % 60} min"
        else -> "${minutes / (24 * 60)} d ${(minutes % (24 * 60)) / 60} h"
    }
}

/** Avance (hechos, total) de un módulo sumando los checklists de todas las alertas activas. */
fun categoryProgress(items: List<AlertWithChecklist>, completed: Set<String>, category: StepCategory): Pair<Int, Int> {
    val steps = items.flatMap { item -> item.checklist.steps.filter { it.category == category }.map { stepKey(item.alert.id, it.id) } }
    return steps.count { it in completed } to steps.size
}

/** Minutos de trabajo que quedan en pasos ya desbloqueados y sin completar. */
fun remainingMinutes(checklist: Checklist, completed: Set<String>, nowMillis: Long): Int =
    checklist.steps
        .filter { stepKey(checklist.alertId, it.id) !in completed && it.phase.isUnlocked(checklist.expectedImpactMillis, nowMillis) }
        .sumOf { it.estimatedMinutes }

enum class PhaseState { DONE, ACTIVE, PENDING, LOCKED }

fun phaseState(checklist: Checklist, phase: Phase, completed: Set<String>, nowMillis: Long): PhaseState {
    if (!phase.isUnlocked(checklist.expectedImpactMillis, nowMillis)) return PhaseState.LOCKED
    val steps = checklist.steps.filter { it.phase == phase }
    if (steps.all { stepKey(checklist.alertId, it.id) in completed }) return PhaseState.DONE
    return if (phase == checklist.activePhase(nowMillis)) PhaseState.ACTIVE else PhaseState.PENDING
}

fun Alert.cleanHeadline(): String = headline.removePrefix("[SIMULADO] ").removePrefix("[NASA EONET] ")

fun sourceLabel(sourceId: String): String = when (sourceId) {
    "sim-pc-guerrero" -> "Protección Civil Guerrero"
    "sim-smn" -> "SMN / CONAGUA"
    "nasa-eonet" -> "NASA EONET"
    else -> sourceId
}
