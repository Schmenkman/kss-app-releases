package de.sascha.kssmaps.core

import kotlin.math.max

/**
 * Stillstand erkennen (Couch, Parkplatz, Ampel). Im Stand "wandert" die GPS-Position um einige
 * Meter, und das GPS meldet oft 1–5 km/h, obwohl sich nichts bewegt – besonders drinnen bei
 * schlechter Genauigkeit. Solange alle Punkte der letzten Sekunden innerhalb der Messgenauigkeit
 * beieinander liegen und das Tempo klein (bzw. bei schlechter Genauigkeit unglaubwuerdig) ist,
 * gilt das Geraet als stehend: Tempo 0, Pfeil fest am Ankerpunkt, Fahrtrichtung bleibt.
 * Losfahren wird sofort erkannt: deutlich aus dem Genauigkeitskreis heraus oder echtes Tempo bei
 * guter Genauigkeit.
 *
 * Zeiten in Sekunden (monoton).
 */
class StillDetector(
    private val windowSec: Double = 6.0,
    private val minSpanSec: Double = 3.0,
) {
    private data class Fix(val t: Double, val p: LatLon, val acc: Double, val speed: Double)

    private val recent = ArrayDeque<Fix>()

    var stationary = false
        private set
    /** Fester Punkt, solange [stationary]. */
    var anchor: LatLon? = null
        private set
    private var anchorAcc = 0.0

    fun reset() {
        recent.clear()
        stationary = false
        anchor = null
    }

    /** Neuer GPS-Punkt; speedMps = gemeldetes/berechnetes Tempo. Gibt [stationary] zurueck. */
    fun update(t: Double, p: LatLon, accuracyM: Double, speedMps: Double): Boolean {
        val acc = accuracyM.coerceIn(3.0, 500.0)
        recent.addLast(Fix(t, p, acc, speedMps))
        while (recent.size > 1 && t - recent.first().t > windowSec) recent.removeFirst()
        val a = anchor
        if (stationary && a != null) {
            val d = Geo.distance(a, p)
            val reach = max(15.0, 2.0 * max(acc, anchorAcc))
            // Losgefahren: weit aus dem Genauigkeitskreis, oder echtes Tempo bei guter Genauigkeit
            val last2 = recent.takeLast(2)
            val moving = d > reach || (speedMps > 2.5 && acc <= 15.0) ||
                (speedMps > 1.2 && acc <= 8.0 && last2.size == 2 && last2.all { it.speed > 1.2 && it.acc <= 8.0 }) ||
                // auch bei schlechter Genauigkeit: zweimal hintereinander ueber 14 km/h ist kein Rauschen
                (speedMps > 4.0 && last2.size == 2 && last2.all { it.speed > 4.0 })
            if (moving) {
                stationary = false
                anchor = null
            } else if (acc < anchorAcc * 0.6) {
                // deutlich genauerer Punkt im Stand: Anker dorthin verbessern (einmaliger kleiner Ruck)
                anchor = p
                anchorAcc = acc
            }
            return stationary
        }
        val first = recent.first()
        val span = t - first.t
        // Tempo im Stand: klein – je schlechter die Genauigkeit (drinnen), desto mehr "Rausch-Tempo"
        // ist unglaubwuerdig (bis 7 km/h). Median statt "alle", damit ein Ausreisser nicht stoert.
        val speeds = recent.map { it.speed }.sorted()
        val accMedian = recent.map { it.acc }.sorted()[recent.size / 2]
        val limit = when {
            accMedian > 12.0 -> 2.0
            accMedian > 6.0 -> 1.2
            else -> 0.8
        }
        val slow = speeds[speeds.size / 2] < limit && speeds.last() < 2 * limit
        val radius = max(10.0, 1.5 * acc)
        val close = recent.all { Geo.distance(it.p, p) < radius }
        if (span >= minSpanSec && slow && close) {
            stationary = true
            // Anker: der genaueste Punkt des Fensters
            val best = recent.minByOrNull { it.acc } ?: recent.last()
            anchor = best.p
            anchorAcc = best.acc
        }
        return stationary
    }
}
