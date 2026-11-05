package de.sascha.kssmaps.core

import kotlin.math.max
import kotlin.math.min

/**
 * Ordnet Meldungen (Blitzer, Stau, Baustellen …) der Route zu. Nur was wirklich AUF der
 * gefahrenen Strecke liegt, kommt durch – nicht, was nur in der Naehe ist (Parallelstrasse,
 * Bruecke darueber, Gegenfahrbahn).
 */
object HazardMatcher {

    /** Blitzer stehen am Fahrbahnrand: 25 m reichen, die Gegenfahrbahn einer Autobahn liegt meist weiter weg. */
    const val CAMERA_MAX_OFFSET = 25.0
    const val POINT_MAX_OFFSET = 40.0
    const val LINE_MAX_OFFSET = 45.0
    /** Liegt eine Linienmeldung im Mittel weiter weg, ist es die Gegenfahrbahn oder eine Nachbarstrasse. */
    const val LINE_MAX_MEDIAN_OFFSET = 22.0
    /** Liegt eine Meldung genau auf der Route, zaehlt ihre Punktreihenfolge nicht (Gegenrichtung wird nur daneben geprueft). */
    const val OPPOSITE_MIN_OFFSET = 7.0

    fun matchAll(route: Route, hazards: List<Hazard>): List<RouteHazard> {
        val out = ArrayList<RouteHazard>()
        val bbox = route.geometry.bbox()?.expanded(200.0) ?: return emptyList()
        for (h in hazards) {
            val inBox = bbox.contains(h.point) || h.geometry.any { bbox.contains(it) }
            if (!inBox) continue
            out.addAll(match(route.geometry, h))
        }
        out.sortBy { it.startAlong }
        return out
    }

    fun match(g: Polyline, h: Hazard): List<RouteHazard> =
        if (h.geometry.size >= 2) listOfNotNull(matchLine(g, h)) else matchPoint(g, h)

    private fun matchPoint(g: Polyline, h: Hazard): List<RouteHazard> {
        val maxOff = if (h.type.isEnforcement) CAMERA_MAX_OFFSET else POINT_MAX_OFFSET
        val out = ArrayList<RouteHazard>()
        for (pr in g.projectAllNear(h.point, maxOff)) {
            val dir = h.directionDeg
            if (dir != null && Geo.angleDiff(g.bearingAt(pr.along), dir) > 70.0) continue
            out.add(RouteHazard(h, pr.along, pr.along))
        }
        return out
    }

    private fun matchLine(g: Polyline, h: Hazard): RouteHazard? {
        val samples = Geo.densify(h.geometry, 40.0)
        if (samples.isEmpty()) return null
        val alongs = ArrayList<Double>()
        val offsets = ArrayList<Double>()
        var same = 0
        var opposite = 0
        for ((i, s) in samples.withIndex()) {
            val pr = g.projectNear(s, LINE_MAX_OFFSET) ?: continue
            alongs.add(pr.along)
            offsets.add(pr.offset)
            // Richtung der Meldung an dieser Stelle gegen die Fahrtrichtung der Route
            val a = samples.getOrNull(if (i + 1 < samples.size) i else i - 1)
            val b = samples.getOrNull(if (i + 1 < samples.size) i + 1 else i)
            if (a != null && b != null && Geo.distance(a, b) > 5.0) {
                val diff = Geo.angleDiff(Geo.bearing(a, b), g.bearingAt(pr.along))
                if (diff < 60.0) same++ else if (diff > 120.0) opposite++
            }
        }
        if (alongs.isEmpty()) return null
        val ratio = alongs.size.toDouble() / samples.size
        // Kurze Meldungen (z. B. 1-2 Punkte) duerfen ganz passen; laengere muessen ueberwiegend auf der Route liegen.
        if (samples.size > 3 && ratio < 0.5) return null
        offsets.sort()
        val median = offsets[offsets.size / 2]
        if (median > LINE_MAX_MEDIAN_OFFSET) return null
        // Gegenfahrbahn: liegt neben der Route und zeigt ueberwiegend in die Gegenrichtung
        if (median > OPPOSITE_MIN_OFFSET && opposite >= 2 && opposite > 2 * same) return null
        // Ausreisser (Route kreuzt die Meldung nur) abschneiden: zusammenhaengenden Bereich nehmen.
        alongs.sort()
        var bestStart = alongs[0]
        var bestEnd = alongs[0]
        var curStart = alongs[0]
        var prev = alongs[0]
        for (i in 1 until alongs.size) {
            val a = alongs[i]
            if (a - prev > 300.0) {
                if (prev - curStart > bestEnd - bestStart) {
                    bestStart = curStart
                    bestEnd = prev
                }
                curStart = a
            }
            prev = a
        }
        if (prev - curStart > bestEnd - bestStart) {
            bestStart = curStart
            bestEnd = prev
        }
        val hazardLen = Geo.lineLength(h.geometry)
        if (hazardLen > 400.0 && bestEnd - bestStart < 0.25 * hazardLen && samples.size > 6) return null
        return RouteHazard(h, bestStart, max(bestEnd, bestStart))
    }

    /**
     * Doppelte Meldungen zusammenfassen: dieselbe Art an (fast) derselben Stelle – z. B. eine
     * Baustelle, die die Autobahn-API zweimal fuehrt, oder Blitzer-Knoten + Relation.
     */
    fun dedupe(list: List<RouteHazard>): List<RouteHazard> {
        val out = ArrayList<RouteHazard>()
        for (rh in list.sortedBy { it.startAlong }) {
            val tol = if (rh.hazard.type.isEnforcement) 40.0 else 250.0
            val dup = out.indexOfLast { o ->
                o.hazard.type == rh.hazard.type && kotlin.math.abs(o.startAlong - rh.startAlong) < tol &&
                    (rh.hazard.type.isEnforcement || kotlin.math.abs(o.endAlong - rh.endAlong) < 400.0)
            }
            if (dup >= 0) {
                val o = out[dup]
                // die laengere/ausfuehrlichere behalten, Verzoegerung maximal
                val keep = if (rh.endAlong - rh.startAlong > o.endAlong - o.startAlong) rh else o
                val delay = maxOf(o.hazard.delaySec, rh.hazard.delaySec)
                out[dup] = keep.copy(hazard = keep.hazard.copy(delaySec = delay))
            } else out.add(rh)
        }
        return out
    }

    /** Stau-Verzoegerungen, die (noch) nicht im Zeitprofil der Route stecken. */
    fun extraDelays(route: Route, matched: List<RouteHazard>): List<Pair<Double, Double>> {
        if (route.source == RouteSource.TOMTOM) return emptyList()
        return matched.filter { it.hazard.type.isTraffic && it.hazard.delaySec > 0 }
            .map { it.startAlong to it.hazard.delaySec.toDouble() }
    }

    /**
     * Freie Fahrt ohne Route: Blitzer voraus in Fahrtrichtung (Kegel +-20 Grad, bis maxDist),
     * seitlich hoechstens 30 m vom gedachten Fahrweg entfernt.
     */
    fun camerasAhead(pos: LatLon, heading: Double, cameras: List<Hazard>, maxDist: Double): List<Pair<Hazard, Double>> {
        val out = ArrayList<Pair<Hazard, Double>>()
        val box = BBox.around(pos, maxDist)
        for (c in cameras) {
            if (!box.contains(c.point)) continue
            val d = Geo.distance(pos, c.point)
            if (d > maxDist || d < 15) continue
            val br = Geo.bearing(pos, c.point)
            val diff = Geo.angleDiff(br, heading)
            if (diff > 20.0) continue
            val lateral = d * kotlin.math.sin(Geo.toRad(diff))
            if (lateral > 30.0) continue
            val dir = c.directionDeg
            if (dir != null && Geo.angleDiff(dir, heading) > 70.0) continue
            out.add(c to d)
        }
        out.sortBy { it.second }
        return out
    }

    /** Laenge der Meldung entlang der Route (mind. 1 m, damit Punkte darstellbar sind). */
    fun length(rh: RouteHazard): Double = max(1.0, rh.endAlong - rh.startAlong)

    fun overlaps(rh: RouteHazard, from: Double, to: Double): Boolean = rh.endAlong >= from && rh.startAlong <= to

    fun clampToRoute(rh: RouteHazard, routeLen: Double) =
        rh.copy(startAlong = rh.startAlong.coerceIn(0.0, routeLen), endAlong = min(rh.endAlong, routeLen))
}
