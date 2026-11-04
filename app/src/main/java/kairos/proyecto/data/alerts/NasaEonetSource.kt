package kairos.proyecto.data.alerts

import kairos.proyecto.domain.model.Alert
import kairos.proyecto.domain.model.HazardType
import kairos.proyecto.domain.model.Regions
import kairos.proyecto.domain.model.Severity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Recuadro geográfico de una región piloto (solo para filtrar eventos globales de NASA; no son datos del hogar). */
internal class GeoBox(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double) {
    fun contains(lon: Double, lat: Double) = lon in minLon..maxLon && lat in minLat..maxLat
}

/** Incluye la costa del Pacífico y del Golfo para capturar tormentas aún en el mar. */
internal val REGION_BOXES = mapOf(
    Regions.SOUTHWEST.id to GeoBox(-106.0, 14.0, -96.0, 20.0),
    Regions.NORTHEAST.id to GeoBox(-101.5, 22.0, -93.0, 28.0),
)

/** Convierte la respuesta JSON de EONET v3 en alertas. Separado de la red para poder probarlo. */
internal object EonetParser {
    private const val HOUR = 3_600_000L
    private const val MAX_AGE_HOURS = 72L
    private const val VALID_FOR_HOURS = 6L

    fun parse(json: String, regionId: String, nowMillis: Long): List<Alert> {
        val box = REGION_BOXES[regionId] ?: return emptyList()
        val events = JSONObject(json).optJSONArray("events") ?: return emptyList()
        return (0 until events.length()).mapNotNull { i -> toAlert(events.getJSONObject(i), regionId, box, nowMillis) }
    }

    private fun toAlert(event: JSONObject, regionId: String, box: GeoBox, now: Long): Alert? {
        val category = event.optJSONArray("categories")?.optJSONObject(0)?.optString("id")
        val hazard = when (category) {
            "severeStorms" -> HazardType.HURRICANE
            "floods" -> HazardType.FLOOD
            else -> return null
        }
        // Última observación ya ocurrida (EONET puede incluir puntos de pronóstico).
        val geometry = event.optJSONArray("geometry") ?: return null
        val latest = (0 until geometry.length()).map { geometry.getJSONObject(it) }
            .mapNotNull { g -> runCatching { Instant.parse(g.getString("date")).toEpochMilli() }.getOrNull()?.let { it to g } }
            .filter { it.first <= now }
            .maxByOrNull { it.first } ?: return null
        val (observedAt, point) = latest
        if (now - observedAt > MAX_AGE_HOURS * HOUR) return null
        val (lon, lat) = firstCoordinate(point.optJSONArray("coordinates")) ?: return null
        if (!box.contains(lon, lat)) return null

        val wind = point.optDouble("magnitudeValue", Double.NaN).takeIf { !it.isNaN() }
        val severity = if (hazard == HazardType.HURRICANE) stormSeverity(wind) else Severity.YELLOW
        val detail = wind?.let { " (vientos de ${it.toInt()} nudos)" }.orEmpty()
        return Alert(
            id = event.getString("id"), sourceId = "nasa-eonet", hazard = hazard, severity = severity, regionId = regionId,
            postalCodes = emptySet(), headline = "[NASA EONET] ${event.getString("title")}$detail",
            issuedAtMillis = observedAt, expiresAtMillis = now + VALID_FOR_HOURS * HOUR, simulated = false,
        )
    }

    /** Escala de vientos sostenidos: depresión < 34 kt, tormenta 34-63, huracán 64-95, huracán mayor ≥ 96. */
    fun stormSeverity(windKnots: Double?): Severity = when {
        windKnots == null -> Severity.YELLOW
        windKnots < 34 -> Severity.GREEN
        windKnots < 64 -> Severity.YELLOW
        windKnots < 96 -> Severity.ORANGE
        else -> Severity.RED
    }

    /** Punto [lon, lat], o el primer vértice si la geometría es un polígono. */
    private fun firstCoordinate(c: JSONArray?): Pair<Double, Double>? {
        var node: Any? = c
        while (node is JSONArray && node.length() > 0 && node.get(0) is JSONArray) node = node.get(0)
        val arr = node as? JSONArray ?: return null
        return if (arr.length() >= 2) arr.getDouble(0) to arr.getDouble(1) else null
    }
}

/**
 * Fuente REAL: eventos naturales abiertos de NASA EONET (tormentas severas e inundaciones), sin clave.
 * Falla en silencio (lista vacía) si no hay red, para no afectar el funcionamiento offline-first.
 * Los resultados se reutilizan 15 minutos para no consultar la red en cada refresco.
 */
class NasaEonetSource : AlertSource {
    override val id = "nasa-eonet"
    override val displayName = "NASA EONET"

    private var cache: Triple<String, Long, String>? = null // regionId, fetchedAt, json

    override suspend fun fetchActive(regionId: String, nowMillis: Long): List<Alert> {
        val box = REGION_BOXES[regionId] ?: return emptyList()
        val json = cache?.takeIf { it.first == regionId && nowMillis - it.second < CACHE_MILLIS }?.third
            ?: download(box)?.also { cache = Triple(regionId, nowMillis, it) }
            ?: return emptyList()
        return runCatching { EonetParser.parse(json, regionId, nowMillis) }.getOrDefault(emptyList())
    }

    private suspend fun download(box: GeoBox): String? = withContext(Dispatchers.IO) {
        runCatching {
            // bbox de EONET: minLon, maxLat, maxLon, minLat
            val url = URL(
                "https://eonet.gsfc.nasa.gov/api/v3/events?status=open&category=severeStorms,floods" +
                    "&bbox=${box.minLon},${box.maxLat},${box.maxLon},${box.minLat}"
            )
            (url.openConnection() as HttpURLConnection).run {
                connectTimeout = 10_000; readTimeout = 15_000
                try { if (responseCode == 200) inputStream.bufferedReader().readText() else null } finally { disconnect() }
            }
        }.getOrNull()
    }

    private companion object {
        const val CACHE_MILLIS = 15 * 60_000L
    }
}
