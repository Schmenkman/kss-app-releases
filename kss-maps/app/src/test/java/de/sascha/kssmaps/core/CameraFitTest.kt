package de.sascha.kssmaps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFitTest {
    private val none = doubleArrayOf(0.0, 0.0, 0.0, 0.0)

    @Test
    fun routeFromMunichToNurembergIsCenteredBetweenBoth() {
        val pts = listOf(LatLon(48.137, 11.575), LatLon(48.9, 11.5), LatLon(49.452, 11.077))
        val r = CameraFit.fit(pts, 1280.0, 720.0, none, 1.0)!!
        // Mitte zwischen den Enden (in Mercator), nicht irgendwo anders
        assertEquals(48.796, r.lat, 0.05)
        assertEquals(11.326, r.lon, 0.01)
        // ~150 km Nord-Sued auf 720 px: Zoom etwa 7,5–8,5
        assertTrue("zoom=${r.zoom}", r.zoom in 7.0..9.0)
    }

    @Test
    fun paddingLeavesTheRouteInTheFreePartAndZoomsOut() {
        val pts = listOf(LatLon(48.10, 11.50), LatLon(48.20, 11.70))
        val free = CameraFit.fit(pts, 1280.0, 720.0, none, 2.0)!!
        val panel = CameraFit.fit(pts, 1280.0, 720.0, doubleArrayOf(420.0, 40.0, 140.0, 40.0), 2.0)!!
        assertTrue(panel.zoom < free.zoom)
        // Mittelpunkt bleibt die Mitte der Punkte – den Rand verschiebt MapLibre ueber das Padding
        assertEquals(free.lat, panel.lat, 1e-9)
        assertEquals(free.lon, panel.lon, 1e-9)
    }

    @Test
    fun singlePointGetsMaxZoomAndBadPointsAreIgnored() {
        val p = LatLon(48.0, 11.0)
        val r = CameraFit.fit(listOf(p, LatLon(0.0, 0.0), LatLon(Double.NaN, 11.0)), 800.0, 480.0, none, 1.0)!!
        assertEquals(17.0, r.zoom, 1e-9)
        assertEquals(48.0, r.lat, 1e-6)
        assertEquals(11.0, r.lon, 1e-6)
        assertNull(CameraFit.fit(listOf(LatLon(0.0, 0.0)), 800.0, 480.0, none, 1.0))
        assertNull(CameraFit.fit(emptyList(), 800.0, 480.0, none, 1.0))
    }

    @Test
    fun hugePaddingStillGivesAUsableZoom() {
        val pts = listOf(LatLon(48.0, 11.0), LatLon(48.5, 11.5))
        val r = CameraFit.fit(pts, 800.0, 480.0, doubleArrayOf(700.0, 300.0, 300.0, 300.0), 1.0)!!
        assertTrue(r.zoom in 3.0..17.0)
    }
}
