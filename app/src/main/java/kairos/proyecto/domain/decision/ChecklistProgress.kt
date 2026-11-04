package kairos.proyecto.domain.decision

import kairos.proyecto.domain.model.Checklist

/** Clave de un paso completado; incluye la alerta porque el mismo paso puede repetirse en alertas distintas. */
fun stepKey(alertId: String, stepId: String) = "$alertId:$stepId"

fun Checklist.completedCount(done: Set<String>) = steps.count { stepKey(alertId, it.id) in done }

/** Un checklist sin pasos cuenta como no completado (no hay nada que demostrar). */
fun Checklist.isCompleted(done: Set<String>) = steps.isNotEmpty() && completedCount(done) == steps.size
