package kairos.proyecto.domain

import kairos.proyecto.data.alerts.OTIS_DURATION_HOURS
import kairos.proyecto.data.alerts.OTIS_IMPACT_HOUR
import kairos.proyecto.data.alerts.OTIS_STAGES
import kairos.proyecto.data.alerts.MTY_HEAT_STAGES
import kairos.proyecto.data.alerts.MTY_WATER_STAGES
import kairos.proyecto.data.alerts.SimulatedCivilProtectionGuerreroSource
import kairos.proyecto.data.alerts.SimulatedSmnSource
import kairos.proyecto.domain.business.AffiliateCommissionCalculator
import kairos.proyecto.domain.decision.RuleBasedChecklistEngine
import kairos.proyecto.domain.decision.activePhase
import kairos.proyecto.domain.decision.ignoredCount
import kairos.proyecto.domain.decision.isUnlocked
import kairos.proyecto.domain.decision.nextAction
import kairos.proyecto.domain.decision.phases
import kairos.proyecto.domain.decision.stepKey
import kairos.proyecto.domain.model.Alert
import kairos.proyecto.domain.model.HazardType
import kairos.proyecto.domain.model.HouseholdProfile
import kairos.proyecto.domain.model.Phase
import kairos.proyecto.domain.model.Regions
import kairos.proyecto.domain.model.RiskLevel
import kairos.proyecto.domain.model.Severity
import kairos.proyecto.domain.model.StepCategory
import kairos.proyecto.domain.telemetry.BehaviorAnonymizer
import kairos.proyecto.domain.telemetry.InteractionEvent
import kairos.proyecto.domain.telemetry.InteractionType
import kairos.proyecto.domain.telemetry.ResponseBucket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Escenarios extremos simulados: huracán tipo Otis en Acapulco y calor/escasez hídrica en Monterrey. */
class ScenarioTest {

    private val engine = RuleBasedChecklistEngine()
    private val hour = 3_600_000L
    private val start = 1_000_000_000L
    private val everyone = HouseholdProfile(groundFloor = true, hasChildren = true, hasElderly = true, hasPets = true)

    private fun otisAt(hours: Double): Alert? = runBlocking {
        SimulatedCivilProtectionGuerreroSource { start }.fetchActive(Regions.SOUTHWEST.id, start + (hours * hour).toLong()).firstOrNull()
    }

    private fun mtyAt(hours: Double): List<Alert> = runBlocking {
        SimulatedSmnSource { start }.fetchActive(Regions.NORTHEAST.id, start + (hours * hour).toLong())
    }

    // ---------- Otis ----------

    @Test
    fun otis_escalatesFromTropicalStormToCategory5WithinTwelveHours() {
        assertEquals(Severity.YELLOW, otisAt(0.0)!!.severity)
        assertEquals(Severity.RED, otisAt(11.5)!!.severity)
        assertTrue(otisAt(11.0)!!.headline.contains("categoría 5"))
        assertTrue(OTIS_STAGES.last().fromHour < 12.0 && OTIS_IMPACT_HOUR <= 12.0)
        assertTrue(otisAt(0.0)!!.simulated)
    }

    @Test
    fun otis_isActiveThroughPostImpactAndThenExpires() {
        assertNotNull(otisAt(OTIS_IMPACT_HOUR + 10))
        assertNull(otisAt(OTIS_DURATION_HOURS + 1))
        assertNull(otisAt(-1.0))
    }

    @Test
    fun otis_checklistGrowsWithEscalationAndKeepsPhases() {
        val tropical = engine.generate(everyone, otisAt(0.0)!!, RiskLevel.HIGH)
        val cat5 = engine.generate(everyone, otisAt(11.5)!!, RiskLevel.HIGH)
        assertTrue(cat5.steps.map { it.id }.containsAll(tropical.steps.map { it.id }))
        assertTrue(cat5.steps.any { it.id == "evacuate" } && cat5.steps.any { it.id == "shelter-in-place" })
        assertEquals(setOf(Phase.EARLY, Phase.MINUS_12, Phase.MINUS_2, Phase.POST_IMPACT), cat5.phases().toSet())
    }

    @Test
    fun otis_phasesUnlockInOrderAsImpactApproaches() {
        val alert = otisAt(0.0)!!
        val c = engine.generate(everyone, alert, RiskLevel.MEDIUM)
        val impact = c.expectedImpactMillis
        assertNotNull(impact)
        fun unlocked(p: Phase, hoursFromStart: Double) = p.isUnlocked(impact, start + (hoursFromStart * hour).toLong())
        assertTrue(unlocked(Phase.MINUS_12, 0.0))     // el impacto es en 12 h: ya aplica la fase -12
        assertFalse(unlocked(Phase.MINUS_2, 9.0))
        assertTrue(unlocked(Phase.MINUS_2, 10.0))
        assertFalse(unlocked(Phase.POST_IMPACT, 11.9))
        assertTrue(unlocked(Phase.POST_IMPACT, 12.0))
    }

    @Test
    fun otis_nextActionFollowsTheActivePhase() {
        val c = engine.generate(everyone, otisAt(11.0)!!, RiskLevel.HIGH)
        val now = start + (11 * hour)
        assertEquals(Phase.MINUS_2, c.activePhase(now))
        assertEquals(Phase.MINUS_2, c.nextAction(emptySet(), now)!!.phase)
        // Antes del -2 h, la acción es de la fase -12 h.
        assertEquals(Phase.MINUS_12, c.nextAction(emptySet(), start + (3 * hour))!!.phase)
    }

    @Test
    fun otis_postImpactStepsAreLockedUntilImpact() {
        val c = engine.generate(everyone, otisAt(0.0)!!, RiskLevel.MEDIUM)
        val beforeImpact = c.nextAction(emptySet(), start + (11 * hour))
        assertTrue(beforeImpact!!.phase != Phase.POST_IMPACT)
        val post = c.steps.filter { it.phase == Phase.POST_IMPACT }.map { it.id }
        assertTrue(post.containsAll(listOf("post-safe", "post-damage", "post-elder", "post-pets")))
    }

    // ---------- Monterrey ----------

    @Test
    fun monterrey_heatAndWaterScarcityArePersistentAndEscalate() {
        val early = mtyAt(1.0).associateBy { it.hazard }
        assertEquals(Severity.ORANGE, early[HazardType.EXTREME_HEAT]!!.severity)
        assertEquals(Severity.YELLOW, early[HazardType.WATER_SCARCITY]!!.severity)
        assertNull(early[HazardType.EXTREME_HEAT]!!.expectedImpactMillis)
        val late = mtyAt(200.0).associateBy { it.hazard }
        assertEquals(Severity.RED, late[HazardType.EXTREME_HEAT]!!.severity)
        assertEquals(Severity.RED, late[HazardType.WATER_SCARCITY]!!.severity)
        assertTrue(MTY_HEAT_STAGES.size >= 2 && MTY_WATER_STAGES.size >= 3)
        assertTrue(mtyAt(24 * 11.0).none { it.hazard == HazardType.EXTREME_HEAT }) // el calor ya terminó
    }

    @Test
    fun monterrey_prolongedAlertsHaveOnlyOngoingPhase() {
        mtyAt(100.0).filter { it.expectedImpactMillis == null }.forEach { alert ->
            val c = engine.generate(everyone, alert, RiskLevel.HIGH)
            assertTrue(c.steps.isNotEmpty())
            assertTrue(c.steps.all { it.phase == Phase.ONGOING })
            assertNotNull(c.nextAction(emptySet(), start))
        }
    }

    @Test
    fun heat_vulnerableMembersGetSpecificSteps() {
        val heat = mtyAt(1.0).first { it.hazard == HazardType.EXTREME_HEAT }
        val ids = engine.generate(everyone, heat, RiskLevel.MEDIUM).steps.map { it.id }
        assertTrue(ids.containsAll(listOf("heat-elder", "heat-kids", "heat-pets", "hydrate")))
        assertFalse("evacuate" in ids)
    }

    @Test
    fun waterScarcity_includesStoragePetsAndPriorityRules() {
        val ws = mtyAt(200.0).first { it.hazard == HazardType.WATER_SCARCITY }
        val ids = engine.generate(everyone, ws, RiskLevel.MEDIUM).steps.map { it.id }
        assertTrue(ids.containsAll(listOf("ws-store", "ws-pets", "ws-priority", "ws-vulnerable")))
    }

    // ---------- Mascotas, módulos y marketplace ----------

    @Test
    fun pets_addPetStepsInPetsCategory() {
        val c = engine.generate(HouseholdProfile(hasPets = true), otisAt(11.5)!!, RiskLevel.MEDIUM)
        val pets = c.steps.filter { it.category == StepCategory.PETS }.map { it.id }
        assertTrue(pets.containsAll(listOf("pets-kit", "pets-shelter", "pets-carrier", "post-pets")))
        assertTrue(engine.generate(HouseholdProfile(), otisAt(11.5)!!, RiskLevel.MEDIUM).steps.none { it.category == StepCategory.PETS })
    }

    @Test
    fun checklistsExposeAssetsAndShelterModules() {
        val cats = engine.generate(everyone, otisAt(11.5)!!, RiskLevel.MEDIUM).steps.map { it.category }.toSet()
        assertTrue(cats.containsAll(listOf(StepCategory.ASSETS, StepCategory.PETS, StepCategory.SHELTER)))
    }

    @Test
    fun physicalInfrastructureStepsCarryAnOptionalSimulatedProduct() {
        val c = engine.generate(HouseholdProfile(groundFloor = true), otisAt(5.0)!!, RiskLevel.MEDIUM)
        val barrier = c.steps.first { it.id == "flood-barrier" }
        assertEquals(StepCategory.ASSETS, barrier.category)
        assertTrue(barrier.product!!.simulated && barrier.product!!.affiliateUrl.contains("simulados"))
        // La acción de supervivencia equivalente no depende de la compra.
        assertNull(c.steps.first { it.id == "ground-relocate" }.product)
        assertEquals(8.0, AffiliateCommissionCalculator(0.08).commissionFor(100.0), 1e-9)
    }

    // ---------- Telemetría ----------

    private fun event(type: InteractionType, at: Long, issued: Long, stepId: String = "kit") = InteractionEvent(
        type, "sim-otis", HazardType.HURRICANE, Severity.ORANGE, stepId, Phase.EARLY, at, issued,
    )

    @Test
    fun ignoredSteps_countExpiredPhasesOnly() {
        val c = engine.generate(everyone, otisAt(11.5)!!, RiskLevel.MEDIUM)
        assertEquals(0, c.ignoredCount(emptySet(), start))                        // nada ha vencido al inicio
        val at10h = start + (10 * hour)
        val minus12 = c.steps.filter { it.phase == Phase.MINUS_12 }
        assertTrue(minus12.isNotEmpty())
        assertEquals(minus12.size, c.ignoredCount(emptySet(), at10h))             // -12 h vence al llegar -2 h
        assertEquals(0, c.ignoredCount(minus12.map { stepKey(c.alertId, it.id) }.toSet(), at10h))
        val afterImpact = start + (13 * hour)
        assertEquals(c.steps.count { it.phase != Phase.POST_IMPACT }, c.ignoredCount(emptySet(), afterImpact))
    }

    @Test
    fun behaviorReport_isAnonymousAndMeasuresResponseTime() {
        val alert = otisAt(5.0)!!
        val c = engine.generate(everyone, alert, RiskLevel.MEDIUM)
        val first = c.steps.first()
        val issued = alert.issuedAtMillis
        val events = listOf(
            event(InteractionType.STEP_COMPLETED, issued + 10 * 60_000, issued, first.id),
            event(InteractionType.PRODUCT_CLICKED, issued + 20 * 60_000, issued, "shutters"),
        )
        val now = start + (5 * hour)
        val report = BehaviorAnonymizer().build(
            "rid-1", alert, c, setOf(stepKey(c.alertId, first.id)), everyone, "39300", events, now,
        )
        assertEquals("393", report.zonePrefix)                                    // nunca el código postal completo
        assertEquals(ResponseBucket.MIN_5_TO_30, report.response)
        assertEquals(1, report.stepsCompleted)
        assertEquals(1, report.productClicks)
        assertEquals(c.steps.size, report.stepsTotal)
        assertEquals(now / 86_400_000L, report.dayBucket)
        // El reporte no tiene campos que identifiquen la alerta, el paso o la hora exacta.
        val fields = report::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { it.contains("alertId") || it.contains("stepId") || it.contains("address") || it.contains("epochMillis") })
    }

    @Test
    fun behaviorReport_withoutActionIsNoAction() {
        val alert = otisAt(1.0)!!
        val c = engine.generate(HouseholdProfile(), alert, RiskLevel.MEDIUM)
        val r = BehaviorAnonymizer().build("rid-2", alert, c, emptySet(), HouseholdProfile(), "64000", emptyList(), start)
        assertEquals(ResponseBucket.NO_ACTION, r.response)
        assertEquals("640", r.zonePrefix)
    }

    @Test
    fun behaviorReport_buckets() {
        val alert = otisAt(1.0)!!
        val c = engine.generate(HouseholdProfile(), alert, RiskLevel.MEDIUM)
        fun bucket(delayMinutes: Long): ResponseBucket {
            val ev = event(InteractionType.STEP_COMPLETED, alert.issuedAtMillis + delayMinutes * 60_000, alert.issuedAtMillis)
            return BehaviorAnonymizer().build("r", alert, c, emptySet(), HouseholdProfile(), "39300", listOf(ev), start).response
        }
        assertEquals(ResponseBucket.UNDER_5_MIN, bucket(1))
        assertEquals(ResponseBucket.MIN_30_TO_120, bucket(60))
        assertEquals(ResponseBucket.OVER_2_H, bucket(300))
    }
}
