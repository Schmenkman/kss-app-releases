package de.sascha.kssmaps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Heranzoomen vor Abzweigungen und Ausfahrten. */
class TurnZoomTest {

    private fun route(vararg m: Pair<ManeuverKind, Double>): Route {
        val pts = (0 until 400).map { LatLon(48.1, 11.5 + it * 0.0003) }
        val g = Polyline(pts)
        val L = g.length
        val list = listOf(Maneuver(ManeuverKind.DEPART, 0.0, pts[0])) +
            m.map { (k, a) -> Maneuver(k, a, g.pointAt(a)) } +
            Maneuver(ManeuverKind.ARRIVE, L, pts.last())
        return Route(RouteSource.VALHALLA, g, L, list, listOf(L), listOf(0.0 to 0.0, L to 600.0), emptySet(), false, false, false, "")
    }

    @Test
    fun zoomsBeforeATurnAndReturnsAfterIt() {
        val r = route(ManeuverKind.RIGHT to 1000.0)
        val city = 14.0 // ~50 km/h
        assertFalse(TurnZoom.active(r, 700.0, city, false))       // 300 m vorher: noch normal
        assertTrue(TurnZoom.active(r, 880.0, city, false))        // 120 m vorher: nah
        assertTrue(TurnZoom.active(r, 1030.0, city, true))        // 30 m danach: noch nah
        assertFalse(TurnZoom.active(r, 1060.0, city, true))       // danach wieder normal
    }

    @Test
    fun leadGrowsWithSpeedAndHasHysteresis() {
        assertEquals(120.0, TurnZoom.leadMeters(5.0), 1e-9)
        assertEquals(350.0, TurnZoom.leadMeters(40.0), 1e-9)
        val r = route(ManeuverKind.EXIT_RIGHT to 5000.0)
        val motorway = 33.0 // ~120 km/h: 297 m vorher
        assertTrue(TurnZoom.active(r, 4720.0, motorway, false))
        assertFalse(TurnZoom.active(r, 4680.0, motorway, false))
        assertTrue(TurnZoom.active(r, 4680.0, motorway, true))   // einmal nah, bleibt es an der Grenze
    }

    @Test
    fun straightAndMergeDoNotZoomButTwoTurnsInARowStayClose() {
        val r = route(ManeuverKind.CONTINUE to 1000.0, ManeuverKind.MERGE to 2000.0, ManeuverKind.LEFT to 3000.0, ManeuverKind.RIGHT to 3100.0)
        assertFalse(TurnZoom.active(r, 950.0, 14.0, false))
        assertFalse(TurnZoom.active(r, 1950.0, 14.0, false))
        // zwischen den beiden Abzweigungen (100 m Abstand) durchgehend nah
        for (a in listOf(2900.0, 3000.0, 3040.0, 3080.0, 3120.0)) assertTrue("bei $a", TurnZoom.active(r, a, 14.0, true))
    }

    @Test
    fun arrivalAndRoundaboutCount() {
        val r = route(ManeuverKind.ROUNDABOUT to 1500.0, ManeuverKind.ROUNDABOUT_EXIT to 1540.0)
        assertTrue(TurnZoom.active(r, 1450.0, 10.0, false))
        assertTrue(TurnZoom.active(r, 1560.0, 10.0, true))
        assertTrue(TurnZoom.active(r, r.distanceM - 100.0, 10.0, false))
    }
}
