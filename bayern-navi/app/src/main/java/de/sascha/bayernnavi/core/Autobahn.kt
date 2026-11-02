package de.sascha.bayernnavi.core

import org.json.JSONArray
import org.json.JSONObject
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

    fun parse(json: String, service: String, roadId: String): List<Hazard> {
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
