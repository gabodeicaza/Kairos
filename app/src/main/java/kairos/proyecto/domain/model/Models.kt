package kairos.proyecto.domain.model

enum class HazardType(val label: String) {
    HURRICANE("Huracán / tormenta tropical"),
    FLOOD("Inundación"),
    EXTREME_HEAT("Calor extremo"),
    WATER_SCARCITY("Escasez hídrica"),
}

/** Semáforo de severidad de la alerta. El orden importa: ordinal creciente = más grave. */
enum class Severity(val label: String) {
    GREEN("Verde"), YELLOW("Amarilla"), ORANGE("Naranja"), RED("Roja")
}

/** Riesgo estructural de una zona frente a una amenaza (catálogo predefinido). */
enum class RiskLevel { LOW, MEDIUM, HIGH }

/** Región piloto parametrizada. Añadir una región = añadir un [Region] y sus zonas al catálogo. */
data class Region(val id: String, val name: String)

object Regions {
    val SOUTHWEST = Region("sw-guerrero", "Suroeste (Acapulco, Guerrero)")
    val NORTHEAST = Region("ne-tamaulipas-nl", "Noreste (Tamaulipas y Monterrey)")
    val PILOT = listOf(SOUTHWEST, NORTHEAST)
}

/** Zona del catálogo, identificada por código postal y colonia. No usa coordenadas. */
data class RiskZone(
    val regionId: String,
    val postalCode: String,
    val colonia: String,
    val risks: Map<HazardType, RiskLevel>,
)

/** Vulnerabilidad del hogar. Contiene solo atributos necesarios para personalizar el checklist. */
data class HouseholdProfile(
    val groundFloor: Boolean = false,
    val hasChildren: Boolean = false,
    val hasElderly: Boolean = false,
    val hasPets: Boolean = false,
)

data class Alert(
    val id: String,
    val sourceId: String,
    val hazard: HazardType,
    val severity: Severity,
    val regionId: String,
    /** Códigos postales afectados; vacío = toda la región. */
    val postalCodes: Set<String>,
    val headline: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    /** true cuando proviene de un adaptador simulado. La UI debe mostrarlo. */
    val simulated: Boolean,
    /** Momento estimado del impacto; null en amenazas prolongadas (calor, escasez de agua). */
    val expectedImpactMillis: Long? = null,
) {
    fun isActive(nowMillis: Long) = nowMillis in issuedAtMillis until expiresAtMillis
    fun affects(postalCode: String) = postalCodes.isEmpty() || postalCode in postalCodes
}

/** Fase temporal del checklist. El orden (ordinal) es el orden cronológico. */
enum class Phase(val label: String) {
    ONGOING("Durante la alerta"),
    EARLY("Preparación"),
    MINUS_12("Fase -12 horas"),
    MINUS_2("Fase -2 horas"),
    POST_IMPACT("Post-impacto"),
}

/** Módulos visuales para reducir la saturación cognitiva: cada paso pertenece a uno. */
enum class StepCategory(val label: String) {
    SAFETY("Seguridad personal"),
    ASSETS("Aseguramiento de bienes"),
    PETS("Planes para mascotas"),
    SHELTER("Alternativas de refugio"),
}

/** Producto de un proveedor afiliado (CPA) asociado a un paso que requiere infraestructura física. */
data class ProductSuggestion(
    val id: String,
    val name: String,
    val provider: String,
    val priceMxn: Double,
    /** Enlace de afiliado SIMULADO: no apunta a un proveedor real. */
    val affiliateUrl: String,
    val simulated: Boolean = true,
)

data class ChecklistStep(
    val id: String,
    val title: String,
    val detail: String,
    /** 1 = más urgente dentro de su fase. */
    val priority: Int,
    /** Tiempo estimado de ejecución, en minutos. */
    val estimatedMinutes: Int,
    /** Cuántas horas antes del impacto conviene completarlo (referencia; la fase es la que desbloquea). */
    val hoursBeforeEvent: Int,
    val category: StepCategory = StepCategory.SAFETY,
    val phase: Phase = Phase.EARLY,
    /** Compra opcional; nunca condiciona la acción de supervivencia. */
    val product: ProductSuggestion? = null,
)

data class Checklist(
    val alertId: String,
    val hazard: HazardType,
    val severity: Severity,
    val zoneRisk: RiskLevel,
    val steps: List<ChecklistStep>,
    val expectedImpactMillis: Long? = null,
    val totalMinutes: Int = steps.sumOf { it.estimatedMinutes },
    val simulated: Boolean,
)
