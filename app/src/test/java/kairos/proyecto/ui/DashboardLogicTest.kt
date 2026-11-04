package kairos.proyecto.ui

import kairos.proyecto.domain.decision.RuleBasedChecklistEngine
import kairos.proyecto.domain.decision.stepKey
import kairos.proyecto.domain.model.Alert
import kairos.proyecto.domain.model.HazardType
import kairos.proyecto.domain.model.HouseholdProfile
import kairos.proyecto.domain.model.Phase
import kairos.proyecto.domain.model.RiskLevel
import kairos.proyecto.domain.model.Severity
import kairos.proyecto.domain.model.StepCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLogicTest {

    private val hour = 3_600_000L
    private val start = 1_000_000_000L
    private val impact = start + 12 * hour

    private val alert = Alert(
        id = "otis", sourceId = "sim-pc-guerrero", hazard = HazardType.HURRICANE, severity = Severity.RED, regionId = "r",
        postalCodes = emptySet(), headline = "[SIMULADO] Otis", issuedAtMillis = start, expiresAtMillis = start + 60 * hour,
        simulated = true, expectedImpactMillis = impact,
    )
    private val checklist = RuleBasedChecklistEngine().generate(
        HouseholdProfile(groundFloor = true, hasPets = true), alert, RiskLevel.MEDIUM,
    )
    private val items = listOf(AlertWithChecklist(alert, checklist))

    @Test
    fun formatDuration_readableUnits() {
        assertEquals("menos de 1 min", formatDuration(10_000))
        assertEquals("35 min", formatDuration(35 * 60_000))
        assertEquals("5 h", formatDuration(5 * hour))
        assertEquals("5 h 20 min", formatDuration(5 * hour + 20 * 60_000))
        assertEquals("2 d 3 h", formatDuration(51 * hour))
        assertEquals("menos de 1 min", formatDuration(-5))
    }

    @Test
    fun categoryProgress_countsDoneAndTotalPerModule() {
        val pets = checklist.steps.filter { it.category == StepCategory.PETS }
        assertTrue(pets.isNotEmpty())
        val done = setOf(stepKey("otis", pets.first().id))
        assertEquals(1 to pets.size, categoryProgress(items, done, StepCategory.PETS))
        assertEquals(0 to pets.size, categoryProgress(items, emptySet(), StepCategory.PETS))
    }

    @Test
    fun phaseState_followsTimeAndCompletion() {
        val early = start
        assertEquals(PhaseState.LOCKED, phaseState(checklist, Phase.MINUS_2, emptySet(), early))
        assertEquals(PhaseState.ACTIVE, phaseState(checklist, Phase.MINUS_12, emptySet(), early))
        assertEquals(PhaseState.PENDING, phaseState(checklist, Phase.EARLY, emptySet(), early))
        val allMinus12 = checklist.steps.filter { it.phase == Phase.MINUS_12 }.map { stepKey("otis", it.id) }.toSet()
        assertEquals(PhaseState.DONE, phaseState(checklist, Phase.MINUS_12, allMinus12, early))
        assertEquals(PhaseState.ACTIVE, phaseState(checklist, Phase.POST_IMPACT, emptySet(), impact + hour))
    }

    @Test
    fun remainingMinutes_onlyCountsUnlockedPendingSteps() {
        val atStart = remainingMinutes(checklist, emptySet(), start)
        val lockedMinutes = checklist.steps.filter { it.phase == Phase.MINUS_2 || it.phase == Phase.POST_IMPACT }.sumOf { it.estimatedMinutes }
        assertEquals(checklist.totalMinutes - lockedMinutes, atStart)
        val everything = checklist.steps.map { stepKey("otis", it.id) }.toSet()
        assertEquals(0, remainingMinutes(checklist, everything, impact + hour))
    }

    @Test
    fun headlineAndSourceLabels_areCleaned() {
        assertEquals("Otis", alert.cleanHeadline())
        assertEquals("Protección Civil Guerrero", sourceLabel("sim-pc-guerrero"))
        assertEquals("NASA EONET", sourceLabel("nasa-eonet"))
    }
}
