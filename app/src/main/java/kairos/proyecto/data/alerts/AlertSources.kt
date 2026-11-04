package kairos.proyecto.data.alerts

import kairos.proyecto.domain.model.Alert
import kairos.proyecto.domain.model.HazardType
import kairos.proyecto.domain.model.Regions
import kairos.proyecto.domain.model.Severity

/**
 * Contrato de una fuente oficial (agencia meteorológica, Protección Civil, ...).
 * Los adaptadores reales deben devolver [Alert] con `simulated = false`.
 */
interface AlertSource {
    val id: String
    val displayName: String
    suspend fun fetchActive(regionId: String, nowMillis: Long): List<Alert>
}

private const val HOUR = 3_600_000L

/** Boletín de un escenario simulado: vigente desde [fromHour] (horas tras el inicio del escenario). */
internal class Stage(val fromHour: Double, val severity: Severity, val headline: String)

/**
 * Construye la alerta vigente de un escenario simulado según las horas transcurridas desde su inicio.
 * Cada etapa emite un boletín nuevo (nueva `issuedAt`), de modo que la escalada se comporta como en la vida real.
 */
internal fun scenarioAlert(
    id: String, sourceId: String, hazard: HazardType, regionId: String, postalCodes: Set<String>,
    startMillis: Long, nowMillis: Long, stages: List<Stage>, impactHour: Double?, durationHours: Double,
): Alert? {
    val elapsed = (nowMillis - startMillis).toDouble() / HOUR
    if (elapsed < 0 || elapsed >= durationHours) return null
    val stage = stages.lastOrNull { elapsed >= it.fromHour } ?: return null
    return Alert(
        id = id, sourceId = sourceId, hazard = hazard, severity = stage.severity, regionId = regionId,
        postalCodes = postalCodes, headline = "[SIMULADO] ${stage.headline}",
        issuedAtMillis = startMillis + (stage.fromHour * HOUR).toLong(),
        expiresAtMillis = startMillis + (durationHours * HOUR).toLong(),
        simulated = true,
        expectedImpactMillis = impactHour?.let { startMillis + (it * HOUR).toLong() },
    )
}

/** Escenario 1 (SIMULADO, inspirado en Otis): tormenta tropical a categoría 5 en 11 h; impacto a las 12 h. */
internal val OTIS_STAGES = listOf(
    Stage(0.0, Severity.YELLOW, "Tormenta tropical Otis frente a Acapulco; se pronostica intensificación"),
    Stage(3.0, Severity.ORANGE, "Otis se intensifica a huracán categoría 1"),
    Stage(6.0, Severity.RED, "Otis alcanza categoría 3: se recomienda evacuar zonas bajas"),
    Stage(9.0, Severity.RED, "Otis categoría 4: Protección Civil ordena evacuar la costa"),
    Stage(11.0, Severity.RED, "Otis alcanza categoría 5: impacto inminente"),
)
internal const val OTIS_IMPACT_HOUR = 12.0
internal const val OTIS_DURATION_HOURS = 12.0 + 48.0

/** SIMULADO: Protección Civil de Acapulco/Guerrero. Sustituir por la API/boletines oficiales. */
class SimulatedCivilProtectionGuerreroSource(private val scenarioStart: () -> Long) : AlertSource {
    override val id = "sim-pc-guerrero"
    override val displayName = "Protección Civil Guerrero (SIMULADO)"

    override suspend fun fetchActive(regionId: String, nowMillis: Long): List<Alert> =
        if (regionId != Regions.SOUTHWEST.id) emptyList() else listOfNotNull(
            scenarioAlert(
                "sim-otis", id, HazardType.HURRICANE, regionId, emptySet(), scenarioStart(), nowMillis,
                OTIS_STAGES, OTIS_IMPACT_HOUR, OTIS_DURATION_HOURS,
            ),
        )
}

private val MONTERREY_ZIPS = setOf("64000", "64720")

/** Escenario 2 (SIMULADO): calor extremo y escasez hídrica prolongados en Monterrey. */
internal val MTY_HEAT_STAGES = listOf(
    Stage(0.0, Severity.ORANGE, "Calor extremo prolongado: máximas de 43 °C durante 10 días"),
    Stage(72.0, Severity.RED, "Calor extremo, día 4: temperaturas récord y riesgo de apagones"),
)
internal val MTY_WATER_STAGES = listOf(
    Stage(0.0, Severity.YELLOW, "Presas por debajo del 25 %: recortes de suministro en Monterrey"),
    Stage(96.0, Severity.ORANGE, "Escasez hídrica severa: tandeos de 12 horas"),
    Stage(168.0, Severity.RED, "Emergencia hídrica: abasto solo por pipas"),
)

/** SIMULADO: Servicio Meteorológico Nacional (SMN/CONAGUA). Sustituir por el servicio web del SMN. */
class SimulatedSmnSource(private val scenarioStart: () -> Long) : AlertSource {
    override val id = "sim-smn"
    override val displayName = "SMN/CONAGUA (SIMULADO)"

    override suspend fun fetchActive(regionId: String, nowMillis: Long): List<Alert> {
        if (regionId != Regions.NORTHEAST.id) return emptyList()
        val start = scenarioStart()
        return listOfNotNull(
            scenarioAlert("sim-mty-heat", id, HazardType.EXTREME_HEAT, regionId, MONTERREY_ZIPS, start, nowMillis,
                MTY_HEAT_STAGES, impactHour = null, durationHours = 10 * 24.0),
            scenarioAlert("sim-mty-water", id, HazardType.WATER_SCARCITY, regionId, MONTERREY_ZIPS, start, nowMillis,
                MTY_WATER_STAGES, impactHour = null, durationHours = 14 * 24.0),
            scenarioAlert("sim-tamps-flood", id, HazardType.FLOOD, regionId, setOf("89000", "64720"), start, nowMillis,
                listOf(Stage(0.0, Severity.YELLOW, "Pronóstico de lluvias fuertes en Tampico y Monterrey")),
                impactHour = 10.0, durationHours = 34.0),
        )
    }
}
