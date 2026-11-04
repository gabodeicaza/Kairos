package kairos.proyecto.domain

import kairos.proyecto.domain.model.HouseholdProfile
import kairos.proyecto.domain.model.Severity
import kairos.proyecto.domain.notification.NotificationKind
import kairos.proyecto.domain.notification.NotificationPolicy
import kairos.proyecto.domain.notification.NotificationPrefs
import kairos.proyecto.domain.notification.SendRecord
import kairos.proyecto.domain.privacy.AnonymousHousehold
import kairos.proyecto.domain.privacy.Anonymizer
import kairos.proyecto.domain.privacy.LocationMode
import kairos.proyecto.domain.privacy.LocationPolicy
import kairos.proyecto.domain.privacy.PrivacyConsent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyTest {

    private val policy = NotificationPolicy()
    private val dayStart = 0L
    private val now = 12L * 3600 * 1000

    private fun alerts(n: Int) = List(n) { SendRecord(NotificationKind.ALERT, 1000L * (it + 1)) }

    private fun canAlert(prefs: NotificationPrefs, history: List<SendRecord>, hour: Int = 12, sev: Severity = Severity.ORANGE) =
        policy.canSend(NotificationKind.ALERT, sev, prefs, history, now, hour, dayStart)

    @Test
    fun alerts_cappedAtThreePerDay() {
        assertTrue(canAlert(NotificationPrefs(), alerts(2)))
        assertFalse(canAlert(NotificationPrefs(), alerts(3)))
    }

    @Test
    fun userLimit_isRespectedAndClampedToThree() {
        assertFalse(canAlert(NotificationPrefs(maxAlertsPerDay = 1), alerts(1)))
        assertFalse(canAlert(NotificationPrefs(maxAlertsPerDay = 99), alerts(3)))
        assertFalse(canAlert(NotificationPrefs(maxAlertsPerDay = 0), emptyList()))
    }

    @Test
    fun quietHours_blockAllButRedAlerts() {
        assertFalse(canAlert(NotificationPrefs(), emptyList(), hour = 23))
        assertTrue(canAlert(NotificationPrefs(), emptyList(), hour = 23, sev = Severity.RED))
        assertFalse(canAlert(NotificationPrefs(), alerts(3), hour = 23, sev = Severity.RED))
    }

    @Test
    fun weekly_onePerWeekAndRespectsOptOut() {
        val weekly = SendRecord(NotificationKind.WEEKLY_PREPAREDNESS, now - 2L * 24 * 3600 * 1000)
        fun can(prefs: NotificationPrefs, h: List<SendRecord>) =
            policy.canSend(NotificationKind.WEEKLY_PREPAREDNESS, null, prefs, h, now, 12, dayStart)
        assertTrue(can(NotificationPrefs(), emptyList()))
        assertFalse(can(NotificationPrefs(), listOf(weekly)))
        assertFalse(can(NotificationPrefs(weeklyPreparednessEnabled = false), emptyList()))
    }

    @Test
    fun exactGps_requiresExplicitConsent() {
        val lp = LocationPolicy()
        val base = PrivacyConsent(privacyNoticeVersion = "v1")
        assertEquals(LocationMode.APPROXIMATE, lp.withMode(base, LocationMode.EXACT_GPS, false, 1).locationMode)
        val granted = lp.withMode(base, LocationMode.EXACT_GPS, true, 1)
        assertTrue(lp.canCaptureDeviceLocation(granted, hasActiveAlert = false))
        assertFalse(lp.canCaptureDeviceLocation(base, hasActiveAlert = true)) // modo aproximado nunca captura
    }

    @Test
    fun onlyDuringAlert_capturesOnlyWithActiveAlert() {
        val lp = LocationPolicy()
        val c = lp.withMode(PrivacyConsent(privacyNoticeVersion = "v1"), LocationMode.ONLY_DURING_ALERT, false, 1)
        assertTrue(lp.canCaptureDeviceLocation(c, true))
        assertFalse(lp.canCaptureDeviceLocation(c, false))
        assertFalse(lp.canCaptureDeviceLocation(c.copy(privacyNoticeVersion = null), true))
    }

    @Test
    fun anonymizer_suppressesSmallGroupsAndAggregates() {
        val anon = Anonymizer(minGroupSize = 10)
        val big = List(10) { AnonymousHousehold("r", "39300", HouseholdProfile(groundFloor = it < 4)) }
        val small = List(3) { AnonymousHousehold("r", "88500", HouseholdProfile()) }
        val result = anon.aggregate(big + small)
        assertEquals(1, result.size)
        assertEquals("39300", result[0].postalCode)
        assertEquals(0.4, result[0].groundFloorShare, 1e-9)
    }
}
