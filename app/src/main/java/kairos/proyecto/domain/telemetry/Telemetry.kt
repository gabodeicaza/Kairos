package kairos.proyecto.domain.telemetry

import kairos.proyecto.domain.decision.completedCount
import kairos.proyecto.domain.decision.ignoredCount
import kairos.proyecto.domain.model.Alert
import kairos.proyecto.domain.model.Checklist
import kairos.proyecto.domain.model.HazardType
import kairos.proyecto.domain.model.HouseholdProfile
import kairos.proyecto.domain.model.Phase
import kairos.proyecto.domain.model.Severity

// ---------- Eventos crudos (solo en el dispositivo; nunca salen tal cual) ----------

enum class InteractionType { STEP_COMPLETED, STEP_UNCOMPLETED, PRODUCT_CLICKED }

data class InteractionEvent(
    val type: InteractionType,
    val alertId: String,
    val hazard: HazardType,
    val severity: Severity,
    val stepId: String,
    val phase: Phase,
    val epochMillis: Long,
    /** Emisión del boletín vigente al momento del evento; base del "tiempo de respuesta tras la alerta". */
    val alertIssuedAtMillis: Long,
)

// ---------- Reporte anonimizado (contrato con el backend / tableros B2G-B2B) ----------

enum class ResponseBucket(val label: String) {
    UNDER_5_MIN("< 5 min"), MIN_5_TO_30("5-30 min"), MIN_30_TO_120("30 min - 2 h"), OVER_2_H("> 2 h"), NO_ACTION("Sin acción")
}

/**
 * Un reporte por alerta y día. No contiene: identificador de persona o dispositivo, dirección, código postal
 * completo, ids de alerta o de paso, ni marcas de tiempo exactas. El `reportId` es aleatorio y distinto en
 * cada reporte, así que no permite enlazar reportes de una misma persona. La supresión de grupos pequeños
 * (k-anonimato) se aplica además en el servidor antes de publicar en tableros.
 */
data class BehaviorReport(
    val reportId: String,
    val regionId: String,
    /** Solo los 3 primeros dígitos del código postal. */
    val zonePrefix: String,
    val hazard: HazardType,
    val severity: Severity,
    /** Día (epoch day), sin hora. */
    val dayBucket: Long,
    val stepsTotal: Int,
    val stepsCompleted: Int,
    val stepsIgnored: Int,
    val response: ResponseBucket,
    val productClicks: Int,
    val groundFloor: Boolean,
    val hasChildren: Boolean,
    val hasElderly: Boolean,
    val hasPets: Boolean,
)

/** Indicador agregado para los tableros: lo que ven Protección Civil (principal) y la aseguradora (secundario). */
data class BehaviorIndicator(
    val regionId: String,
    val zonePrefix: String,
    val hazard: HazardType,
    val reports: Int,
    val avgCompletionShare: Double,
    val avgIgnoredShare: Double,
    val noActionShare: Double,
    val productClickShare: Double,
)

/** Punto de integración con el backend. Hoy lo implementa un adaptador SIMULADO (data/telemetry). */
interface TelemetryUploader {
    suspend fun upload(report: BehaviorReport)
}

class BehaviorAnonymizer {

    fun build(
        reportId: String,
        alert: Alert,
        checklist: Checklist,
        done: Set<String>,
        profile: HouseholdProfile,
        postalCode: String,
        events: List<InteractionEvent>,
        nowMillis: Long,
    ): BehaviorReport {
        val mine = events.filter { it.alertId == alert.id }
        val firstDone = mine.filter { it.type == InteractionType.STEP_COMPLETED }.minByOrNull { it.epochMillis }
        return BehaviorReport(
            reportId = reportId,
            regionId = alert.regionId,
            zonePrefix = postalCode.take(3),
            hazard = alert.hazard,
            severity = alert.severity,
            dayBucket = nowMillis / DAY_MILLIS,
            stepsTotal = checklist.steps.size,
            stepsCompleted = checklist.completedCount(done),
            stepsIgnored = checklist.ignoredCount(done, nowMillis),
            response = firstDone?.let { bucket(it.epochMillis - it.alertIssuedAtMillis) } ?: ResponseBucket.NO_ACTION,
            productClicks = mine.count { it.type == InteractionType.PRODUCT_CLICKED },
            groundFloor = profile.groundFloor,
            hasChildren = profile.hasChildren,
            hasElderly = profile.hasElderly,
            hasPets = profile.hasPets,
        )
    }

    private fun bucket(millis: Long): ResponseBucket {
        val minutes = millis.coerceAtLeast(0) / 60_000
        return when {
            minutes < 5 -> ResponseBucket.UNDER_5_MIN
            minutes < 30 -> ResponseBucket.MIN_5_TO_30
            minutes < 120 -> ResponseBucket.MIN_30_TO_120
            else -> ResponseBucket.OVER_2_H
        }
    }

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
