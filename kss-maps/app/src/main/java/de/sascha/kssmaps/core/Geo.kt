package de.sascha.kssmaps.core

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Ein Punkt in WGS84. Bewusst ohne Android-Abhaengigkeit, damit alles hier auf der JVM testbar ist. */
data class LatLon(val lat: Double, val lon: Double) {
    fun fmt(): String = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)
}

data class BBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
    fun contains(p: LatLon) = p.lat in minLat..maxLat && p.lon in minLon..maxLon

    fun expanded(meters: Double): BBox {
        val dLat = meters / 111_320.0
        val dLon = meters / (111_320.0 * cos(Geo.toRad((minLat + maxLat) / 2)).coerceAtLeast(0.01))
        return BBox(minLat - dLat, minLon - dLon, maxLat + dLat, maxLon + dLon)
    }

    fun intersects(o: BBox) = !(o.minLat > maxLat || o.maxLat < minLat || o.minLon > maxLon || o.maxLon < minLon)

    companion object {
        fun of(points: Iterable<LatLon>): BBox? {
            var minLat = Double.MAX_VALUE; var minLon = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
            var any = false
            for (p in points) {
                any = true
                if (p.lat < minLat) minLat = p.lat
                if (p.lat > maxLat) maxLat = p.lat
                if (p.lon < minLon) minLon = p.lon
                if (p.lon > maxLon) maxLon = p.lon
            }
            return if (any) BBox(minLat, minLon, maxLat, maxLon) else null
        }

        fun around(p: LatLon, meters: Double) = BBox(p.lat, p.lon, p.lat, p.lon).expanded(meters)
    }
}

object Geo {
    private const val R = 6371008.8
    private const val M_PER_DEG = R * PI / 180.0

    fun toRad(d: Double) = d * PI / 180.0
    fun toDeg(r: Double) = r * 180.0 / PI

    fun distance(a: LatLon, b: LatLon): Double = distance(a.lat, a.lon, b.lat, b.lon)

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = toRad(lat2 - lat1)
        val dLon = toRad(lon2 - lon1)
        val s = sin(dLat / 2).pow(2) + cos(toRad(lat1)) * cos(toRad(lat2)) * sin(dLon / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(s)))
    }

    /** Kurs von a nach b in Grad (0 = Nord, im Uhrzeigersinn). */
    fun bearing(a: LatLon, b: LatLon): Double {
        val f1 = toRad(a.lat)
        val f2 = toRad(b.lat)
        val dl = toRad(b.lon - a.lon)
        val y = sin(dl) * cos(f2)
        val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
        return (toDeg(atan2(y, x)) + 360.0) % 360.0
    }

    /** Kleinster Winkel zwischen zwei Kursen, 0..180. */
    fun angleDiff(a: Double, b: Double): Double = abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0)

    /** Vorzeichenbehafteter Winkel von a nach b, -180..180 (positiv = rechts herum). */
    fun signedAngle(from: Double, to: Double): Double = ((to - from) % 360.0 + 540.0) % 360.0 - 180.0

    fun destination(p: LatLon, bearingDeg: Double, dist: Double): LatLon {
        val d = dist / R
        val br = toRad(bearingDeg)
        val f1 = toRad(p.lat)
        val l1 = toRad(p.lon)
        val f2 = asin(sin(f1) * cos(d) + cos(f1) * sin(d) * cos(br))
        val l2 = l1 + atan2(sin(br) * sin(d) * cos(f1), cos(d) - sin(f1) * sin(f2))
        return LatLon(toDeg(f2), ((toDeg(l2) + 540.0) % 360.0) - 180.0)
    }

    fun lerp(a: LatLon, b: LatLon, t: Double) = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    data class SegProj(val t: Double, val dist: Double)

    /**
     * Lot von p auf die Strecke a-b in lokaler, flacher Naeherung (bei Abstaenden unter
     * einigen Kilometern genauer als jedes GPS). t = 0..1 entlang der Strecke.
     */
    fun projectToSegment(p: LatLon, a: LatLon, b: LatLon): SegProj {
        val kx = cos(toRad(p.lat)) * M_PER_DEG
        val ky = M_PER_DEG
        val ax = (a.lon - p.lon) * kx
        val ay = (a.lat - p.lat) * ky
        val bx = (b.lon - p.lon) * kx
        val by = (b.lat - p.lat) * ky
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        var t = if (len2 > 1e-12) -(ax * dx + ay * dy) / len2 else 0.0
        if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
        val cx = ax + t * dx
        val cy = ay + t * dy
        return SegProj(t, sqrt(cx * cx + cy * cy))
    }

    /** Dekodiert eine "encoded polyline" (Valhalla: Praezision 6, Google/OSRM: 5). */
    fun decodePolyline(encoded: String, precision: Int = 6): List<LatLon> {
        val factor = 10.0.pow(precision)
        val out = ArrayList<LatLon>(encoded.length / 4 + 1)
        var index = 0
        var lat = 0L
        var lon = 0L
        val n = encoded.length
        while (index < n) {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                if (index >= n) return out
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
            result = 0L
            shift = 0
            do {
                if (index >= n) return out
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
            out.add(LatLon(lat / factor, lon / factor))
        }
        return out
    }

    fun encodePolyline(points: List<LatLon>, precision: Int = 6): String {
        val factor = 10.0.pow(precision)
        val sb = StringBuilder(points.size * 8)
        var pLat = 0L
        var pLon = 0L
        for (p in points) {
            val la = (p.lat * factor).roundToLong()
            val lo = (p.lon * factor).roundToLong()
            encodeValue(la - pLat, sb)
            encodeValue(lo - pLon, sb)
            pLat = la
            pLon = lo
        }
        return sb.toString()
    }

    private fun encodeValue(v: Long, sb: StringBuilder) {
        var x = if (v < 0) (v shl 1).inv() else (v shl 1)
        while (x >= 0x20) {
            sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar())
            x = x shr 5
        }
        sb.append((x.toInt() + 63).toChar())
    }

    /** Douglas-Peucker, iterativ (keine Rekursionstiefe bei langen Routen). */
    fun simplify(points: List<LatLon>, toleranceM: Double): List<LatLon> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayList<IntArray>()
        stack.add(intArrayOf(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val range = stack.removeAt(stack.size - 1)
            val s = range[0]
            val e = range[1]
            var maxD = 0.0
            var idx = -1
            for (i in s + 1 until e) {
                val d = projectToSegment(points[i], points[s], points[e]).dist
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (idx >= 0 && maxD > toleranceM) {
                keep[idx] = true
                stack.add(intArrayOf(s, idx))
                stack.add(intArrayOf(idx, e))
            }
        }
        val out = ArrayList<LatLon>()
        for (i in points.indices) if (keep[i]) out.add(points[i])
        return out
    }

    /** Fuegt Zwischenpunkte ein, sodass kein Abstand groesser als stepM ist. */
    fun densify(points: List<LatLon>, stepM: Double): List<LatLon> {
        if (points.size < 2) return points
        val out = ArrayList<LatLon>()
        out.add(points[0])
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val d = distance(a, b)
            val n = floor(d / stepM).toInt()
            for (k in 1..n) {
                val t = k * stepM / d
                if (t < 1.0) out.add(lerp(a, b, t))
            }
            out.add(b)
        }
        return out
    }

    fun lineLength(points: List<LatLon>): Double {
        var s = 0.0
        for (i in 1 until points.size) s += distance(points[i - 1], points[i])
        return s
    }

    fun pointInRing(p: LatLon, ring: List<LatLon>): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.lat > p.lat) != (b.lat > p.lat)) {
                val x = (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon
                if (p.lon < x) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Abstand eines Punktes zum Rand eines Rings (0, wenn innerhalb). */
    fun distanceToRing(p: LatLon, ring: List<LatLon>): Double {
        if (ring.isEmpty()) return Double.MAX_VALUE
        if (pointInRing(p, ring)) return 0.0
        var best = Double.MAX_VALUE
        for (i in 0 until ring.size - 1) best = min(best, projectToSegment(p, ring[i], ring[i + 1]).dist)
        if (ring.size >= 2) best = min(best, projectToSegment(p, ring[ring.size - 1], ring[0]).dist)
        return best
    }

    /** Flaeche in m² (lokal flach). */
    fun ringArea(ring: List<LatLon>): Double {
        if (ring.size < 3) return 0.0
        val lat0 = ring[0].lat
        val kx = cos(toRad(lat0)) * M_PER_DEG
        var s = 0.0
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            s += (a.lon * kx) * (b.lat * M_PER_DEG) - (b.lon * kx) * (a.lat * M_PER_DEG)
        }
        return abs(s) / 2.0
    }

    fun ringCentroid(ring: List<LatLon>): LatLon {
        if (ring.isEmpty()) return LatLon(0.0, 0.0)
        var a = 0.0
        var cx = 0.0
        var cy = 0.0
        val n = ring.size
        for (i in 0 until n) {
            val p = ring[i]
            val q = ring[(i + 1) % n]
            val f = p.lon * q.lat - q.lon * p.lat
            a += f
            cx += (p.lon + q.lon) * f
            cy += (p.lat + q.lat) * f
        }
        if (abs(a) < 1e-14) {
            return LatLon(ring.sumOf { it.lat } / n, ring.sumOf { it.lon } / n)
        }
        a *= 0.5
        return LatLon(cy / (6 * a), cx / (6 * a))
    }

    /** Achsparalleles Quadrat um p mit halber Kantenlaenge halfM, als geschlossener Ring. */
    fun squareRing(p: LatLon, halfM: Double): List<LatLon> {
        val b = BBox.around(p, halfM)
        return listOf(
            LatLon(b.minLat, b.minLon), LatLon(b.minLat, b.maxLon),
            LatLon(b.maxLat, b.maxLon), LatLon(b.maxLat, b.minLon), LatLon(b.minLat, b.minLon)
        )
    }
}

data class Projection(
    /** Index des Segments (Punkt seg -> seg+1). */
    val seg: Int,
    val t: Double,
    /** Strecke vom Routenanfang bis zum Lotfusspunkt in Metern. */
    val along: Double,
    /** Seitlicher Abstand zur Linie in Metern. */
    val offset: Double,
    val point: LatLon,
)

/** Linienzug mit kumulierten Distanzen und einem Raster-Index fuer schnelle Naehe-Abfragen. */
class Polyline(val points: List<LatLon>) {
    val cum: DoubleArray = DoubleArray(points.size)

    init {
        for (i in 1 until points.size) cum[i] = cum[i - 1] + Geo.distance(points[i - 1], points[i])
    }

    val length: Double get() = if (cum.isEmpty()) 0.0 else cum[cum.size - 1]
    val size: Int get() = points.size

    /** Segment i mit cum[i] <= d < cum[i+1]. */
    fun segmentAt(d: Double): Int {
        if (points.size < 2 || d <= 0.0) return 0
        if (d >= length) return points.size - 2
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= d) lo = mid else hi = mid
        }
        return lo
    }

    fun pointAt(d: Double): LatLon {
        if (points.isEmpty()) return LatLon(0.0, 0.0)
        if (points.size == 1) return points[0]
        val i = segmentAt(d)
        val segLen = cum[i + 1] - cum[i]
        val t = if (segLen > 0) ((d - cum[i]) / segLen).coerceIn(0.0, 1.0) else 0.0
        return Geo.lerp(points[i], points[i + 1], t)
    }

    fun bearingAt(d: Double, span: Double = 30.0): Double {
        if (points.size < 2) return 0.0
        val a = pointAt(max(0.0, d - span / 2))
        val b = pointAt(min(length, d + span / 2))
        return if (Geo.distance(a, b) < 0.5) {
            val i = segmentAt(d)
            Geo.bearing(points[i], points[i + 1])
        } else Geo.bearing(a, b)
    }

    /** Lot auf den Linienzug, nur Segmente fromSeg..toSeg. */
    fun project(p: LatLon, fromSeg: Int = 0, toSeg: Int = points.size - 2): Projection? {
        if (points.isEmpty()) return null
        if (points.size == 1) return Projection(0, 0.0, 0.0, Geo.distance(p, points[0]), points[0])
        val s = fromSeg.coerceIn(0, points.size - 2)
        val e = toSeg.coerceIn(s, points.size - 2)
        var best: Projection? = null
        var bestD = Double.MAX_VALUE
        for (i in s..e) {
            val sp = Geo.projectToSegment(p, points[i], points[i + 1])
            if (sp.dist < bestD) {
                bestD = sp.dist
                val segLen = cum[i + 1] - cum[i]
                best = Projection(i, sp.t, cum[i] + sp.t * segLen, sp.dist, Geo.lerp(points[i], points[i + 1], sp.t))
            }
        }
        return best
    }

    // ---- Raster-Index: Zelle -> Segmente. Zellgroesse ~550 m. ----
    private val cellLat = 0.005
    private val cellLon = 0.0075
    private val grid: HashMap<Long, IntArray> by lazy { buildGrid() }

    private fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

    private fun buildGrid(): HashMap<Long, IntArray> {
        val tmp = HashMap<Long, ArrayList<Int>>()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val x0 = floor(min(a.lon, b.lon) / cellLon).toInt()
            val x1 = floor(max(a.lon, b.lon) / cellLon).toInt()
            val y0 = floor(min(a.lat, b.lat) / cellLat).toInt()
            val y1 = floor(max(a.lat, b.lat) / cellLat).toInt()
            for (x in x0..x1) for (y in y0..y1) tmp.getOrPut(cellKey(x, y)) { ArrayList(4) }.add(i)
        }
        val out = HashMap<Long, IntArray>(tmp.size * 2)
        for ((k, v) in tmp) out[k] = v.toIntArray()
        return out
    }

    /** Naechster Lotfusspunkt innerhalb maxDist (Meter, sinnvoll bis ~500 m), sonst null. */
    fun projectNear(p: LatLon, maxDist: Double): Projection? {
        if (points.size < 2) return project(p)?.takeIf { it.offset <= maxDist }
        val cx = floor(p.lon / cellLon).toInt()
        val cy = floor(p.lat / cellLat).toInt()
        val r = if (maxDist > 500) 2 else 1
        var best: Projection? = null
        var bestD = maxDist
        for (x in cx - r..cx + r) for (y in cy - r..cy + r) {
            val segs = grid[cellKey(x, y)] ?: continue
            for (i in segs) {
                val sp = Geo.projectToSegment(p, points[i], points[i + 1])
                if (sp.dist <= bestD) {
                    bestD = sp.dist
                    val segLen = cum[i + 1] - cum[i]
                    best = Projection(i, sp.t, cum[i] + sp.t * segLen, sp.dist, Geo.lerp(points[i], points[i + 1], sp.t))
                }
            }
        }
        return best
    }

    /** Alle Lotfusspunkte innerhalb maxDist, je Segmentgruppe der beste (fuer Strecken, die man zweimal faehrt). */
    fun projectAllNear(p: LatLon, maxDist: Double): List<Projection> {
        if (points.size < 2) return listOfNotNull(projectNear(p, maxDist))
        val cx = floor(p.lon / cellLon).toInt()
        val cy = floor(p.lat / cellLat).toInt()
        val found = ArrayList<Projection>()
        val seen = HashSet<Int>()
        for (x in cx - 1..cx + 1) for (y in cy - 1..cy + 1) {
            val segs = grid[cellKey(x, y)] ?: continue
            for (i in segs) {
                if (!seen.add(i)) continue
                val sp = Geo.projectToSegment(p, points[i], points[i + 1])
                if (sp.dist <= maxDist) {
                    val segLen = cum[i + 1] - cum[i]
                    found.add(Projection(i, sp.t, cum[i] + sp.t * segLen, sp.dist, Geo.lerp(points[i], points[i + 1], sp.t)))
                }
            }
        }
        // Treffer, die entlang der Route dicht beieinander liegen, sind derselbe Durchgang.
        found.sortBy { it.along }
        val out = ArrayList<Projection>()
        for (f in found) {
            val last = out.lastOrNull()
            if (last != null && f.along - last.along < 150.0) {
                if (f.offset < last.offset) out[out.size - 1] = f
            } else out.add(f)
        }
        return out
    }

    fun slice(from: Double, to: Double): List<LatLon> {
        if (points.size < 2) return points
        val a = from.coerceIn(0.0, length)
        val b = to.coerceIn(a, length)
        val out = ArrayList<LatLon>()
        out.add(pointAt(a))
        val i0 = segmentAt(a) + 1
        val i1 = segmentAt(b)
        for (i in i0..i1) if (cum[i] > a && cum[i] < b) out.add(points[i])
        out.add(pointAt(b))
        return out
    }

    fun bbox(): BBox? = BBox.of(points)
}
