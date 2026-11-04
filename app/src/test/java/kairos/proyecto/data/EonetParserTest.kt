package kairos.proyecto.data

import kairos.proyecto.data.alerts.EonetParser
import kairos.proyecto.domain.model.HazardType
import kairos.proyecto.domain.model.Regions
import kairos.proyecto.domain.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EonetParserTest {

    private val now = Instant.parse("2026-10-23T00:00:00Z").toEpochMilli()

    private fun event(id: String, category: String, wind: Double?, lon: Double, lat: Double, date: String) = """
        {"id":"$id","title":"Evento $id","categories":[{"id":"$category"}],"geometry":[
          {"date":"2026-10-21T00:00:00Z","type":"Point","coordinates":[-90.0,10.0]},
          {${wind?.let { "\"magnitudeValue\":$it," } ?: ""}"date":"$date","type":"Point","coordinates":[$lon,$lat]}]}
    """.trimIndent()

    private fun feed(vararg events: String) = """{"events":[${events.joinToString(",")}]}"""

    @Test
    fun hurricaneInsideSouthwestBox_becomesRealAlertWithSeverityFromWind() {
        val json = feed(event("E1", "severeStorms", 100.0, -100.0, 16.0, "2026-10-22T18:00:00Z"))
        val alert = EonetParser.parse(json, Regions.SOUTHWEST.id, now).single()
        assertEquals(HazardType.HURRICANE, alert.hazard)
        assertEquals(Severity.RED, alert.severity)
        assertFalse(alert.simulated)
        assertTrue(alert.headline.startsWith("[NASA EONET]"))
        assertTrue(alert.isActive(now))
        assertTrue(alert.affects("39300")) // sin códigos postales: aplica a toda la región
    }

    @Test
    fun usesLatestObservationNotTheFirstOne() {
        // El primer punto (lon -90) estaría fuera de la región; el último está dentro.
        val json = feed(event("E2", "severeStorms", 40.0, -99.0, 17.0, "2026-10-22T12:00:00Z"))
        assertEquals(1, EonetParser.parse(json, Regions.SOUTHWEST.id, now).size)
    }

    @Test
    fun eventsOutsideRegionStaleOrOfOtherCategoriesAreIgnored() {
        val outside = event("E3", "severeStorms", 50.0, -80.0, 25.0, "2026-10-22T12:00:00Z")
        val stale = event("E4", "severeStorms", 50.0, -100.0, 16.0, "2026-10-10T12:00:00Z")
        val fire = event("E5", "wildfires", null, -100.0, 16.0, "2026-10-22T12:00:00Z")
        assertTrue(EonetParser.parse(feed(outside, stale, fire), Regions.SOUTHWEST.id, now).isEmpty())
    }

    @Test
    fun northeastFloodDefaultsToYellow_andForecastPointsAreIgnored() {
        val future = event("E6", "floods", null, -98.0, 25.0, "2026-10-25T00:00:00Z")
        assertTrue(EonetParser.parse(feed(future), Regions.NORTHEAST.id, now).isEmpty())
        val current = event("E7", "floods", null, -98.0, 25.0, "2026-10-22T20:00:00Z")
        assertEquals(Severity.YELLOW, EonetParser.parse(feed(current), Regions.NORTHEAST.id, now).single().severity)
    }

    @Test
    fun stormSeverityFollowsWindScale() {
        assertEquals(Severity.GREEN, EonetParser.stormSeverity(25.0))
        assertEquals(Severity.YELLOW, EonetParser.stormSeverity(45.0))
        assertEquals(Severity.ORANGE, EonetParser.stormSeverity(70.0))
        assertEquals(Severity.RED, EonetParser.stormSeverity(110.0))
        assertEquals(Severity.YELLOW, EonetParser.stormSeverity(null))
    }
}
