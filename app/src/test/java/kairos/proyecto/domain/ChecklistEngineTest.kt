package kairos.proyecto.domain

import kairos.proyecto.domain.business.MicroinsuranceAdvisor
import kairos.proyecto.domain.decision.RuleBasedChecklistEngine
import kairos.proyecto.domain.decision.completedCount
import kairos.proyecto.domain.decision.isCompleted
import kairos.proyecto.domain.decision.stepKey
import kairos.proyecto.domain.model.Alert
import kairos.proyecto.domain.model.HazardType
import kairos.proyecto.domain.model.HouseholdProfile
import kairos.proyecto.domain.model.Phase
import kairos.proyecto.domain.model.RiskLevel
import kairos.proyecto.domain.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistEngineTest {

    private val engine = RuleBasedChecklistEngine()

    private fun alert(hazard: HazardType, severity: Severity, impact: Long? = null) = Alert(
        id = "a", sourceId = "test", hazard = hazard, severity = severity, regionId = "r",
        postalCodes = emptySet(), headline = "t", issuedAtMillis = 0, expiresAtMillis = 1, simulated = true,
        expectedImpactMillis = impact,
    )

    private fun ids(profile: HouseholdProfile, hazard: HazardType, severity: Severity, risk: RiskLevel = RiskLevel.MEDIUM) =
        engine.generate(profile, alert(hazard, severity), risk).steps.map { it.id }

    @Test
    fun groundFloor_addsElevationAndRelocationSteps_forFlood() {
        val base = ids(HouseholdProfile(), HazardType.FLOOD, Severity.ORANGE)
        val ground = ids(HouseholdProfile(groundFloor = true), HazardType.FLOOD, Severity.ORANGE)
        assertFalse("ground-lift" in base)
        assertTrue("ground-lift" in ground && "ground-relocate" in ground)
    }

    @Test
    fun groundFloor_relocationIsMostUrgentInItsPhase() {
        val checklist = engine.generate(HouseholdProfile(groundFloor = true), alert(HazardType.FLOOD, Severity.ORANGE, impact = 10_000), RiskLevel.MEDIUM)
        assertEquals("ground-relocate", checklist.steps.filter { it.phase == Phase.MINUS_12 }.first().id)
    }

    @Test
    fun children_addBagAndMeetingPoint() {
        val kids = ids(HouseholdProfile(hasChildren = true), HazardType.HURRICANE, Severity.ORANGE)
        assertTrue("kids-bag" in kids && "kids-meeting" in kids)
        assertFalse("kids-bag" in ids(HouseholdProfile(), HazardType.HURRICANE, Severity.ORANGE))
    }

    @Test
    fun elderly_addMedsAndAssistedTransferWithEarlyLeadTime() {
        val checklist = engine.generate(HouseholdProfile(hasElderly = true), alert(HazardType.HURRICANE, Severity.ORANGE), RiskLevel.MEDIUM)
        val transfer = checklist.steps.first { it.id == "elder-transfer" }
        assertTrue(checklist.steps.any { it.id == "elder-meds" })
        assertTrue(transfer.hoursBeforeEvent >= 12)
        assertEquals(1, transfer.priority)
    }

    @Test
    fun stepsAreSortedByPhaseThenPriority_andTotalMinutesIsSum() {
        val c = engine.generate(HouseholdProfile(true, true, true, true), alert(HazardType.HURRICANE, Severity.RED, impact = 10_000), RiskLevel.MEDIUM)
        val expected = c.steps.sortedWith(compareBy({ it.phase.ordinal }, { it.priority }))
        assertEquals(expected.map { it.phase to it.priority }, c.steps.map { it.phase to it.priority })
        assertEquals(c.steps.sumOf { it.estimatedMinutes }, c.totalMinutes)
    }

    @Test
    fun higherSeverityNeverRemovesSteps() {
        val profile = HouseholdProfile(true, true, true)
        val yellow = ids(profile, HazardType.FLOOD, Severity.YELLOW).toSet()
        val red = ids(profile, HazardType.FLOOD, Severity.RED).toSet()
        assertTrue(red.containsAll(yellow))
        assertTrue("evacuate" in red && "evacuate" !in yellow)
    }

    @Test
    fun highRiskZoneEscalatesEffectiveSeverity() {
        assertTrue("evacuate" in ids(HouseholdProfile(), HazardType.FLOOD, Severity.ORANGE, RiskLevel.HIGH))
        assertFalse("evacuate" in ids(HouseholdProfile(), HazardType.FLOOD, Severity.ORANGE, RiskLevel.LOW))
    }

    @Test
    fun hazardsProduceDifferentChecklists() {
        assertTrue("secure-outdoor" in ids(HouseholdProfile(), HazardType.HURRICANE, Severity.YELLOW))
        assertFalse("secure-outdoor" in ids(HouseholdProfile(), HazardType.FLOOD, Severity.YELLOW))
    }

    @Test
    fun microinsurance_onlyForLowRiskScenarios() {
        val advisor = MicroinsuranceAdvisor()
        assertNotNull(advisor.offerFor(RiskLevel.LOW, Severity.YELLOW, checklistCompleted = true))
        assertNull(advisor.offerFor(RiskLevel.MEDIUM, Severity.ORANGE, true))
        assertNull(advisor.offerFor(RiskLevel.HIGH, Severity.YELLOW, true)) // HIGH escala a naranja
    }

    @Test
    fun microinsurance_scenarioQualifiesOnlyBelowOrange() {
        val advisor = MicroinsuranceAdvisor()
        assertTrue(advisor.scenarioQualifies(RiskLevel.LOW, Severity.YELLOW))
        assertFalse(advisor.scenarioQualifies(RiskLevel.HIGH, Severity.YELLOW))
        assertFalse(advisor.scenarioQualifies(RiskLevel.LOW, Severity.RED))
    }

    @Test
    fun microinsurance_requiresCompletedChecklist() {
        assertNull(MicroinsuranceAdvisor().offerFor(RiskLevel.LOW, Severity.YELLOW, checklistCompleted = false))
    }

    @Test
    fun progress_completionRequiresEveryStep() {
        val c = engine.generate(HouseholdProfile(), alert(HazardType.FLOOD, Severity.YELLOW), RiskLevel.LOW)
        val all = c.steps.map { stepKey(c.alertId, it.id) }.toSet()
        assertFalse(c.isCompleted(emptySet()))
        assertFalse(c.isCompleted(all - all.first()))
        assertTrue(c.isCompleted(all))
        assertEquals(c.steps.size - 1, c.completedCount(all - all.first()))
    }
}
