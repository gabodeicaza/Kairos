package kairos.proyecto.domain.risk

import kairos.proyecto.domain.model.HazardType
import kairos.proyecto.domain.model.RiskLevel
import kairos.proyecto.domain.model.RiskZone

/** Catálogo predefinido de zonas. La implementación actual vive en data/catalog. */
interface ZoneCatalog {
    fun zones(regionId: String? = null): List<RiskZone>
}

/**
 * Clasifica el riesgo en el dispositivo usando solo código postal / colonia.
 * Si no hay coincidencia exacta de colonia, usa la zona más grave del código postal (criterio conservador).
 */
class RiskClassifier(private val catalog: ZoneCatalog) {

    fun findZone(postalCode: String, colonia: String? = null): RiskZone? {
        val byPostal = catalog.zones().filter { it.postalCode == postalCode }
        if (byPostal.isEmpty()) return null
        return byPostal.firstOrNull { colonia != null && it.colonia.equals(colonia, ignoreCase = true) }
            ?: byPostal.maxByOrNull { zone -> zone.risks.values.maxOfOrNull { it.ordinal } ?: -1 }
    }

    fun classify(zone: RiskZone?, hazard: HazardType): RiskLevel =
        zone?.risks?.get(hazard) ?: RiskLevel.MEDIUM // sin dato: supuesto prudente
}
