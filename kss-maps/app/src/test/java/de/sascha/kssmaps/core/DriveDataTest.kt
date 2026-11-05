package de.sascha.kssmaps.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

/** Baustellen-Zeitfenster, Gegenfahrbahn, Warnbanner, Tempolimits. Alle Daten kuenstlich. */
class DriveDataTest {

    private fun at(s: String): ZonedDateTime = LocalDateTime.parse(s).atZone(Autobahn.BERLIN)

    // ---- Baustellen nur, solange sie gelten ----

    @Test
    fun roadworksOutsideBeginEndAreInactive() {
        val d = listOf("Beginn: 03.11.26 um 08:00 Uhr", "Ende: 20.12.26 um 17:00 Uhr", "", "A9 München -> Nürnberg")
        assertFalse(Autobahn.activeNow(d, "ROADWORKS", "", at("2026-11-02T12:00")))
        assertTrue(Autobahn.activeNow(d, "ROADWORKS", "", at("2026-11-05T03:00"))) // Dauerbaustelle: auch nachts
        assertFalse(Autobahn.activeNow(d, "ROADWORKS", "", at("2026-12-21T09:00")))
        assertTrue(Autobahn.activeNow(listOf("Fahrbahnerneuerung"), "ROADWORKS", "", at("2026-11-05T03:00")))
    }

    @Test
    fun shortTermRoadworksOnlyInTheirDailyWindow() {
        val d = listOf("Beginn: 03.11.26 um 09:00 Uhr", "Ende: 07.11.26 um 15:00 Uhr", "Arbeiten am Mittelstreifen")
        assertTrue(Autobahn.activeNow(d, "SHORT_TERM_ROADWORKS", "", at("2026-11-05T11:30")))
        assertFalse(Autobahn.activeNow(d, "SHORT_TERM_ROADWORKS", "", at("2026-11-05T18:00")))
        assertFalse(Autobahn.activeNow(d, "SHORT_TERM_ROADWORKS", "", at("2026-11-05T07:00")))
        // nachts 20–05 Uhr
        val n = listOf("Beginn: 03.11.26 um 20:00 Uhr", "Ende: 06.11.26 um 05:00 Uhr")
        assertTrue(Autobahn.activeNow(n, "SHORT_TERM_ROADWORKS", "", at("2026-11-04T23:00")))
        assertTrue(Autobahn.activeNow(n, "SHORT_TERM_ROADWORKS", "", at("2026-11-05T04:30")))
        assertFalse(Autobahn.activeNow(n, "SHORT_TERM_ROADWORKS", "", at("2026-11-05T12:00")))
        // ausdruecklich genannt
        val e = listOf("Beginn: 02.11.26", "Ende: 30.11.26", "täglich 9:00 - 15:30 Uhr")
        assertTrue(Autobahn.activeNow(e, "ROADWORKS", "", at("2026-11-10T15:00")))
        assertFalse(Autobahn.activeNow(e, "ROADWORKS", "", at("2026-11-10T16:30")))
    }

    @Test
    fun autobahnParserDropsInactiveRoadworks() {
        fun item(desc: String) = JSONObject().put("identifier", desc.hashCode().toString()).put("display_type", "SHORT_TERM_ROADWORKS")
            .put("coordinate", JSONObject().put("lat", "48.3").put("long", "11.6")).put("title", "A9 | Test")
            .put("description", JSONArray().put(desc).put("Ende: 07.11.26 um 15:00 Uhr"))
        val json = JSONObject().put("roadworks", JSONArray().put(item("Beginn: 03.11.26 um 09:00 Uhr")).put(item("Beginn: 03.11.26 um 13:00 Uhr"))).toString()
        assertEquals(2, Autobahn.parse(json, "roadworks", "A9", at("2026-11-05T14:00")).size)
        assertEquals(0, Autobahn.parse(json, "roadworks", "A9", at("2026-11-05T19:00")).size)
    }

    // ---- Gegenfahrbahn ----

    @Test
    fun lineHazardOnTheOppositeCarriagewayIsIgnored() {
        val route = Polyline((0 until 200).map { LatLon(48.30 + it * 0.0002, 11.60) }) // nach Norden
        // 18 m oestlich, Richtung Sueden (Gegenfahrbahn)
        val opposite = (150 downTo 50).map { LatLon(48.30 + it * 0.0002, 11.60 + 18.0 / 74_000) }
        val h = Hazard("x", HazardType.ROADWORKS, "Baustelle", "", opposite.first(), opposite, source = "test")
        assertTrue(HazardMatcher.match(route, h).isEmpty())
        // dieselbe Linie in Fahrtrichtung zaehlt
        assertEquals(1, HazardMatcher.match(route, h.copy(geometry = opposite.reversed(), point = opposite.last())).size)
        // genau auf der Route liegend zaehlt auch in umgekehrter Punktreihenfolge
        val onRoute = (150 downTo 50).map { LatLon(48.30 + it * 0.0002, 11.60) }
        assertEquals(1, HazardMatcher.match(route, h.copy(geometry = onRoute, point = onRoute.first())).size)
    }

    // ---- Warnbanner ----

    @Test
    fun bannerSaysInAndStill() {
        val h = Hazard("b", HazardType.ROADWORKS, "Baustelle", "", LatLon(48.0, 11.0), source = "test")
        val rh = RouteHazard(h, 5000.0, 6200.0)
        assertEquals("Baustelle in 800 m", Instructions.hazardBanner(rh, 800.0, 4200.0))
        assertEquals("Baustelle noch 1,2 km", Instructions.hazardBanner(rh, 0.0, 5000.0))
        assertEquals("Baustelle", Instructions.hazardBanner(rh, 0.0, 6190.0))
        val jam = RouteHazard(h.copy(type = HazardType.JAM, delaySec = 480), 100.0, 2600.0)
        assertEquals("Stau in 2,5 km · +8 Min.", Instructions.hazardBanner(jam, 2500.0, 0.0))
        assertEquals("Stau noch 1,5 km · +8 Min.", Instructions.hazardBanner(jam, 0.0, 1100.0))
        val cam = h.copy(type = HazardType.SPEED_CAMERA, title = "Blitzer", maxSpeed = 80)
        assertEquals("Blitzer in 400 m · 80 km/h", Instructions.cameraBanner(cam, 400.0))
    }

    // ---- Anruf-Erinnerung ----

    @Test
    fun callBeforeMinutesFromTheNote() {
        val r = ContactParser.parse("Termin mit Herr Müller 0171 2345678 vereinbart. Bitte vorher Herr Mader 30 min vorher unter 08761/72345 anrufen")
        assertEquals(30, r.contacts[0].callBeforeMin)
        assertNull(r.contacts[1].callBeforeMin)
        assertEquals(60, ContactParser.parse("Bitte 1 Std. vorher bei Herrn Wagner anrufen: 0160 1234567").contacts[0].callBeforeMin)
        assertNull(ContactParser.parse("Herr Bauer 0871 1234567. Vorher anrufen").contacts[0].callBeforeMin)
    }

    // ---- Tempolimits ----

    @Test
    fun tomTomSpeedLimitsFillGapsOfOsm() {
        val pts = (0 until 100).map { LatLon(48.1, 11.5 + it * 0.0005) }
        val ptsJson = JSONArray().apply { pts.forEach { put(JSONObject().put("latitude", it.lat).put("longitude", it.lon)) } }
        val len = Geo.lineLength(pts)
        val route = JSONObject()
            .put("summary", JSONObject().put("lengthInMeters", len).put("travelTimeInSeconds", 600))
            .put("legs", JSONArray().put(JSONObject().put("points", ptsJson).put("summary", JSONObject().put("lengthInMeters", len))))
            .put(
                "sections", JSONArray()
                    .put(JSONObject().put("sectionType", "SPEED_LIMIT").put("startPointIndex", 0).put("endPointIndex", 50).put("maxSpeedLimitInKmh", 100))
                    .put(JSONObject().put("sectionType", "SPEED_LIMIT").put("startPointIndex", 50).put("endPointIndex", 99).put("maxSpeedLimitInKmh", 70))
                    .put(JSONObject().put("sectionType", "TRAFFIC").put("startPointIndex", 10).put("endPointIndex", 20).put("simpleCategory", "ROAD_WORK").put("magnitudeOfDelay", 0).put("delayInSeconds", 0))
                    .put(JSONObject().put("sectionType", "TRAFFIC").put("startPointIndex", 30).put("endPointIndex", 40).put("simpleCategory", "ROAD_WORK").put("magnitudeOfDelay", 1).put("delayInSeconds", 60)),
            )
            .put("guidance", JSONObject().put("instructions", JSONArray()
                .put(JSONObject().put("routeOffsetInMeters", 0).put("travelTimeInSeconds", 0).put("maneuver", "DEPART"))
                .put(JSONObject().put("routeOffsetInMeters", len).put("travelTimeInSeconds", 600).put("maneuver", "ARRIVE"))))
        val r = TomTom.parseRoutes(JSONObject().put("routes", JSONArray().put(route)).toString())[0]
        assertEquals(1, r.hazards.size) // Baustelle ohne Auswirkung faellt weg
        assertEquals(100, r.speedLimitAt(100.0))
        assertEquals(70, r.speedLimitAt(len - 100.0))
        r.speedLimits = listOf(SpeedLimitRange(0.0, 500.0, 80), SpeedLimitRange(500.0, 1500.0, 0))
        assertEquals(80, r.speedLimitAt(100.0))   // beschildert (OSM) geht vor
        assertEquals(100, r.speedLimitAt(800.0))  // unbekannt in OSM -> TomTom
        assertTrue(TomTom.routeUrl(pts.take(2), "k", RouteOptions(), 0, null).contains("sectionType=speedLimit"))
        assertFalse(TomTom.routeUrl(pts.take(2), "k", RouteOptions(), 0, null, speedLimits = false).contains("speedLimit"))
    }

    @Test
    fun variableSignsAndTimedLimits() {
        val g = JSONArray()
        for (i in 0 until 50) { g.put(48.30 + i * 0.0005); g.put(11.60) } // Richtung Norden
        val json = JSONObject().put("ways", JSONArray()
            .put(JSONObject().put("ms", "none").put("var", 1).put("ow", 1).put("g", g))
            .put(JSONObject().put("ms", "none").put("cond", "120 @ (22:00-06:00); 80 @ wet").put("ow", 1)
                .put("g", JSONArray().put(48.40).put(11.70).put(48.42).put(11.70))),
        ).toString()
        val idx = TempoIndex.parse(json)
        val noon = LocalDateTime.parse("2026-11-05T12:00")
        assertEquals(SpeedSign(null, variable = true), idx.at(LatLon(48.31, 11.60005), 0.0, noon))
        assertNull(idx.at(LatLon(48.31, 11.60005), 180.0, noon))         // Gegenrichtung
        assertNull(idx.at(LatLon(48.31, 11.6010), 0.0, noon))            // 74 m daneben
        assertNull(idx.at(LatLon(48.41, 11.70), 0.0, noon))              // tagsueber frei
        assertEquals(SpeedSign(120, variable = false), idx.at(LatLon(48.41, 11.70), 0.0, LocalDateTime.parse("2026-11-05T23:15")))
        assertEquals(2, TempoIndex.parseConditional("100 @ (Mo-Fr 06:00-10:00, 15:00-19:00)").size)
    }
}
