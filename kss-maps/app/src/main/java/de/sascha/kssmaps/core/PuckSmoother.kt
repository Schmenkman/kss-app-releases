package de.sascha.kssmaps.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Fluessige Positionsanzeige zwischen den GPS-Punkten.
 *
 * GPS liefert etwa einen Punkt pro Sekunde. Frueher sprang der Pfeil bei jedem Punkt, die Karte
 * glitt hinterher – das sah nach Stocken aus. Jetzt wird fuer jedes Bild (60 pro Sekunde)
 * vorausberechnet, wo das Auto gerade ist: auf der Route entlang der Strecke (folgt Kurven),
 * sonst geradeaus in Fahrtrichtung, jeweils mit dem gemessenen Tempo. Weicht der naechste
 * GPS-Punkt davon ab, wird die Abweichung weich ausgeglichen (klingt mit [tauErr] ab), statt
 * zu springen. Rueckwaerts laeuft der Pfeil beim Fahren nie.
 *
 * Zeiten in Sekunden (beliebiger, monotoner Bezug, z. B. System.nanoTime() / 1e9).
 */
class PuckSmoother(
    private val tauErr: Double = 0.6,
    private val tauBearing: Double = 0.35,
    private val maxPredict: Double = 2.0,
) {
    // ---- letzter GPS-Punkt ----
    private var fixT = Double.NaN
    private var speed = 0.0
    private var line: Polyline? = null
    private var lineKey: Any? = null
    private var fixAlong = 0.0
    private var errAlong = 0.0
    private var fixPos: LatLon? = null
    private var fixBearing: Double? = null
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

    /**
     * Neuer GPS-Punkt, auf die Route gelegt. [key] erkennt eine neue Route (z. B. nach einer
     * Neuberechnung): die angezeigte Position wird dann auf die neue Route uebertragen.
     */
    fun fixOnRoute(t: Double, route: Polyline, key: Any, routeAlong: Double, speedMps: Double) {
        advance(t)
        if (key !== lineKey || line == null) {
            val shown = pos
            val pr = if (shown != null) route.projectNear(shown, 60.0) else null
            along = pr?.along
        }
        line = route
        lineKey = key
        fixPos = null
        var err = (along ?: routeAlong) - routeAlong
        if (abs(err) > 150.0) {
            // Grosser Sprung (GPS-Aussetzer, falsche Zuordnung): nicht 150 m "nachrutschen"
            err = 0.0
            along = routeAlong
        }
        if (along == null) along = routeAlong
        errAlong = err
        fixAlong = routeAlong
        speed = if (speedMps < 0.7) 0.0 else speedMps
        fixT = t
        if (pos == null) {
            pos = route.pointAt(routeAlong)
            bearing = route.bearingAt(routeAlong, 24.0)
            hasBearing = true
        }
    }

    /** Neuer GPS-Punkt ohne Route (freie Fahrt, abseits der Route). */
    fun fixFree(t: Double, p: LatLon, headingDeg: Double?, speedMps: Double) {
        advance(t)
        line = null
        lineKey = null
        along = null
        val shown = pos
        if (shown == null || Geo.distance(shown, p) > 300.0) {
            errE = 0.0
            errN = 0.0
            pos = p
        } else {
            errE = eastM(p, shown)
            errN = northM(p, shown)
        }
        fixPos = p
        fixBearing = headingDeg
        speed = if (speedMps < 0.7 || headingDeg == null) 0.0 else speedMps
        fixT = t
        if (!hasBearing && headingDeg != null) {
            bearing = headingDeg
            hasBearing = true
        }
    }

    /** Anzeige fuer den Zeitpunkt [t] berechnen (einmal pro Bild). */
    fun advance(t: Double) {
        if (fixT.isNaN()) return
        val since = max(0.0, t - fixT)
        val dtPred = min(since, maxPredict)
        val decay = exp(-since / tauErr)
        val fdt = if (lastT.isNaN()) 0.0 else (t - lastT).coerceIn(0.0, 0.5)
        lastT = t
        val l = line
        if (l != null) {
            val target = fixAlong + speed * dtPred + errAlong * decay
            val cur = along ?: target
            // Beim Fahren nie zurueck: lieber kurz stehen, bis die Vorhersage aufholt.
            var a = if (speed > 0.5) max(cur, target) else target
            a = a.coerceIn(0.0, l.length)
            along = a
            pos = l.pointAt(a)
            targetBearing = l.bearingAt(a, 24.0)
        } else {
            val p = fixPos ?: return
            val b = fixBearing
            var q = if (b != null && speed > 0.0) Geo.destination(p, b, speed * dtPred) else p
            q = offset(q, errE * decay, errN * decay)
            pos = q
            targetBearing = b
        }
        val tb = targetBearing ?: return
        if (!hasBearing) {
            bearing = tb
            hasBearing = true
        } else if (fdt > 0.0) {
            val k = 1.0 - exp(-fdt / tauBearing)
            bearing = norm360(bearing + Geo.signedAngle(bearing, tb) * k)
        }
    }

    /** true = es bewegt sich nichts mehr sichtbar, es muessen keine weiteren Bilder berechnet werden. */
    fun isSettled(t: Double): Boolean {
        if (fixT.isNaN()) return true
        val since = t - fixT
        if (speed > 0.0 && since < maxPredict) return false
        if (since < 5 * tauErr) {
            val residual = if (line != null) abs(errAlong) else abs(errE) + abs(errN)
            if (residual * exp(-since / tauErr) > 0.05) return false
        }
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
