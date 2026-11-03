package de.sascha.kssmaps.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Verfolgt die Position entlang einer Route: wo bin ich (along), was ist das naechste Manoever,
 * bin ich von der Route abgekommen? Reine Logik, wird mit jedem GPS-Fix gefuettert.
 */
class RouteTracker(val route: Route, startAlong: Double = 0.0) {

    data class State(
        val along: Double,
        val offset: Double,
        val snapped: LatLon,
        val routeBearing: Double,
        val onRoute: Boolean,
        val offRoute: Boolean,
        val wrongWay: Boolean,
        /** Index in route.maneuvers des naechsten wichtigen Manoevers (-1 = keins). */
        val nextIdx: Int,
        val distToNext: Double,
        /** Index des Manoevers danach (-1 = keins). */
        val afterIdx: Int,
        val remainingM: Double,
        val remainingSec: Double,
        val legIndex: Int,
        val distToLegEnd: Double,
        val arrived: Boolean,
    )

    var along: Double = startAlong
        private set
    private var initialized = startAlong > 0.0
    private var offCount = 0
    private var wrongCount = 0

    fun update(p: LatLon, accuracyM: Double, speedMps: Double, headingDeg: Double?): State {
        val g = route.geometry
        val tol = max(35.0, min(accuracyM * 1.5, 80.0))
        val proj: Projection? = if (!initialized) {
            // Eine neue Route beginnt am eigenen Standort: bei Schleifen den ersten Durchgang nehmen
            g.projectAllNear(p, 60.0).minByOrNull { it.along } ?: g.projectNear(p, 300.0) ?: g.project(p)
        } else {
            val ahead = along + max(350.0, speedMps * 15.0 + 200.0)
            // Faehrt die Route eine Schleife (z. B. um den Block zum Ziel), liegt ein spaeterer Teil
            // oft genauso nah wie der aktuelle. Unter den nahen Treffern den nehmen, der zur
            // erwarteten Lage passt – sonst springt die Position (und die Ansage) vor.
            val expected = along + speedMps
            g.projectAllNear(p, tol)
                .filter { it.along >= along - 60.0 && it.along <= ahead }
                .minByOrNull { it.offset + 0.25 * abs(it.along - expected) }
                ?: g.project(p, g.segmentAt(along - 60.0), g.segmentAt(ahead))
        }
        var onRoute = false
        if (proj != null && proj.offset <= tol) {
            onRoute = true
            offCount = 0
            // Nur in Fahrtrichtung weiter; kleine Rueckspruenge (GPS-Rauschen) ignorieren.
            if (!initialized || proj.along > along - 15.0) along = max(if (initialized) along - 15.0 else 0.0, proj.along)
            initialized = true
        } else {
            offCount++
        }
        val routeBearing = g.bearingAt(along)
        if (onRoute && headingDeg != null && speedMps > 4.0 && Geo.angleDiff(headingDeg, routeBearing) > 120.0) {
            wrongCount++
        } else if (onRoute) {
            wrongCount = 0
        }
        val offRoute = (offCount >= 3 && (proj == null || proj.offset > tol)) || wrongCount >= 4

        var nextIdx = -1
        for ((i, m) in route.maneuvers.withIndex()) {
            if (m.along > along + 3.0 && (m.kind.significant)) {
                nextIdx = i
                break
            }
        }
        val distToNext = if (nextIdx >= 0) route.maneuvers[nextIdx].along - along else route.distanceM - along
        var afterIdx = -1
        if (nextIdx >= 0) {
            for (i in nextIdx + 1 until route.maneuvers.size) {
                if (route.maneuvers[i].kind.significant) {
                    afterIdx = i
                    break
                }
            }
        }
        val remainingM = max(0.0, g.length - along)
        val leg = route.legIndexAt(along)
        val legEnd = route.legEnds.getOrNull(leg) ?: g.length
        val dest = g.points.lastOrNull()
        val arrived = remainingM < 25.0 || (dest != null && remainingM < 120.0 && Geo.distance(p, dest) < 30.0)
        return State(
            along = along,
            offset = proj?.offset ?: Double.MAX_VALUE,
            snapped = if (onRoute) g.pointAt(along) else p,
            routeBearing = routeBearing,
            onRoute = onRoute,
            offRoute = offRoute,
            wrongWay = wrongCount >= 4,
            nextIdx = nextIdx,
            distToNext = distToNext,
            afterIdx = afterIdx,
            remainingM = remainingM,
            remainingSec = route.remainingSec(along),
            legIndex = leg,
            distToLegEnd = max(0.0, legEnd - along),
            arrived = arrived,
        )
    }

    /** Nach einem Reroute keine sofortige neue Abweichung melden. */
    fun resetOffRoute() {
        offCount = 0
        wrongCount = 0
    }
}

/**
 * Wann wird ein Manoever angesagt? Drei Stufen je nach Tempo:
 * frueh (Vorankuendigung), nah, und "jetzt".
 */
object AnnouncePlan {
    enum class Stage { FAR, NEAR, NOW }

    fun thresholds(speedMps: Double, onMotorway: Boolean): Triple<Double, Double, Double> {
        val kmh = speedMps * 3.6
        return when {
            onMotorway || kmh > 85 -> Triple(2000.0, 800.0, max(120.0, speedMps * 6.0))
            kmh > 55 -> Triple(1000.0, 400.0, max(70.0, speedMps * 5.0))
            else -> Triple(400.0, 150.0, max(35.0, speedMps * 4.0))
        }
    }

    /** Welche Stufe ist bei dieser Entfernung faellig? null = noch nichts. */
    fun stageFor(dist: Double, speedMps: Double, onMotorway: Boolean): Stage? {
        val (far, near, now) = thresholds(speedMps, onMotorway)
        return when {
            dist <= now -> Stage.NOW
            dist <= near -> Stage.NEAR
            dist <= far && dist > near + 150 -> Stage.FAR
            else -> null
        }
    }

    /** Warnabstand fuer Meldungen. */
    fun hazardWarnDistance(type: HazardType, speedMps: Double): Double {
        val kmh = speedMps * 3.6
        return when (type) {
            HazardType.SPEED_CAMERA, HazardType.DISTANCE_CHECK -> when {
                kmh > 90 -> 1000.0
                kmh > 55 -> 600.0
                else -> 300.0
            }
            HazardType.JAM, HazardType.SLOW_TRAFFIC -> if (kmh > 70) 3000.0 else 1200.0
            HazardType.CLOSURE -> 4000.0
            HazardType.ROADWORKS -> if (kmh > 70) 1500.0 else 600.0
            else -> if (kmh > 70) 2000.0 else 800.0
        }
    }
}
