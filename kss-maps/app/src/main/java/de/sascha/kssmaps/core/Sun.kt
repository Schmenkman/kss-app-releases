package de.sascha.kssmaps.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** Sonnenstand (NOAA-Naeherung, auf ~1 Grad genau) – fuer den automatischen Nachtmodus. */
object Sun {
    /** Sonnenhoehe in Grad ueber dem Horizont. */
    fun elevation(lat: Double, lon: Double, timeMillis: Long): Double {
        val jd = timeMillis / 86_400_000.0 + 2440587.5
        val n = jd - 2451545.0
        val l = (280.460 + 0.9856474 * n) % 360.0
        val g = Math.toRadians((357.528 + 0.9856003 * n) % 360.0)
        val lambda = Math.toRadians(l + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val eps = Math.toRadians(23.439 - 0.0000004 * n)
        val decl = asin(sin(eps) * sin(lambda))
        val ra = Math.atan2(cos(eps) * sin(lambda), cos(lambda))
        val gmst = (18.697374558 + 24.06570982441908 * n) % 24.0
        val lst = Math.toRadians(gmst * 15.0 + lon)
        val ha = lst - ra
        val phi = Math.toRadians(lat)
        val el = asin(sin(phi) * sin(decl) + cos(phi) * cos(decl) * cos(ha))
        return el * 180.0 / PI
    }

    /** Nacht = Sonne tiefer als 3 Grad unter dem Horizont (buergerliche Daemmerung beginnt). */
    fun isNight(lat: Double, lon: Double, timeMillis: Long = System.currentTimeMillis()): Boolean =
        elevation(lat, lon, timeMillis) < -3.0
}
