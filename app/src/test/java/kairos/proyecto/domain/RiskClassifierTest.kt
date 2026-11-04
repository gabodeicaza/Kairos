package kairos.proyecto.domain

import kairos.proyecto.data.catalog.StaticZoneCatalog
import kairos.proyecto.domain.business.CommissionCalculator
import kairos.proyecto.domain.model.HazardType
import kairos.proyecto.domain.model.RiskLevel
import kairos.proyecto.domain.risk.RiskClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RiskClassifierTest {

    private val classifier = RiskClassifier(StaticZoneCatalog())

    @Test
    fun matchesColoniaWithinPostalCode() {
        assertEquals("La Laja", classifier.findZone("39300", "la laja")?.colonia)
    }

    @Test
    fun unknownColonia_usesMostSevereZoneOfPostalCode() {
        assertEquals("La Laja", classifier.findZone("39300", "Otra")?.colonia)
    }

    @Test
    fun unknownPostalCode_returnsNullAndMediumRisk() {
        assertNull(classifier.findZone("00000"))
        assertEquals(RiskLevel.MEDIUM, classifier.classify(null, HazardType.FLOOD))
    }

    @Test
    fun commission_isRateTimesAnnualPremium() {
        assertEquals(12.0, CommissionCalculator(0.1).commissionFor(10.0), 1e-9)
    }
}
