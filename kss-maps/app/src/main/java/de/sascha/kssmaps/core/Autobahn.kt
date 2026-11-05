package de.sascha.kssmaps.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Verkehrsmeldungen der Autobahn GmbH des Bundes (verkehr.autobahn.de, frei, kein Key):
 * Staus/Warnungen, Baustellen und Sperrungen je Autobahn – mit Geometrie.
 */
object Autobahn {
    const val BASE = "https://verkehr.autobahn.de/o/autobahn"
    val SERVICES = listOf("warning", "roadworks", "closure")

    private val refRegex = Regex("^A\\s?(\\d{1,3})(?:\\s.*)?$")

    /** "A 9" / "A9" / "A 9 (E 45)" -> "A9"; alles andere -> null. */
    fun roadId(ref: String): String? {
        val m = refRegex.find(ref.trim()) ?: return null
        return "A" + m.groupValues[1].trimStart('0').ifEmpty { "0" }
    }

    fun url(roadId: String, service: String) = "$BASE/$roadId/services/$service"

    fun parse(json: String, service: String, roadId: String, now: ZonedDateTime = ZonedDateTime.now(BERLIN)): List<Hazard> {
        val root = JSONObject(json)
        val arr = root.optJSONArray(service) ?: return emptyList()
        val out = ArrayList<Hazard>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.optBoolean("future", false)) continue
            val displayType = it.optString("display_type")
            if (displayType == "WEBCAM" || displayType == "PARKING" || displayType.contains("CHARGING")) continue
            val geom = parseGeometry(it.optJSONObject("geometry"))
            val coord = it.optJSONObject("coordinate")
            val lat = coord?.optNum("lat")
            val lon = coord?.optNum("long")
            val point = if (lat != null && lon != null) LatLon(lat, lon) else geom.firstOrNull() ?: continue
            val title = it.optString("title").trim()
            val subtitle = it.optString("subtitle").trim()
            val desc = it.optJSONArray("description").strings()
            // Baustellen nur, solange sie gelten: Beginn/Ende, Tagesbaustellen nur in ihrem Zeitfenster
            if (service == "roadworks" && !activeNow(desc, displayType, title, now)) continue
            val blocked = it.optString("isBlocked").equals("true", true)
            val abnormal = it.optString("abnormalTrafficType")
            val avgSpeed = it.optNum("averageSpeed")?.roundToInt()?.takeIf { s -> s > 0 }
            val delayMin = it.optNum("delayTimeValue")?.roundToInt()
            val type = when (service) {
                "warning" -> classifyWarning(abnormal, title, desc)
                "roadworks" -> if (blocked) HazardType.CLOSURE else HazardType.ROADWORKS
                "closure" -> HazardType.CLOSURE
                else -> HazardType.OTHER
            }
            var delaySec = (delayMin ?: 0) * 60
            if (delaySec == 0 && type.isTraffic && geom.size >= 2) delaySec = estimateDelay(Geo.lineLength(geom), avgSpeed, type)
            out.add(
                Hazard(
                    id = it.optString("identifier").ifBlank { "$roadId-$service-$i" },
                    type = type,
                    title = titleFor(type, title, subtitle),
                    description = describe(subtitle, desc),
                    point = point,
                    geometry = geom,
                    delaySec = delaySec,
                    speedKmh = avgSpeed,
                    source = "autobahn",
                    roadRef = roadId,
                )
            )
        }
        return out
    }

    val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    private val whenRx = "(\\d{1,2})\\.(\\d{1,2})\\.(\\d{2,4})(?:\\s*(?:um|,|ab)?\\s*(\\d{1,2})[:.](\\d{2}))?"
    private val beginRx = Regex("Beginn:?\\s*$whenRx", RegexOption.IGNORE_CASE)
    private val endRx = Regex("Ende:?\\s*$whenRx", RegexOption.IGNORE_CASE)
    /** Ausdrueckliches taegliches Zeitfenster, z. B. "täglich 09:00 - 15:00 Uhr", "Mo-Fr 9.00 bis 15.00 Uhr". */
    private val dailyRx = Regex(
        "(?:t(?:ä|ae)glich|jeweils|werktags|nachts|Mo(?:ntag)?\\.?\\s*(?:-|bis)\\s*(?:Fr|Sa|So)\\w*\\.?)[^0-9]{0,25}(\\d{1,2})[:.](\\d{2})\\s*(?:Uhr)?\\s*(?:-|–|bis)\\s*(\\d{1,2})[:.](\\d{2})",
        RegexOption.IGNORE_CASE,
    )
    private val shortTermRx = Regex("Tagesbaustelle|Kurzzeitbaustelle|Nachtbaustelle|Wanderbaustelle|Tagesbaust", RegexOption.IGNORE_CASE)

    private fun dateTime(m: MatchResult, endOfDay: Boolean): LocalDateTime? = try {
        val d = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        var y = m.groupValues[3].toInt()
        if (y < 100) y += 2000
        val h = m.groupValues[4]
        val t = if (h.isNotEmpty()) LocalTime.of(h.toInt() % 24, m.groupValues[5].toInt()) else if (endOfDay) LocalTime.of(23, 59) else LocalTime.MIDNIGHT
        LocalDateTime.of(y, mo, d, t.hour, t.minute)
    } catch (e: Exception) {
        null
    }

    /**
     * Gilt die Baustelle jetzt? Aus der Beschreibung ("Beginn: 03.11.26 um 09:00 Uhr", "Ende: …").
     * Tagesbaustellen ueber mehrere Tage gelten nur im taeglichen Zeitfenster (ausdruecklich genannt
     * oder Beginn-/Ende-Uhrzeit, z. B. 09:00–15:00 oder nachts 20:00–05:00). Ohne Angaben: ja.
     */
    fun activeNow(desc: List<String>, displayType: String, title: String, now: ZonedDateTime): Boolean {
        val text = desc.joinToString("\n")
        val local = now.withZoneSameInstant(BERLIN).toLocalDateTime()
        val begin = beginRx.find(text)?.let { dateTime(it, false) }
        val end = endRx.find(text)?.let { dateTime(it, true) }
        if (begin != null && local.isBefore(begin.minusMinutes(10))) return false
        if (end != null && local.isAfter(end.plusMinutes(10))) return false
        val daily = dailyRx.find(text)
        val window: Pair<LocalTime, LocalTime>? = when {
            daily != null -> try {
                LocalTime.of(daily.groupValues[1].toInt() % 24, daily.groupValues[2].toInt()) to LocalTime.of(daily.groupValues[3].toInt() % 24, daily.groupValues[4].toInt())
            } catch (e: Exception) {
                null
            }
            (displayType == "SHORT_TERM_ROADWORKS" || shortTermRx.containsMatchIn(title + " " + text)) &&
                begin != null && end != null && begin.toLocalDate() != end.toLocalDate() -> {
                val a = begin.toLocalTime()
                val b = end.toLocalTime()
                // nur ein echtes Arbeitszeitfenster (2–14 Stunden), kein "00:00 bis 23:59"
                val span = ((b.toSecondOfDay() - a.toSecondOfDay()) + 86_400) % 86_400
                if (a != b && span in 2 * 3600..14 * 3600) a to b else null
            }
            else -> null
        }
        if (window != null) {
            val t = local.toLocalTime().toSecondOfDay()
            val a = (window.first.toSecondOfDay() - 600 + 86_400) % 86_400
            val b = (window.second.toSecondOfDay() + 600) % 86_400
            return if (a <= b) t in a..b else t >= a || t <= b
        }
        return true
    }

    private fun parseGeometry(g: JSONObject?): List<LatLon> {
        if (g == null) return emptyList()
        val coords = g.optJSONArray("coordinates") ?: return emptyList()
        return when (g.optString("type")) {
            "LineString" -> lineFrom(coords)
            "MultiLineString" -> {
                val out = ArrayList<LatLon>()
                for (i in 0 until coords.length()) coords.optJSONArray(i)?.let { out.addAll(lineFrom(it)) }
                out
            }
            "Point" -> {
                val lon = coords.optDouble(0, Double.NaN)
                val lat = coords.optDouble(1, Double.NaN)
                if (lat.isNaN() || lon.isNaN()) emptyList() else listOf(LatLon(lat, lon))
            }
            else -> emptyList()
        }
    }

    private fun lineFrom(arr: JSONArray): List<LatLon> {
        val out = ArrayList<LatLon>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONArray(i) ?: continue
            val lon = c.optDouble(0, Double.NaN)
            val lat = c.optDouble(1, Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) out.add(LatLon(lat, lon))
        }
        return out
    }

    fun classifyWarning(abnormal: String, title: String, desc: List<String>): HazardType {
        val text = (title + " " + desc.joinToString(" ")).lowercase()
        return when {
            abnormal == "STATIONARY_TRAFFIC" || abnormal == "QUEUING_TRAFFIC" -> HazardType.JAM
            abnormal == "SLOW_TRAFFIC" || abnormal == "HEAVY_TRAFFIC" -> HazardType.SLOW_TRAFFIC
            "unfall" in text -> HazardType.ACCIDENT
            "stau" in text -> HazardType.JAM
            "stockend" in text || "zähfließend" in text || "zaehfliessend" in text -> HazardType.SLOW_TRAFFIC
            "gesperrt" in text || "sperrung" in text -> HazardType.CLOSURE
            else -> HazardType.DANGER
        }
    }

    /** Verzoegerung schaetzen, wenn die API keine nennt: Stau ~15 km/h, stockend ~40 km/h statt ~100. */
    fun estimateDelay(lengthM: Double, avgSpeed: Int?, type: HazardType): Int {
        val v = (avgSpeed ?: if (type == HazardType.JAM) 15 else 40).coerceIn(3, 90)
        val jam = lengthM / (v / 3.6)
        val free = lengthM / (100 / 3.6)
        return max(0.0, jam - free).roundToInt()
    }

    private fun titleFor(type: HazardType, title: String, subtitle: String): String = when (type) {
        HazardType.DANGER, HazardType.OTHER -> subtitle.ifBlank { title }.ifBlank { type.label }
        else -> type.label
    }

    private fun describe(subtitle: String, desc: List<String>): String {
        val lines = desc.map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("Beginn") && !it.startsWith("Ende") }
        return (listOf(subtitle) + lines).filter { it.isNotBlank() }.distinct().take(4).joinToString("\n")
    }
}
