package de.sascha.kssmaps.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Fluessige Positionsanzeige zwischen den GPS-Punkten.
 *
 * GPS liefert etwa einen Punkt pro Sekunde. Frueher sprang der Pfeil bei jedem Punkt, die Karte
 * glitt hinterher – das sah nach Stocken aus. Jetzt wird fuer jedes Bild vorausberechnet, wo das
 * Auto gerade ist: auf der Route entlang der Strecke (folgt Kurven), sonst geradeaus in
 * Fahrtrichtung, jeweils mit dem gemessenen Tempo. Weicht der naechste GPS-Punkt davon ab, wird
 * die Abweichung Bild fuer Bild abgebaut – weich (klingt mit [tauErr] ab) und begrenzt (nie
 * schneller als ein Drittel des Tempos zusaetzlich), damit der Pfeil weder steht noch springt.
 * Beim Fahren laeuft er nie rueckwaerts; nur sehr grosse Abweichungen (GPS-Sprung) werden sofort
 * uebernommen.
 *
 * Zeiten in Sekunden (beliebiger, monotoner Bezug, z. B. System.nanoTime() / 1e9).
 */
class PuckSmoother(
    private val tauErr: Double = 0.8,
    private val tauBearing: Double = 0.35,
    private val maxPredict: Double = 3.0,
    private val snapDist: Double = 60.0,
) {
    // ---- letzter GPS-Punkt (Vorhersage-Modell) ----
    private var fixT = Double.NaN
    private var speed = 0.0
    private var line: Polyline? = null
    private var lineKey: Any? = null
    private var fixAlong = 0.0
    private var fixPos: LatLon? = null
    private var fixBearing: Double? = null

    // ---- Abweichung Anzeige minus Vorhersage, wird pro Bild abgebaut ----
    private var errAlong = 0.0
    private var errE = 0.0
    private var errN = 0.0

    // ---- angezeigt ----
    var pos: LatLon? = null
        private set
    /** Lage auf der Route (m), null ohne Route. */
    var along: Double? = null
        private set
    var bearing: Double = 0.0
        private set
    private var hasBearing = false
    private var targetBearing: Double? = null
    private var lastT = Double.NaN

    val hasPosition: Boolean get() = pos != null

    /** Beim letzten Punkt sofort uebernommen statt weich ausgeglichen (fuer das Test-Protokoll). */
    var lastSnap = false
        private set
    /** Aktuelle Abweichung Anzeige minus Vorhersage in Metern (fuer das Test-Protokoll). */
    val residual: Double get() = if (line != null) errAlong else hypot(errE, errN)
    val predictSpeed: Double get() = speed

    fun reset() {
        fixT = Double.NaN
        speed = 0.0
        line = null
        lineKey = null
        fixPos = null
        pos = null
        along = null
        hasBearing = false
        targetBearing = null
        lastT = Double.NaN
        errAlong = 0.0
        errE = 0.0
        errN = 0.0
    }

    /** Hoechste Korrekturgeschwindigkeit (m/s) zusaetzlich zum Tempo. */
    private fun maxRate(): Double = 0.35 * speed + 1.5

    private fun predAlong(t: Double): Double = fixAlong + speed * min(max(0.0, t - fixT), maxPredict)

    private fun predFree(t: Double): LatLon? {
        val p = fixPos ?: return null
        val b = fixBearing
        return if (b != null && speed > 0.0) Geo.destination(p, b, speed * min(max(0.0, t - fixT), maxPredict)) else p
    }

    /**
     * Neuer GPS-Punkt, auf die Route gelegt. [key] erkennt eine neue Route (z. B. nach einer
     * Neuberechnung): die angezeigte Position wird dann auf die neue Route uebertragen.
     * [ageSec]: so alt war der Punkt beim Eintreffen (aus dem Zeitstempel des GPS).
     */
    fun fixOnRoute(t: Double, route: Polyline, key: Any, routeAlong: Double, speedMps: Double, ageSec: Double = 0.0) {
        advance(t)
        // Derselbe Punkt kurz danach noch einmal (veraltete Wiederholung): Vorhersage nicht zuruecksetzen
        if (key === lineKey && speed > 2.0 && abs(routeAlong - fixAlong) < 0.3 && t - fixT < 0.9) return
        if (key !== lineKey || line == null) {
            // Angezeigte Position auf die (neue) Route legen. Faehrt die Route eine Schleife, liegen
            // mehrere Stellen nah: die nehmen, die zur Lage des GPS-Punkts passt.
            val shown = pos
            along = if (shown != null) {
                route.projectAllNear(shown, 60.0).minByOrNull { abs(it.along - routeAlong) }?.along
                    ?.takeIf { abs(it - routeAlong) < snapDist + 40.0 }
            } else null
        }
        line = route
        lineKey = key
        fixPos = null
        fixAlong = routeAlong
        fixT = t - ageSec.coerceIn(0.0, 1.5)
        speed = if (speedMps < 0.7) 0.0 else speedMps.coerceAtMost(70.0)
        val pred = predAlong(t)
        val shown = along
        if (shown == null || abs(shown - pred) > snapDist) {
            lastSnap = true
            along = pred
            errAlong = 0.0
        } else {
            lastSnap = false
            errAlong = shown - pred
        }
        if (pos == null) {
            pos = route.pointAt(along ?: pred)
            bearing = route.bearingAt(along ?: pred, 24.0)
            hasBearing = true
        }
    }

    /** Neuer GPS-Punkt ohne Route (freie Fahrt, abseits der Route). */
    fun fixFree(t: Double, p: LatLon, headingDeg: Double?, speedMps: Double, ageSec: Double = 0.0) {
        advance(t)
        val fp = fixPos
        if (line == null && fp != null && speed > 2.0 && Geo.distance(fp, p) < 0.3 && t - fixT < 0.9) return
        line = null
        lineKey = null
        along = null
        fixPos = p
        fixBearing = headingDeg
        speed = if (speedMps < 0.7 || headingDeg == null) 0.0 else speedMps.coerceAtMost(70.0)
        fixT = t - ageSec.coerceIn(0.0, 1.5)
        val pred = predFree(t) ?: p
        val shown = pos
        lastSnap = shown == null || Geo.distance(shown, pred) > snapDist
        if (shown == null || Geo.distance(shown, pred) > snapDist) {
            errE = 0.0
            errN = 0.0
            pos = pred
        } else {
            errE = eastM(pred, shown)
            errN = northM(pred, shown)
        }
        if (!hasBearing && headingDeg != null) {
            bearing = headingDeg
            hasBearing = true
        }
    }

    /** Abweichung um ein Bild abbauen: exponentiell, aber hoechstens [maxRate] m/s. */
    private fun shrink(err: Double, dt: Double): Double {
        if (err == 0.0 || dt <= 0.0) return err
        val target = err * exp(-dt / tauErr)
        val lim = maxRate() * dt
        val change = (err - target).coerceIn(-lim, lim)
        val r = err - change
        return if (abs(r) < 0.005) 0.0 else r
    }

    /** Anzeige fuer den Zeitpunkt [t] berechnen (einmal pro Bild). */
    fun advance(t: Double) {
        if (fixT.isNaN()) return
        val dt = if (lastT.isNaN()) 0.0 else (t - lastT).coerceIn(0.0, 0.5)
        lastT = t
        val l = line
        if (l != null) {
            val pred = predAlong(t)
            errAlong = shrink(errAlong, dt)
            var a = pred + errAlong
            val cur = along
            // Beim Fahren nie zurueck: lieber kurz langsamer, bis die Vorhersage aufholt.
            if (cur != null && speed > 0.5 && a < cur) {
                a = cur
                errAlong = a - pred
            }
            a = a.coerceIn(0.0, l.length)
            along = a
            pos = l.pointAt(a)
            targetBearing = l.bearingAt(a, 24.0)
        } else {
            val pred = predFree(t) ?: return
            val m = hypot(errE, errN)
            if (m > 0.0) {
                val k = shrink(m, dt) / m
                errE *= k
                errN *= k
            }
            pos = offset(pred, errE, errN)
            targetBearing = fixBearing
        }
        val tb = targetBearing ?: return
        if (!hasBearing) {
            bearing = tb
            hasBearing = true
        } else if (dt > 0.0) {
            val k = 1.0 - exp(-dt / tauBearing)
            bearing = norm360(bearing + Geo.signedAngle(bearing, tb) * k)
        }
    }

    /** true = es bewegt sich nichts mehr sichtbar, es muessen keine weiteren Bilder berechnet werden. */
    fun isSettled(t: Double): Boolean {
        if (fixT.isNaN()) return true
        val since = t - fixT
        if (speed > 0.0 && since < maxPredict) return false
        // Laenger kein GPS (Tunnel): Vorhersage ist zu Ende, Pfeil bleibt stehen – keine Bilder mehr.
        if (since > maxPredict + 4 * tauErr) return true
        val residual = if (line != null) abs(errAlong) else hypot(errE, errN)
        if (residual > 0.02) return false
        val tb = targetBearing
        return tb == null || abs(Geo.signedAngle(bearing, tb)) < 0.3
    }

    companion object {
        private const val M_PER_DEG = 111_195.0

        private fun norm360(a: Double): Double = ((a % 360.0) + 360.0) % 360.0

        /** Ost-/Nord-Abstand von a nach b in Metern (kleine Entfernungen). */
        fun eastM(a: LatLon, b: LatLon): Double = (b.lon - a.lon) * M_PER_DEG * cos(Geo.toRad(a.lat))
        fun northM(a: LatLon, b: LatLon): Double = (b.lat - a.lat) * M_PER_DEG

        fun offset(p: LatLon, eastM: Double, northM: Double): LatLon {
            if (eastM == 0.0 && northM == 0.0) return p
            val c = cos(Geo.toRad(p.lat)).coerceAtLeast(1e-6)
            return LatLon(p.lat + northM / M_PER_DEG, p.lon + eastM / (M_PER_DEG * c))
        }
    }
}
