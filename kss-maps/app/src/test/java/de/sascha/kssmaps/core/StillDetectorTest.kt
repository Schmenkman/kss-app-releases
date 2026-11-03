package de.sascha.kssmaps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/** Stillstand: GPS wandert drinnen und meldet 1–5 km/h, obwohl man auf der Couch sitzt. */
class StillDetectorTest {

    private val home = LatLon(48.1, 11.5)
    private fun jitter(r: Random, sigma: Double) = PuckSmoother.offset(home, r.nextGaussian() * sigma, r.nextGaussian() * sigma)

    @Test
    fun couchWithWanderingGpsCountsAsStanding() {
        val d = StillDetector()
        val r = Random(5)
        var firstStill = -1
        var lostAfter = false
        for (i in 0 until 120) {
            val speed = abs(0.8 + r.nextGaussian() * 0.4)   // 0–1,6 m/s = bis ~6 km/h "Rauschen"
            val still = d.update(i.toDouble(), jitter(r, 6.0), 20.0, speed)
            if (still && firstStill < 0) firstStill = i
            if (firstStill >= 0 && !still) lostAfter = true
        }
        assertTrue("erkannt nach $firstStill s", firstStill in 3..8)
        assertFalse("faelschlich losgefahren", lostAfter)
        // Anker bleibt nahe am echten Ort
        assertTrue(Geo.distance(d.anchor!!, home) < 20.0)
    }

    @Test
    fun indoorsWithMediumAccuracyAndSpeedNoiseCountsAsStanding() {
        // Genauigkeit 8 m, gemeldet 1–5 km/h (0,3–1,4 m/s) mit einem Ausreisser
        val d = StillDetector()
        val r = Random(12)
        var still = false
        for (i in 0 until 20) {
            val speed = if (i == 7) 2.0 else 0.3 + r.nextDouble() * 1.1
            still = d.update(i.toDouble(), jitter(r, 3.0), 8.0, speed)
        }
        assertTrue(still)
    }

    @Test
    fun drivingOffEndsStandstillImmediately() {
        val d = StillDetector()
        val r = Random(9)
        for (i in 0 until 10) d.update(i.toDouble(), jitter(r, 1.5), 5.0, 0.0)
        assertTrue(d.stationary)
        // Ampel gruen: 3 m/s bei guter Genauigkeit
        val moving = d.update(10.0, Geo.destination(home, 90.0, 3.0), 5.0, 3.0)
        assertFalse(moving)
        assertEquals(null, d.anchor)
    }

    @Test
    fun crawlingInJamWithGoodGpsIsNotStanding() {
        val d = StillDetector()
        var p = home
        for (i in 0 until 30) {
            p = Geo.destination(p, 0.0, 1.2)
            assertFalse("Stop-and-go bei 1,2 m/s darf nicht als Stand gelten (t=$i)", d.update(i.toDouble(), p, 5.0, 1.2))
        }
    }

    @Test
    fun slowStartWithGoodGpsEndsStandstillAfterTwoFixes() {
        val d = StillDetector()
        for (i in 0 until 6) d.update(i.toDouble(), home, 5.0, 0.0)
        assertTrue(d.stationary)
        var p = home
        p = Geo.destination(p, 0.0, 1.5)
        d.update(6.0, p, 5.0, 1.5)
        p = Geo.destination(p, 0.0, 1.5)
        assertFalse(d.update(7.0, p, 5.0, 1.5))
    }

    @Test
    fun drivingOffWithPoorAccuracyIsDetectedAfterTwoFixes() {
        val d = StillDetector()
        val r = Random(4)
        for (i in 0 until 8) d.update(i.toDouble(), jitter(r, 4.0), 30.0, 0.5)
        assertTrue(d.stationary)
        var p = home
        p = Geo.destination(p, 0.0, 8.0)
        d.update(8.0, p, 30.0, 8.0)
        p = Geo.destination(p, 0.0, 8.0)
        assertFalse(d.update(9.0, p, 30.0, 8.0))
    }

    @Test
    fun walkingAwayFromTheCouchEventuallyCountsAsMoving() {
        // Selbst bei schlechter Genauigkeit und "Rausch-Tempo": wer sich 50 m entfernt, steht nicht mehr
        val d = StillDetector()
        val r = Random(2)
        for (i in 0 until 10) d.update(i.toDouble(), jitter(r, 5.0), 20.0, 0.6)
        assertTrue(d.stationary)
        assertFalse(d.update(11.0, Geo.destination(home, 45.0, 50.0), 20.0, 1.0))
    }
}
