package de.sascha.kssmaps.core

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.math.floor

/**
 * Autobahn- und Schnellstrassen-Abschnitte in Bayern mit Wechselverkehrszeichen (LED-Anzeigen
 * ueber der Fahrbahn) oder zeitlich begrenzten Tempolimits aus OpenStreetMap (beim Bauen geladen,
 * tools/fetch_enforcement.py). Das gerade angezeigte LED-Limit kennt keine freie Datenquelle:
 * dort zeigt die App statt "frei" das Schild "Anzeige beachten" (mit dem festen Hoechstwert, falls
 * es einen gibt). Zeitlich begrenzte Limits ("120 @ (22:00-06:00)") gelten zur passenden Uhrzeit.
 */
class TempoIndex private constructor(private val ways: List<Way>) {

    data class Cond(val kmh: Int, val fromMin: Int, val toMin: Int, val days: Set<DayOfWeek>?)

    class Way(val pts: List<LatLon>, val variable: Boolean, val fixedKmh: Int?, val conditions: List<Cond>, val oneway: Boolean)

    private val cells = HashMap<Long, MutableList<Long>>() // Zelle -> (Weg << 20 | Segment)

    init {
        for ((wi, w) in ways.withIndex()) {
            for (si in 0 until w.pts.size - 1) {
                val a = w.pts[si]
                val b = w.pts[si + 1]
                for (cy in cell(minOf(a.lat, b.lat))..cell(maxOf(a.lat, b.lat)))
                    for (cx in cell(minOf(a.lon, b.lon))..cell(maxOf(a.lon, b.lon)))
                        cells.getOrPut(key(cy, cx)) { ArrayList() }.add((wi.toLong() shl 20) or si.toLong())
            }
        }
    }

    val size: Int get() = ways.size

    /**
     * Besonderheit an dieser Stelle in Fahrtrichtung [heading], sonst null (dann gilt das normale
     * Limit der Route). Nur Abschnitte bis 18 m neben der Position und in gleicher Richtung.
     */
    fun at(p: LatLon, heading: Double?, now: LocalDateTime): SpeedSign? {
        var best: Way? = null
        var bestD = MAX_OFFSET
        for (cy in cell(p.lat) - 1..cell(p.lat) + 1) for (cx in cell(p.lon) - 1..cell(p.lon) + 1) {
            for (code in cells[key(cy, cx)] ?: continue) {
                val w = ways[(code shr 20).toInt()]
                val si = (code and 0xFFFFF).toInt()
                val a = w.pts[si]
                val b = w.pts[si + 1]
                val d = Geo.projectToSegment(p, a, b).dist
                if (d >= bestD) continue
                if (heading != null) {
                    val diff = Geo.angleDiff(Geo.bearing(a, b), heading)
                    if (diff > 40.0 && !(!w.oneway && diff > 140.0)) continue
                }
                best = w
                bestD = d
            }
        }
        val w = best ?: return null
        val minute = now.hour * 60 + now.minute
        val cond = w.conditions.filter { c ->
            (c.days == null || now.dayOfWeek in c.days) &&
                (if (c.fromMin <= c.toMin) minute in c.fromMin until c.toMin else minute >= c.fromMin || minute < c.toMin)
        }.minOfOrNull { it.kmh }
        if (cond != null) return SpeedSign(cond, variable = w.variable)
        if (w.variable) return SpeedSign(w.fixedKmh, variable = true)
        return null
    }

    companion object {
        const val MAX_OFFSET = 18.0
        private const val CELL = 0.01

        private fun cell(v: Double): Int = floor(v / CELL).toInt()
        private fun key(cy: Int, cx: Int): Long = (cy.toLong() shl 32) xor (cx.toLong() and 0xFFFFFFFFL)

        val EMPTY = TempoIndex(emptyList())

        /** Kompakte Datei aus tools/fetch_enforcement.py: {"ways":[{"ms":"none","var":1,"cond":"…","ow":1,"g":[lat,lon,…]}]}. */
        fun parse(json: String): TempoIndex {
            val arr = JSONObject(json).optJSONArray("ways") ?: return EMPTY
            val out = ArrayList<Way>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val g = o.optJSONArray("g") ?: continue
                val pts = ArrayList<LatLon>(g.length() / 2)
                var k = 0
                while (k + 1 < g.length()) {
                    pts.add(LatLon(g.optDouble(k), g.optDouble(k + 1)))
                    k += 2
                }
                if (pts.size < 2) continue
                val ms = o.optString("ms")
                out.add(
                    Way(
                        pts = pts,
                        variable = o.optInt("var", 0) == 1 || ms == "signals",
                        fixedKmh = Regex("^\\d+$").find(ms.trim())?.value?.toIntOrNull(),
                        conditions = parseConditional(o.optString("cond")),
                        oneway = o.optInt("ow", 1) == 1,
                    ),
                )
            }
            return TempoIndex(out)
        }

        /**
         * "120 @ (22:00-06:00); 80 @ wet; 100 @ (Mo-Fr 06:00-19:00)" -> nur die mit Uhrzeit (Naesse,
         * Schnee, LKW … kennt die App nicht).
         */
        fun parseConditional(s: String): List<Cond> {
            if (s.isBlank()) return emptyList()
            val out = ArrayList<Cond>()
            for (part in s.split(';')) {
                val m = Regex("^\\s*(\\d+)\\s*@\\s*\\(?([^)]*)\\)?\\s*$").find(part) ?: continue
                val kmh = m.groupValues[1].toInt()
                val cond = m.groupValues[2]
                val days = Regex("\\b(Mo|Tu|We|Th|Fr|Sa|Su)(?:\\s*-\\s*(Mo|Tu|We|Th|Fr|Sa|Su))?\\b").find(cond)?.let { d ->
                    val order = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
                    val a = order.indexOf(d.groupValues[1])
                    val b = d.groupValues[2].takeIf { it.isNotEmpty() }?.let { order.indexOf(it) } ?: a
                    (if (a <= b) (a..b).toList() else (a..6).toList() + (0..b).toList()).map { DayOfWeek.of(it + 1) }.toSet()
                }
                for (t in Regex("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})").findAll(cond)) {
                    val from = t.groupValues[1].toInt() * 60 + t.groupValues[2].toInt()
                    val to = (t.groupValues[3].toInt() * 60 + t.groupValues[4].toInt()) % (24 * 60)
                    out.add(Cond(kmh, from, to, days))
                }
            }
            return out
        }
    }
}
