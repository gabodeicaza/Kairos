package kairos.proyecto.domain.business

import kairos.proyecto.domain.decision.RuleBasedChecklistEngine
import kairos.proyecto.domain.model.Alert
import kairos.proyecto.domain.model.HazardType
import kairos.proyecto.domain.model.RiskLevel
import kairos.proyecto.domain.model.Severity
import kairos.proyecto.domain.telemetry.BehaviorIndicator

// ---------- Actores y contrato de datos compartido con el panel web B2B/B2G ----------

enum class PanelActor { CIVIL_PROTECTION, INSURER }

/** Alerta creada por Protección Civil/gobierno desde el panel y distribuida a usuarios de su zona. */
data class PanelAlertDraft(
    val actor: PanelActor,
    val organizationId: String,
    val hazard: HazardType,
    val severity: Severity,
    val regionId: String,
    val postalCodes: Set<String>,
    val headline: String,
    val validHours: Int,
)

/** Indicador agregado y anonimizado. Nunca contiene identificadores ni domicilio individual. */
data class AnonymizedIndicator(
    val regionId: String,
    val postalCode: String,
    val households: Int,
    val groundFloorShare: Double,
    val withChildrenShare: Double,
    val withElderlyShare: Double,
    val withPetsShare: Double,
)

/**
 * Contrato entre la app, el backend y el panel web. Sin implementación en este esqueleto:
 * el servidor y el panel quedan fuera de alcance.
 */
interface PanelGateway {
    suspend fun publishAlert(draft: PanelAlertDraft): Alert
    suspend fun dashboard(actor: PanelActor, regionId: String): List<AnonymizedIndicator>

    /** Data-as-a-Service: inteligencia de comportamiento agregada a partir de los BehaviorReport anonimizados. */
    suspend fun behaviorIndicators(actor: PanelActor, regionId: String): List<BehaviorIndicator>
}

// ---------- Suscripciones y microseguro ----------

enum class SubscriptionPlan(val monthlyPriceMxn: Int) {
    /** Tablero anonimizado básico para gobierno municipal. Precios por definir (simulados). */
    GOV_BASIC(0),
    /** Tablero de inteligencia de comportamiento para aseguradoras / gobierno estatal. */
    DASHBOARD_PRO(0),
}

data class Subscription(
    val plan: SubscriptionPlan,
    val actor: PanelActor,
    val organizationId: String,
    val active: Boolean,
)

/** Microseguro mostrado SEPARADO de los checklists. Producto y precios simulados. */
data class MicroinsuranceOffer(
    val productName: String,
    val monthlyPremiumMxn: Double,
    val coverageMxn: Double,
    val simulated: Boolean = true,
)

class MicroinsuranceAdvisor {
    /** ¿El escenario es de bajo riesgo? Si no, nunca se ofrece microseguro, aunque se complete el checklist. */
    fun scenarioQualifies(zoneRisk: RiskLevel, severity: Severity) =
        RuleBasedChecklistEngine.effectiveSeverity(severity, zoneRisk) < Severity.ORANGE

    /**
     * Ofrece microseguro solo si el hogar queda en un escenario de bajo riesgo (severidad efectiva < naranja)
     * y la persona completó su checklist, conforme al modelo de comisión; la prima sube con la exposición de la zona.
     */
    fun offerFor(zoneRisk: RiskLevel, severity: Severity, checklistCompleted: Boolean): MicroinsuranceOffer? {
        if (!checklistCompleted || !scenarioQualifies(zoneRisk, severity)) return null
        val premium = when (zoneRisk) {
            RiskLevel.LOW -> 59.0
            RiskLevel.MEDIUM -> 89.0
            RiskLevel.HIGH -> 129.0
        }
        return MicroinsuranceOffer("Microseguro de hogar (simulado)", premium, coverageMxn = 25_000.0)
    }
}

/** Comisión por microseguros vendidos; la tasa es un parámetro del acuerdo con la aseguradora. */
class CommissionCalculator(private val commissionRate: Double = 0.12) {
    fun commissionFor(monthlyPremiumMxn: Double, months: Int = 12): Double =
        monthlyPremiumMxn * months * commissionRate
}

/** Comisión de afiliados (CPA) por compras de productos del marketplace preventivo; tasa simulada. */
class AffiliateCommissionCalculator(private val commissionRate: Double = 0.08) {
    fun commissionFor(priceMxn: Double): Double = priceMxn * commissionRate
}
