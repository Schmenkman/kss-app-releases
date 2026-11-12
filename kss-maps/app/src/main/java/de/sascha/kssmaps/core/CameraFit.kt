package de.sascha.kssmaps.core

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh

/**
 * Kamera fuer eine Uebersicht (Routenvorschau, Alternativen waehrend der Fahrt): alle Punkte in den
 * freien Teil der Karte, genordet und flach von oben.
 *
 * Selbst gerechnet statt MapLibre "newLatLngBounds": das uebernimmt Neigung und Drehung der
 * Fahransicht (50° geneigt). Weit entfernte Punkte liegen dann hinter dem Horizont, ihre
 * Bildschirm-Koordinaten werden unsinnig – und die Uebersicht landet an einem ganz anderen Ort.
 */
object CameraFit {
    data class Result(val lat: Double, val lon: Double, val zoom: Double)

    /** Web-Mercator 0..1 (wie die Kartenkacheln). */
    private fun mx(lon: Double) = (lon + 180.0) / 360.0

    private fun my(lat: Double): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-85.0, 85.0)))
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    private fun latOf(y: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y))))

    /**
     * width/height: Kartengroesse in Pixeln; pad: Raender links, oben, rechts, unten (Pixel);
     * density: Pixel je dp – MapLibre rechnet mit 512 dp breiten Kacheln.
     * null, wenn kein gueltiger Punkt dabei ist.
     */
    fun fit(
        points: List<LatLon>,
        width: Double,
        height: Double,
        pad: DoubleArray,
        density: Double,
        minZoom: Double = 3.0,
        maxZoom: Double = 17.0,
    ): Result? {
        if (width <= 0 || height <= 0 || density <= 0) return null
        var x0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE
        var y0 = Double.MAX_VALUE
        var y1 = -Double.MAX_VALUE
        var n = 0
        for (p in points) {
            if (p.lat.isNaN() || p.lon.isNaN() || p.lat !in -85.0..85.0 || p.lon !in -180.0..180.0) continue
            // 0/0 kommt nur aus kaputten Daten (liegt im Golf von Guinea)
            if (p.lat == 0.0 && p.lon == 0.0) continue
            val x = mx(p.lon)
            val y = my(p.lat)
            x0 = min(x0, x)
            x1 = max(x1, x)
            y0 = min(y0, y)
            y1 = max(y1, y)
            n++
        }
        if (n == 0) return null
        // Ist der freie Bereich (zu) schmal, wenigstens ein Viertel der Karte nehmen
        val w = max(width - pad.getOrElse(0) { 0.0 } - pad.getOrElse(2) { 0.0 }, width * 0.25) / density
        val h = max(height - pad.getOrElse(1) { 0.0 } - pad.getOrElse(3) { 0.0 }, height * 0.25) / density
        val dx = max(x1 - x0, 1e-12)
        val dy = max(y1 - y0, 1e-12)
        val zoom = log2(min(w / (dx * 512.0), h / (dy * 512.0))).coerceIn(minZoom, maxZoom)
        return Result(latOf((y0 + y1) / 2), (x0 + x1) / 2 * 360.0 - 180.0, zoom)
    }
}
