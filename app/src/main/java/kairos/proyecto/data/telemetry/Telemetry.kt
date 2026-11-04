package kairos.proyecto.data.telemetry

import android.content.Context
import android.util.Log
import kairos.proyecto.data.local.LocalSettings
import kairos.proyecto.domain.model.Alert
import kairos.proyecto.domain.model.Checklist
import kairos.proyecto.domain.model.HazardType
import kairos.proyecto.domain.model.HouseholdProfile
import kairos.proyecto.domain.model.Phase
import kairos.proyecto.domain.model.Severity
import kairos.proyecto.domain.telemetry.BehaviorAnonymizer
import kairos.proyecto.domain.telemetry.BehaviorReport
import kairos.proyecto.domain.telemetry.InteractionEvent
import kairos.proyecto.domain.telemetry.InteractionType
import kairos.proyecto.domain.telemetry.TelemetryUploader
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Data Logger local: registra la interacción con los checklists. Los eventos crudos nunca salen del dispositivo;
 * solo se envía el [BehaviorReport] ya anonimizado y únicamente con opt-in.
 */
class DataLogger(context: Context) {

    private val prefs = context.getSharedPreferences("data_logger", Context.MODE_PRIVATE)

    fun record(event: InteractionEvent) {
        val all = (events() + event).takeLast(MAX_EVENTS)
        prefs.edit().putString("events", JSONArray().also { arr ->
            all.forEach { e ->
                arr.put(JSONObject().apply {
                    put("type", e.type.name); put("alert", e.alertId); put("hazard", e.hazard.name)
                    put("severity", e.severity.name); put("step", e.stepId); put("phase", e.phase.name)
                    put("at", e.epochMillis); put("issued", e.alertIssuedAtMillis)
                })
            }
        }.toString()).apply()
    }

    fun events(): List<InteractionEvent> {
        val arr = JSONArray(prefs.getString("events", "[]"))
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                InteractionEvent(
                    InteractionType.valueOf(o.getString("type")), o.getString("alert"), HazardType.valueOf(o.getString("hazard")),
                    Severity.valueOf(o.getString("severity")), o.getString("step"), Phase.valueOf(o.getString("phase")),
                    o.getLong("at"), o.getLong("issued"),
                )
            }.getOrNull()
        }
    }

    fun wipe() = prefs.edit().clear().apply()

    private companion object {
        const val MAX_EVENTS = 500
    }
}

/** Adaptador SIMULADO del backend: no hay red; guarda y registra el JSON que se enviaría. */
class SimulatedTelemetryUploader(context: Context) : TelemetryUploader {

    private val prefs = context.getSharedPreferences("telemetry_outbox_sim", Context.MODE_PRIVATE)

    override suspend fun upload(report: BehaviorReport) {
        val json = report.toJson().toString()
        Log.i("KairosTelemetry", "SIMULADO - reporte anonimizado: $json")
        prefs.edit().putString("last", json).putInt("count", sentCount() + 1).apply()
    }

    fun sentCount() = prefs.getInt("count", 0)
    fun lastPayload(): String? = prefs.getString("last", null)
    fun wipe() = prefs.edit().clear().apply()

    private fun BehaviorReport.toJson() = JSONObject().apply {
        put("reportId", reportId); put("regionId", regionId); put("zonePrefix", zonePrefix)
        put("hazard", hazard.name); put("severity", severity.name); put("dayBucket", dayBucket)
        put("stepsTotal", stepsTotal); put("stepsCompleted", stepsCompleted); put("stepsIgnored", stepsIgnored)
        put("response", response.name); put("productClicks", productClicks)
        put("groundFloor", groundFloor); put("hasChildren", hasChildren); put("hasElderly", hasElderly); put("hasPets", hasPets)
    }
}

class TelemetryService(
    private val logger: DataLogger,
    private val uploader: TelemetryUploader,
    private val settings: LocalSettings,
    private val anonymizer: BehaviorAnonymizer = BehaviorAnonymizer(),
) {
    fun record(event: InteractionEvent) = logger.record(event)

    fun localEventCount() = logger.events().size

    /** Un reporte anonimizado por alerta y día; solo con opt-in. Si el envío falla, se reintenta en el siguiente refresco. */
    suspend fun flushDaily(
        alert: Alert, checklist: Checklist, done: Set<String>, profile: HouseholdProfile, postalCode: String, nowMillis: Long,
    ) {
        if (!settings.consent.behaviorSharingOptIn) return
        val today = nowMillis / DAY_MILLIS
        val key = "${alert.id}:$today"
        if (key in settings.reportedKeys) return
        val report = anonymizer.build(UUID.randomUUID().toString(), alert, checklist, done, profile, postalCode, logger.events(), nowMillis)
        runCatching { uploader.upload(report) }.onSuccess {
            settings.reportedKeys = (settings.reportedKeys + key).filter { it.substringAfterLast(':').toLongOrNull()?.let { d -> d >= today - 1 } == true }.toSet()
        }
    }

    fun wipe() = logger.wipe()

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
