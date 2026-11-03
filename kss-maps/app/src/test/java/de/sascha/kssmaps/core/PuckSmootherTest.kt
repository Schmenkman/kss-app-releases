package de.sascha.kssmaps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

/** Fluessiger Positionspfeil: 1 GPS-Punkt pro Sekunde, 60 Bilder pro Sekunde. */
class PuckSmootherTest {

    private val frame = 1.0 / 60

    private fun straight(n: Int = 400): Polyline = Polyline((0 until n).map { LatLon(48.1, 11.5 + it * 0.0005) })

    @Test
    fun movesEvenlyBetweenNoisyFixes() {
        val line = straight()
        val s = PuckSmoother()
        val v = 20.0
        val rnd = Random(7)
        var nextFix = 0.0
        var t = 0.0
        var prev: Double? = null
        var maxRatio = 0.0
        var maxLag = 0.0
        while (t < 40.0) {
            if (t >= nextFix) {
                val noisy = max(0.0, v * t + rnd.nextGaussian() * 2.0)
                s.fixOnRoute(t, line, line, noisy, v + rnd.nextGaussian() * 0.5)
                nextFix += 0.9 + rnd.nextDouble() * 0.3
            }
            s.advance(t)
            val a = s.along!!
            if (prev != null && t > 3.0) {
                val step = a - prev
                assertTrue("rueckwaerts bei t=$t: $step", step >= -1e-9)
                maxRatio = max(maxRatio, step / (v * frame))
                maxLag = max(maxLag, abs(a - v * t))
            }
            prev = a
            t += frame
        }
        // Ohne Glaettung waere ein Bild pro Sekunde ein Sprung um rund 60 Bildschritte.
        assertTrue("maxRatio=$maxRatio", maxRatio < 2.0)
        assertTrue("maxLag=$maxLag", maxLag < 8.0)
    }

    @Test
    fun exactFixesGiveConstantSpeed() {
        val line = straight()
        val s = PuckSmoother()
        val v = 25.0
        var t = 0.0
        var nextFix = 0.0
        var prev: Double? = null
        var minR = 9.0
        var maxR = 0.0
        while (t < 20.0) {
            if (t >= nextFix - 1e-9) {
                s.fixOnRoute(t, line, line, v * t, v)
                nextFix += 1.0
            }
            s.advance(t)
            val a = s.along!!
            if (prev != null && t > 2.0) {
                val r = (a - prev) / (v * frame)
                minR = minOf(minR, r)
                maxR = max(maxR, r)
            }
            prev = a
            t += frame
        }
        assertTrue("min=$minR max=$maxR", minR > 0.9 && maxR < 1.1)
    }

    @Test
    fun followsCurvesWithoutCuttingCornersAndTurnsGradually() {
        // 1 km nach Osten, dann 1 km nach Norden
        val corner = LatLon(48.1, 11.5 + 1000.0 / (111_195.0 * Math.cos(Math.toRadians(48.1))))
        val pts = Geo.densify(listOf(LatLon(48.1, 11.5), corner, LatLon(48.1 + 1000.0 / 111_195.0, corner.lon)), 10.0)
        val line = Polyline(pts)
        val s = PuckSmoother()
        val v = 15.0
        var t = 0.0
        var nextFix = 0.0
        var prevB: Double? = null
        var maxTurn = 0.0
        while (t < 120.0 && v * t < line.length - 50) {
            if (t >= nextFix - 1e-9) {
                s.fixOnRoute(t, line, line, v * t, v)
                nextFix += 1.0
            }
            s.advance(t)
            val p = s.pos!!
            val pr = line.project(p)!!
            assertTrue("neben der Route: ${pr.offset}", pr.offset < 0.5)
            prevB?.let { maxTurn = max(maxTurn, abs(Geo.signedAngle(it, s.bearing))) }
            prevB = s.bearing
            t += frame
        }
        assertTrue("Drehung pro Bild $maxTurn Grad", maxTurn < 4.0)
        assertEquals(0.0, Geo.signedAngle(s.bearing, 0.0), 5.0)
    }

    @Test
    fun newRouteObjectWithSameGeometryDoesNotJump() {
        val a = straight()
        val b = straight()
        val s = PuckSmoother()
        val v = 20.0
        var t = 0.0
        for (i in 0 until 5) {
            s.fixOnRoute(t, a, a, v * t, v)
            repeat(60) { s.advance(t); t += frame }
        }
        s.advance(t)
        val before = s.pos!!
        // Neuberechnung: neue Route beginnt am (GPS-)Standort, kurz hinter dem angezeigten Pfeil
        val c = Polyline(listOf(LatLon(before.lat, before.lon - 0.00003)) + a.points.filter { it.lon > before.lon + 1e-6 })
        s.fixOnRoute(t, c, c, 2.0, v)
        s.advance(t + frame)
        assertTrue("Sprung ${Geo.distance(before, s.pos!!)} m", Geo.distance(before, s.pos!!) < 1.5)
        // b: gleiche Punkte, anderes Objekt
        s.fixOnRoute(t + 2 * frame, b, b, s.along!! + 5 * 37.0, v)
        s.advance(t + 3 * frame)
        assertTrue(s.pos != null)
    }

    @Test
    fun freeDrivingIsSmoothAndSettlesWhenStopped() {
        val s = PuckSmoother()
        val v = 14.0
        val start = LatLon(48.1, 11.5)
        val rnd = Random(3)
        var t = 0.0
        var nextFix = 0.0
        var prevE: Double? = null
        var maxRatio = 0.0
        while (t < 20.0) {
            if (t >= nextFix - 1e-9) {
                val truth = Geo.destination(start, 90.0, v * t)
                val noisy = PuckSmoother.offset(truth, rnd.nextGaussian() * 1.5, rnd.nextGaussian() * 1.5)
                s.fixFree(t, noisy, 90.0, v)
                nextFix += 1.0
            }
            s.advance(t)
            val e = PuckSmoother.eastM(start, s.pos!!)
            if (prevE != null && t > 2.0) maxRatio = max(maxRatio, abs(e - prevE) / (v * frame))
            prevE = e
            t += frame
        }
        assertTrue("maxRatio=$maxRatio", maxRatio < 2.0)
        // Anhalten: Pfeil bleibt stehen, Bildberechnung darf aufhoeren
        val stop = s.pos!!
        s.fixFree(t, stop, 90.0, 0.0)
        var settledAt = -1.0
        for (i in 0 until 600) {
            t += frame
            s.advance(t)
            if (s.isSettled(t)) {
                settledAt = t
                break
            }
        }
        assertTrue("kommt nicht zur Ruhe", settledAt > 0)
        assertTrue(Geo.distance(stop, s.pos!!) < 0.5)
    }
}
