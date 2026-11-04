package kairos.proyecto.domain.decision

import kairos.proyecto.domain.model.Checklist
import kairos.proyecto.domain.model.ChecklistStep
import kairos.proyecto.domain.model.Phase

private const val HOUR_MILLIS = 3_600_000L

/** Instante en que se desbloquea la fase; null si está disponible desde el inicio de la alerta. */
fun Phase.unlockMillis(impactMillis: Long?): Long? = when (this) {
    Phase.ONGOING, Phase.EARLY -> null
    Phase.MINUS_12 -> impactMillis?.minus(12 * HOUR_MILLIS)
    Phase.MINUS_2 -> impactMillis?.minus(2 * HOUR_MILLIS)
    Phase.POST_IMPACT -> impactMillis
}

fun Phase.isUnlocked(impactMillis: Long?, nowMillis: Long): Boolean =
    if (this == Phase.ONGOING || this == Phase.EARLY) true
    else unlockMillis(impactMillis)?.let { nowMillis >= it } ?: false

/**
 * Una fase "vence" cuando se desbloquea la que la reemplaza: lo pendiente deja de estar a tiempo.
 * La preparación temprana solo vence con el impacto, porque sigue siendo útil hasta entonces.
 */
private fun Phase.expiresWith(): Phase? = when (this) {
    Phase.EARLY -> Phase.POST_IMPACT
    Phase.MINUS_12 -> Phase.MINUS_2
    Phase.MINUS_2 -> Phase.POST_IMPACT
    else -> null
}

fun Phase.isExpired(impactMillis: Long?, nowMillis: Long): Boolean =
    expiresWith()?.isUnlocked(impactMillis, nowMillis) ?: false

/** Fases presentes en el checklist, en orden cronológico. */
fun Checklist.phases(): List<Phase> = Phase.entries.filter { p -> steps.any { it.phase == p } }

/** Fase vigente: la más avanzada ya desbloqueada. */
fun Checklist.activePhase(nowMillis: Long): Phase? =
    phases().filter { it.isUnlocked(expectedImpactMillis, nowMillis) }.maxByOrNull { it.ordinal }

/** Pasos desbloqueados cuya ventana ya venció sin completarse (alimenta la métrica "ignorados"). */
fun Checklist.ignoredCount(done: Set<String>, nowMillis: Long): Int =
    steps.count { stepKey(alertId, it.id) !in done && it.phase.isExpired(expectedImpactMillis, nowMillis) }

/**
 * La acción más crítica para este minuto: entre los pasos desbloqueados y pendientes, la fase más reciente primero
 * y dentro de ella la mayor prioridad. Así la persona ve una sola cosa por hacer.
 */
fun Checklist.nextAction(done: Set<String>, nowMillis: Long): ChecklistStep? =
    steps.filter { stepKey(alertId, it.id) !in done && it.phase.isUnlocked(expectedImpactMillis, nowMillis) }
        .sortedWith(compareByDescending<ChecklistStep> { it.phase.ordinal }.thenBy { it.priority })
        .firstOrNull()
