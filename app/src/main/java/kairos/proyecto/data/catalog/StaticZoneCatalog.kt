package kairos.proyecto.data.catalog

import kairos.proyecto.domain.model.HazardType.EXTREME_HEAT
import kairos.proyecto.domain.model.HazardType.FLOOD
import kairos.proyecto.domain.model.HazardType.HURRICANE
import kairos.proyecto.domain.model.HazardType.WATER_SCARCITY
import kairos.proyecto.domain.model.Regions
import kairos.proyecto.domain.model.RiskLevel.HIGH
import kairos.proyecto.domain.model.RiskLevel.LOW
import kairos.proyecto.domain.model.RiskLevel.MEDIUM
import kairos.proyecto.domain.model.RiskZone
import kairos.proyecto.domain.risk.ZoneCatalog

/**
 * Catálogo de EJEMPLO para el piloto. Los códigos postales, colonias y niveles son ilustrativos:
 * deben sustituirse por el catálogo oficial de Protección Civil. Para añadir una región, agregar sus zonas aquí
 * (o cargarlas desde un archivo/endpoint) y registrar la [kairos.proyecto.domain.model.Region].
 */
class StaticZoneCatalog : ZoneCatalog {

    private val all = listOf(
        // Suroeste: Acapulco, Guerrero
        RiskZone(Regions.SOUTHWEST.id, "39300", "Centro", mapOf(HURRICANE to MEDIUM, FLOOD to MEDIUM, EXTREME_HEAT to MEDIUM)),
        RiskZone(Regions.SOUTHWEST.id, "39300", "La Laja", mapOf(HURRICANE to HIGH, FLOOD to HIGH, EXTREME_HEAT to MEDIUM)),
        RiskZone(Regions.SOUTHWEST.id, "39850", "Zona Diamante", mapOf(HURRICANE to MEDIUM, FLOOD to LOW, EXTREME_HEAT to MEDIUM)),
        RiskZone(Regions.SOUTHWEST.id, "39906", "Zona costera", mapOf(HURRICANE to HIGH, FLOOD to HIGH, EXTREME_HEAT to MEDIUM)),
        // Noreste: Tamaulipas y Monterrey
        RiskZone(Regions.NORTHEAST.id, "89000", "Tampico Centro", mapOf(HURRICANE to MEDIUM, FLOOD to HIGH, EXTREME_HEAT to MEDIUM)),
        RiskZone(Regions.NORTHEAST.id, "88500", "Reynosa Centro", mapOf(HURRICANE to LOW, FLOOD to MEDIUM, EXTREME_HEAT to HIGH)),
        RiskZone(Regions.NORTHEAST.id, "64000", "Monterrey Centro", mapOf(HURRICANE to LOW, FLOOD to MEDIUM, EXTREME_HEAT to HIGH, WATER_SCARCITY to HIGH)),
        RiskZone(Regions.NORTHEAST.id, "64720", "Margen del río Santa Catarina", mapOf(HURRICANE to LOW, FLOOD to HIGH, EXTREME_HEAT to HIGH, WATER_SCARCITY to MEDIUM)),
    )

    override fun zones(regionId: String?): List<RiskZone> =
        if (regionId == null) all else all.filter { it.regionId == regionId }
}
