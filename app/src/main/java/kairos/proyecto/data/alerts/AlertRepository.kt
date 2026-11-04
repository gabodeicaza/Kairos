package kairos.proyecto.data.alerts

import android.content.Context
import kairos.proyecto.domain.model.Alert
import kairos.proyecto.domain.model.HazardType
import kairos.proyecto.domain.model.Severity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline-first: intenta refrescar desde todas las fuentes y guarda el resultado en disco;
 * si una fuente falla, usa la última copia local. Las alertas son públicas (sin datos personales).
 */
class AlertRepository(context: Context, private val sources: List<AlertSource>) {

    private val prefs = context.getSharedPreferences("alert_cache", Context.MODE_PRIVATE)

    suspend fun refresh(regionId: String, nowMillis: Long): List<Alert> {
        val fresh = sources.flatMap { source ->
            runCatching { source.fetchActive(regionId, nowMillis) }.getOrNull() ?: return cached(regionId, nowMillis)
        }
        prefs.edit().putString(regionId, encode(fresh)).apply()
        return fresh.filter { it.isActive(nowMillis) }
    }

    fun cached(regionId: String, nowMillis: Long): List<Alert> =
        decode(prefs.getString(regionId, null)).filter { it.isActive(nowMillis) }

    private fun encode(alerts: List<Alert>): String = JSONArray().also { arr ->
        alerts.forEach { a ->
            arr.put(JSONObject().apply {
                put("id", a.id); put("source", a.sourceId); put("hazard", a.hazard.name)
                put("severity", a.severity.name); put("region", a.regionId)
                put("zips", JSONArray(a.postalCodes.toList())); put("headline", a.headline)
                put("issued", a.issuedAtMillis); put("expires", a.expiresAtMillis); put("sim", a.simulated)
                a.expectedImpactMillis?.let { put("impact", it) }
            })
        }
    }.toString()

    private fun decode(json: String?): List<Alert> {
        if (json == null) return emptyList()
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val zips = o.getJSONArray("zips")
            Alert(
                id = o.getString("id"), sourceId = o.getString("source"),
                hazard = HazardType.valueOf(o.getString("hazard")), severity = Severity.valueOf(o.getString("severity")),
                regionId = o.getString("region"), postalCodes = (0 until zips.length()).map { zips.getString(it) }.toSet(),
                headline = o.getString("headline"), issuedAtMillis = o.getLong("issued"),
                expiresAtMillis = o.getLong("expires"), simulated = o.getBoolean("sim"),
                expectedImpactMillis = if (o.has("impact")) o.getLong("impact") else null,
            )
        }
    }
}
