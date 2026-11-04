package kairos.proyecto.domain.decision

import kairos.proyecto.domain.model.Alert
import kairos.proyecto.domain.model.Checklist
import kairos.proyecto.domain.model.ChecklistStep
import kairos.proyecto.domain.model.HazardType
import kairos.proyecto.domain.model.HouseholdProfile
import kairos.proyecto.domain.model.Phase
import kairos.proyecto.domain.model.ProductSuggestion
import kairos.proyecto.domain.model.RiskLevel
import kairos.proyecto.domain.model.Severity
import kairos.proyecto.domain.model.StepCategory

/**
 * Punto de integración con el backend: en producción el motor corre en el servidor y la app solo muestra
 * el resultado. La implementación remota (pendiente) debe cumplir este mismo contrato.
 * Hoy se enlaza [RuleBasedChecklistEngine] como respaldo local/offline.
 */
interface ChecklistGenerator {
    fun generate(profile: HouseholdProfile, alert: Alert, zoneRisk: RiskLevel): Checklist
}

/**
 * Árbol de decisión temporal: pasos por amenaza y severidad + pasos por vulnerabilidad, asignados a una [Phase].
 * - Una zona de riesgo ALTO eleva un nivel la severidad efectiva (tope ROJA) para decidir qué pasos incluir.
 * - Con impacto estimado, la fase sale de las horas previas del paso; sin impacto (calor, escasez) todo es ONGOING.
 */
class RuleBasedChecklistEngine : ChecklistGenerator {

    private class Rule(
        val hazards: Set<HazardType>,
        val minSeverity: Severity,
        val appliesTo: (HouseholdProfile) -> Boolean = { true },
        val postImpact: Boolean = false,
        val step: ChecklistStep,
    )

    override fun generate(profile: HouseholdProfile, alert: Alert, zoneRisk: RiskLevel): Checklist {
        val effective = effectiveSeverity(alert.severity, zoneRisk)
        val hasImpact = alert.expectedImpactMillis != null
        val steps = rules
            .filter {
                alert.hazard in it.hazards && effective >= it.minSeverity && it.appliesTo(profile) &&
                    (!it.postImpact || hasImpact)
            }
            .map { it.step.copy(phase = phaseOf(it, hasImpact)) }
            .distinctBy { it.id }
            .sortedWith(compareBy<ChecklistStep> { it.phase.ordinal }.thenBy { it.priority }.thenByDescending { it.hoursBeforeEvent })
        return Checklist(
            alertId = alert.id,
            hazard = alert.hazard,
            severity = alert.severity,
            zoneRisk = zoneRisk,
            steps = steps,
            expectedImpactMillis = alert.expectedImpactMillis,
            simulated = alert.simulated,
        )
    }

    private fun phaseOf(rule: Rule, hasImpact: Boolean): Phase = when {
        !hasImpact -> Phase.ONGOING
        rule.postImpact -> Phase.POST_IMPACT
        rule.step.hoursBeforeEvent > 12 -> Phase.EARLY
        rule.step.hoursBeforeEvent > 2 -> Phase.MINUS_12
        else -> Phase.MINUS_2
    }

    companion object {
        fun effectiveSeverity(severity: Severity, zoneRisk: RiskLevel): Severity =
            if (zoneRisk == RiskLevel.HIGH) Severity.entries[minOf(severity.ordinal + 1, Severity.RED.ordinal)] else severity

        private val water = setOf(HazardType.HURRICANE, HazardType.FLOOD)
        private val storm = setOf(HazardType.HURRICANE)
        private val flood = setOf(HazardType.FLOOD)
        private val heat = setOf(HazardType.EXTREME_HEAT)
        private val scarcity = setOf(HazardType.WATER_SCARCITY)
        private val everything = HazardType.entries.toSet()
        private val notScarcity = everything - scarcity

        // Catálogo de afiliados SIMULADO (CPA). Los enlaces no apuntan a proveedores reales.
        private fun product(id: String, name: String, provider: String, price: Double) =
            ProductSuggestion(id, name, provider, price, "https://proveedores-simulados.example/cpa/$id?aff=airos")

        private val emergencyKit = product("kit-72h", "Kit de emergencia familiar 72 h", "Proveedor simulado A", 1250.0)
        private val shutters = product("tormenteras", "Tormenteras para ventanas (juego)", "Proveedor simulado B", 2300.0)
        private val floodBarrier = product("barrera-inundacion", "Barrera anti-inundación para puerta", "Proveedor simulado C", 1890.0)
        private val waterTank = product("bidones-agua", "Bidones de agua de 20 L (x4) con filtro", "Proveedor simulado D", 980.0)

        private fun step(
            id: String, title: String, detail: String, priority: Int, minutes: Int, hoursBefore: Int,
            category: StepCategory = StepCategory.SAFETY, product: ProductSuggestion? = null,
        ) = ChecklistStep(id, title, detail, priority, minutes, hoursBefore, category, product = product)

        private val S = StepCategory.SAFETY
        private val A = StepCategory.ASSETS
        private val P = StepCategory.PETS
        private val H = StepCategory.SHELTER

        private val rules: List<Rule> = listOf(
            // ---------- Base: huracán, inundación, calor ----------
            Rule(notScarcity, Severity.GREEN, step = step("kit", "Revisa tu kit de emergencia",
                "Agua (3 L por persona al día), alimentos no perecederos, linterna, botiquín y radio de pilas.", 5, 30, 72, S, emergencyKit)),
            Rule(notScarcity, Severity.YELLOW, step = step("charge", "Carga celulares y baterías externas",
                "Deja los equipos al 100 % y baja el brillo de pantalla.", 4, 15, 24, S)),
            Rule(storm, Severity.YELLOW, step = step("secure-outdoor", "Asegura objetos en patio y azotea",
                "Tinacos, macetas, láminas y muebles ligeros pueden volverse proyectiles.", 4, 30, 24, A)),
            Rule(storm, Severity.YELLOW, step = step("shutters", "Protege ventanas y puertas",
                "Cubre los vidrios con tormenteras o madera y cierra bien puertas.", 3, 60, 24, A, shutters)),
            Rule(flood, Severity.YELLOW, step = step("flood-route", "Ubica tu ruta a zona alta",
                "Evita cauces, arroyos y calles que se anegan.", 4, 20, 24, H)),
            Rule(water, Severity.ORANGE, step = step("docs", "Guarda documentos en bolsa impermeable",
                "Identificaciones, escrituras, póliza de seguro y recetas.", 3, 15, 12, A)),
            Rule(water, Severity.ORANGE, step = step("route", "Confirma tu ruta al refugio oficial",
                "Identifica el refugio temporal más cercano y una ruta alterna hacia zona alta.", 3, 20, 12, H)),
            Rule(water, Severity.ORANGE, step = step("alt-shelter", "Elige un refugio alterno",
                "Casa de un familiar o vecino en zona alta, por si el refugio oficial se llena.", 3, 15, 12, H)),
            Rule(water, Severity.RED, step = step("utilities", "Corta gas y electricidad",
                "Hazlo antes de salir o si el agua empieza a entrar a la vivienda.", 2, 10, 3, S)),
            Rule(water, Severity.RED, step = step("evacuate", "Evacúa al refugio indicado por Protección Civil",
                "Sal con tu mochila de emergencia y sigue las rutas oficiales.", 1, 60, 6, H)),
            Rule(storm, Severity.RED, step = step("shelter-in-place", "Si no evacuaste, resguárdate lejos de ventanas",
                "Ve al cuarto interior más resistente, con tu mochila y agua. No salgas durante el ojo del huracán.", 1, 10, 2, H)),
            Rule(flood, Severity.RED, step = step("flood-high", "Sube al nivel más alto y no cruces corrientes",
                "Treinta centímetros de agua en movimiento pueden arrastrarte.", 1, 10, 2, S)),

            Rule(heat, Severity.YELLOW, step = step("hydrate", "Aumenta hidratación y evita el sol",
                "Evita actividad al aire libre de 11:00 a 17:00.", 3, 10, 0, S)),
            Rule(heat, Severity.ORANGE, step = step("cool-room", "Prepara un espacio fresco en casa",
                "Cierra cortinas de día y ventila de noche.", 2, 30, 0, S)),
            Rule(heat, Severity.RED, step = step("heat-center", "Acude a un centro de enfriamiento o a un lugar con clima",
                "Si tu casa supera 32 °C en el interior, pasa las horas de más calor fuera de ella.", 1, 45, 0, H)),

            // ---------- Base: escasez hídrica ----------
            Rule(scarcity, Severity.YELLOW, step = step("ws-store", "Almacena agua: 3 L por persona al día",
                "Apunta a una reserva para 7 días en recipientes limpios y tapados.", 2, 30, 0, A, waterTank)),
            Rule(scarcity, Severity.YELLOW, step = step("ws-reduce", "Reduce el consumo del hogar",
                "Regadera de 4 minutos, reutiliza agua de lavado y revisa fugas.", 4, 15, 0, S)),
            Rule(scarcity, Severity.ORANGE, step = step("ws-supply", "Ubica pipas y puntos de abasto",
                "Anota los horarios de tandeo y los puntos de reparto cercanos.", 2, 20, 0, H)),
            Rule(scarcity, Severity.RED, step = step("ws-priority", "Prioriza agua para beber y cocinar",
                "Suspende lavado de autos, riego y limpieza con manguera.", 1, 10, 0, S)),

            // ---------- Vulnerabilidad: planta baja ----------
            Rule(water, Severity.YELLOW, { it.groundFloor }, step = step("ground-lift", "Sube enseres y documentos a un nivel alto",
                "Tu vivienda en planta baja es la primera en inundarse: eleva electrodomésticos, colchones y papeles.", 2, 45, 18, A)),
            Rule(water, Severity.ORANGE, { it.groundFloor }, step = step("flood-barrier", "Instala barreras anti-inundación",
                "Coloca barreras en puertas y rejillas para frenar la entrada de agua.", 2, 60, 18, A, floodBarrier)),
            Rule(water, Severity.ORANGE, { it.groundFloor }, step = step("ground-relocate", "Planea salir de la planta baja",
                "Acuerda con un vecino de piso alto o ve al refugio antes de que suba el nivel del agua.", 1, 30, 12, H)),

            // ---------- Vulnerabilidad: niñas y niños ----------
            Rule(water, Severity.YELLOW, { it.hasChildren }, step = step("kids-bag", "Prepara la mochila de las niñas y niños",
                "Agua, comida, ropa seca, medicinas, copia de acta y una cobija o juguete.", 3, 20, 24, S)),
            Rule(water, Severity.ORANGE, { it.hasChildren }, step = step("kids-meeting", "Define un punto de reunión familiar",
                "Que todos lo conozcan; avisa a la escuela y a un familiar fuera de la zona.", 2, 15, 12, S)),
            Rule(storm, Severity.RED, { it.hasChildren }, step = step("kids-calm", "Mantén a las niñas y niños contigo",
                "Quédense juntos en el cuarto seguro; usa su juguete y su mochila para mantener la calma.", 2, 5, 2, S)),
            Rule(heat, Severity.ORANGE, { it.hasChildren }, step = step("heat-kids", "Hidrata a las niñas y niños cada hora",
                "Nunca los dejes solos en un vehículo y evita juegos al sol directo.", 1, 10, 0, S)),

            // ---------- Vulnerabilidad: personas adultas mayores ----------
            Rule(everything, Severity.YELLOW, { it.hasElderly }, step = step("elder-meds", "Reúne medicinas para al menos 72 horas",
                "Incluye recetas, equipo médico cargado y lentes.", 2, 25, 36, S)),
            Rule(water, Severity.ORANGE, { it.hasElderly }, step = step("elder-transfer", "Organiza su traslado con anticipación",
                "Requiere más tiempo: pide ayuda a vecinos o a Protección Civil y sal antes de la alerta roja.", 1, 40, 18, H)),
            Rule(heat, Severity.ORANGE, { it.hasElderly }, step = step("heat-elder", "Revísalas cada 2 horas por golpe de calor",
                "Mareo, confusión o piel seca y caliente: llévalas a la sombra y pide ayuda médica.", 1, 10, 0, S)),
            Rule(scarcity, Severity.ORANGE, { it.hasElderly || it.hasChildren }, step = step("ws-vulnerable", "Reserva primero el agua para niñez y adultos mayores",
                "Aparta su agua de beber antes de cualquier otro uso.", 2, 10, 0, S)),

            // ---------- Vulnerabilidad: mascotas ----------
            Rule(water, Severity.YELLOW, { it.hasPets }, step = step("pets-kit", "Prepara el kit de tu mascota",
                "Alimento y agua para 3 días, correa, transportadora, cartilla y una foto reciente.", 3, 20, 24, P)),
            Rule(water, Severity.ORANGE, { it.hasPets }, step = step("pets-shelter", "Ubica refugios que admitan mascotas",
                "Muchos refugios no las aceptan: confirma uno o un familiar que pueda recibirla.", 2, 20, 12, P)),
            Rule(water, Severity.RED, { it.hasPets }, step = step("pets-carrier", "Ten a tu mascota en su transportadora, contigo",
                "Con collar e identificación, lista para salir si evacúas.", 2, 5, 2, P)),
            Rule(heat, Severity.YELLOW, { it.hasPets }, step = step("heat-pets", "Protege a tu mascota del calor",
                "Agua fresca siempre, sombra y paseos solo temprano o de noche.", 3, 10, 0, P)),
            Rule(scarcity, Severity.YELLOW, { it.hasPets }, step = step("ws-pets", "Aparta agua para tu mascota",
                "Calcula su consumo diario dentro de tu reserva.", 3, 5, 0, P)),

            // ---------- Post-impacto (solo con impacto estimado) ----------
            Rule(water, Severity.YELLOW, postImpact = true, step = step("post-safe", "Avisa a tu familia que estás bien",
                "Mensajes cortos; las llamadas pueden saturar la red.", 1, 5, 0, S)),
            Rule(water, Severity.YELLOW, postImpact = true, step = step("post-hazards", "Evita agua estancada y cables caídos",
                "No camines ni manejes por zonas inundadas ni toques instalaciones eléctricas mojadas.", 1, 5, 0, S)),
            Rule(water, Severity.ORANGE, postImpact = true, step = step("post-water", "Hierve o clora el agua antes de beber",
                "Hierve el agua 1 minuto o usa gotas de cloro apto para consumo.", 2, 15, 0, S)),
            Rule(water, Severity.YELLOW, postImpact = true, step = step("post-damage", "Documenta daños con fotos y video",
                "Útil para tu seguro y para solicitar apoyos antes de limpiar.", 3, 30, 0, A)),
            Rule(water, Severity.YELLOW, { it.hasElderly }, postImpact = true, step = step("post-elder", "Revisa su salud y medicinas",
                "Confirma que tengan sus medicinas y que no haya signos de deshidratación.", 2, 15, 0, S)),
            Rule(water, Severity.YELLOW, { it.hasPets }, postImpact = true, step = step("post-pets", "Revisa a tu mascota",
                "Agua limpia, alimento y atención veterinaria si hay heridas o decaimiento.", 3, 15, 0, P)),
        )
    }
}
