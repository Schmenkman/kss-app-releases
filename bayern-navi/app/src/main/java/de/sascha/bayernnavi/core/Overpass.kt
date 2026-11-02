package de.sascha.bayernnavi.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** OpenStreetMap-Abfragen ueber die Overpass-API: feste Blitzer, Gebaeude am Ziel, UTA-Tags. */
object Overpass {
    val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )

    /** Alle festen Blitzer in Bayern (ein paar tausend Punkte, wird woechentlich erneuert). */
    fun speedCamerasQuery(): String =
        "[out:json][timeout:120];area[\"ISO3166-2\"=\"DE-BY\"][admin_level=4]->.by;" +
            "(node[\"highway\"=\"speed_camera\"](area.by););out body;"

    /** Blitzer entlang einer Bounding-Box (fuer Routen, die Bayern verlassen). */
    fun speedCamerasBBoxQuery(b: BBox): String = String.format(
        Locale.ROOT,
        "[out:json][timeout:60];(node[\"highway\"=\"speed_camera\"](%.5f,%.5f,%.5f,%.5f););out body;",
        b.minLat, b.minLon, b.maxLat, b.maxLon
    )

    fun buildingsQuery(p: LatLon): String = String.format(
        Locale.ROOT,
        "[out:json][timeout:25];(way[\"building\"](around:70,%1\$.6f,%2\$.6f);" +
            "relation[\"building\"](around:70,%1\$.6f,%2\$.6f);" +
            "nwr[\"addr:housenumber\"](around:150,%1\$.6f,%2\$.6f););out body geom;",
        p.lat, p.lon
    )

    fun utaQuery(): String =
        "[out:json][timeout:90];area[\"ISO3166-2\"=\"DE-BY\"][admin_level=4]->.by;" +
            "nwr[\"amenity\"=\"fuel\"][\"payment:uta\"](area.by);out tags center;"

    data class Element(
        val type: String,
        val id: Long,
        val tags: Map<String, String>,
        val lat: Double?,
        val lon: Double?,
        /** Umriss bei Wegen; bei Relationen der groesste geschlossene "outer"-Ring. */
        val ring: List<LatLon>?,
    ) {
        val point: LatLon? get() = if (lat != null && lon != null) LatLon(lat, lon) else ring?.let { Geo.ringCentroid(it) }
    }

    fun parseElements(json: String): List<Element> {
        val arr = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Element>()
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val type = e.optString("type")
            val tags = HashMap<String, String>()
            e.optJSONObject("tags")?.let { t -> t.keys().forEach { k -> tags[k] = t.optString(k) } }
            var lat = e.optNum("lat")
            var lon = e.optNum("lon")
            e.optJSONObject("center")?.let { c ->
                lat = c.optNum("lat")
                lon = c.optNum("lon")
            }
            val ring: List<LatLon>? = when (type) {
                "way" -> e.optJSONArray("geometry")?.let(::geomList)?.takeIf { it.size >= 3 }
                "relation" -> outerRing(e.optJSONArray("members"))
                else -> null
            }
            out.add(Element(type, e.optLong("id"), tags, lat, lon, ring))
        }
        return out
    }

    private fun geomList(a: JSONArray): List<LatLon> {
        val out = ArrayList<LatLon>(a.length())
        for (i in 0 until a.length()) {
            val p = a.optJSONObject(i) ?: continue
            val la = p.optNum("lat") ?: continue
            val lo = p.optNum("lon") ?: continue
            out.add(LatLon(la, lo))
        }
        return out
    }

    private fun outerRing(members: JSONArray?): List<LatLon>? {
        if (members == null) return null
        var best: List<LatLon>? = null
        var bestArea = 0.0
        val open = ArrayList<List<LatLon>>()
        for (i in 0 until members.length()) {
            val m = members.optJSONObject(i) ?: continue
            if (m.optString("type") != "way" || m.optString("role", "outer") == "inner") continue
            val g = m.optJSONArray("geometry")?.let(::geomList) ?: continue
            if (g.size < 2) continue
            if (g.size >= 4 && Geo.distance(g.first(), g.last()) < 1.0) {
                val a = Geo.ringArea(g)
                if (a > bestArea) {
                    bestArea = a
                    best = g
                }
            } else open.add(g)
        }
        if (best != null) return best
        // Offene Teilstuecke zu einem Ring verketten (einfacher Fall).
        if (open.isEmpty()) return null
        val ring = ArrayList(open.removeAt(0))
        var changed = true
        while (open.isNotEmpty() && changed) {
            changed = false
            val it = open.iterator()
            while (it.hasNext()) {
                val s = it.next()
                when {
                    Geo.distance(ring.last(), s.first()) < 1.0 -> { ring.addAll(s.drop(1)); it.remove(); changed = true }
                    Geo.distance(ring.last(), s.last()) < 1.0 -> { ring.addAll(s.reversed().drop(1)); it.remove(); changed = true }
                }
            }
        }
        return ring.takeIf { it.size >= 3 }
    }

    fun parseCameras(json: String): List<Hazard> {
        val out = ArrayList<Hazard>()
        for (e in parseElements(json)) {
            val p = e.point ?: continue
            val maxspeed = e.tags["maxspeed"]?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
            val dir = parseDirection(e.tags["direction"] ?: e.tags["camera:direction"])
            val kind = when {
                e.tags["enforcement"] == "traffic_signals" || e.tags["speed_camera"] == "traffic_signals" -> "Rotlicht- und Tempoblitzer"
                e.tags["enforcement"] == "average_speed" -> "Abschnittskontrolle"
                else -> "Fester Blitzer"
            }
            out.add(
                Hazard(
                    id = "osm-${e.id}", type = HazardType.SPEED_CAMERA, title = "Blitzer",
                    description = kind + (maxspeed?.let { " · $it km/h" } ?: ""),
                    point = p, maxSpeed = maxspeed, source = "osm", directionDeg = dir,
                )
            )
        }
        return out
    }

    /** "90", "NE", "N;S" (mehrere Richtungen -> keine Einschraenkung), "forward" (unbrauchbar ohne Weg). */
    fun parseDirection(v: String?): Double? {
        if (v.isNullOrBlank() || v.contains(';')) return null
        v.trim().toDoubleOrNull()?.let { return ((it % 360) + 360) % 360 }
        val card = mapOf(
            "N" to 0.0, "NNE" to 22.5, "NE" to 45.0, "ENE" to 67.5, "E" to 90.0, "ESE" to 112.5, "SE" to 135.0, "SSE" to 157.5,
            "S" to 180.0, "SSW" to 202.5, "SW" to 225.0, "WSW" to 247.5, "W" to 270.0, "WNW" to 292.5, "NW" to 315.0, "NNW" to 337.5,
        )
        return card[v.trim().uppercase(Locale.ROOT)]
    }

    /** OSM-Tankstellen mit payment:uta -> (Position, akzeptiert?). */
    fun parseUta(json: String): List<Pair<LatLon, Boolean>> = parseElements(json).mapNotNull { e ->
        val p = e.point ?: return@mapNotNull null
        val v = e.tags["payment:uta"]?.lowercase(Locale.ROOT) ?: return@mapNotNull null
        when (v) {
            "yes" -> p to true
            "no" -> p to false
            else -> null
        }
    }
}

/**
 * Findet das Gebaeude zur Zieladresse, damit es farbig markiert werden kann.
 * Reihenfolge: Gebaeude mit passender Hausnummer -> Gebaeude, in dem der passende
 * Adresspunkt liegt -> Gebaeude am Zielpunkt -> naechstes Gebaeude (max. 25 m).
 */
object BuildingFinder {
    data class Result(val ring: List<LatLon>, val label: LatLon, val houseNumber: String?, val levels: Int?)

    fun normalizeNumber(s: String): String = s.lowercase(Locale.GERMANY).replace(" ", "").replace("-", "-")

    fun normalizeStreet(s: String): String = s.lowercase(Locale.GERMANY)
        .replace("straße", "str").replace("strasse", "str").replace("str.", "str")
        .replace(Regex("[^a-z0-9äöü]"), "")

    fun numberMatches(tagValue: String, wanted: String): Boolean {
        val w = normalizeNumber(wanted)
        val wNum = Regex("^\\d+").find(w)?.value?.toIntOrNull()
        for (part in tagValue.split(';', ',')) {
            val p = normalizeNumber(part.trim())
            if (p == w) return true
            val range = Regex("^(\\d+)[a-z]?-(\\d+)[a-z]?$").find(p)
            if (range != null && wNum != null && w == wNum.toString()) {
                val a = range.groupValues[1].toInt()
                val b = range.groupValues[2].toInt()
                if (wNum in minOf(a, b)..maxOf(a, b)) return true
            }
        }
        return false
    }

    private fun streetOk(tags: Map<String, String>, street: String?): Boolean {
        val s = tags["addr:street"] ?: tags["addr:place"] ?: return true
        if (street.isNullOrBlank()) return true
        return normalizeStreet(s) == normalizeStreet(street)
    }

    private fun levels(tags: Map<String, String>): Int? = tags["building:levels"]?.toDoubleOrNull()?.toInt()

    fun find(dest: LatLon, houseNumber: String?, street: String?, elements: List<Overpass.Element>): Result? {
        val buildings = elements.filter { it.ring != null && (it.tags.containsKey("building") || it.tags.containsKey("building:part")) }
        if (buildings.isEmpty()) return null
        if (!houseNumber.isNullOrBlank()) {
            // 1. Gebaeude traegt die Adresse selbst
            val direct = buildings.filter { b ->
                b.tags["addr:housenumber"]?.let { numberMatches(it, houseNumber) } == true && streetOk(b.tags, street)
            }.minByOrNull { Geo.distanceToRing(dest, it.ring!!) }
            if (direct != null && Geo.distanceToRing(dest, direct.ring!!) < 250.0) {
                return Result(direct.ring, Geo.ringCentroid(direct.ring), houseNumber, levels(direct.tags))
            }
            // 2. Adresspunkt (z. B. amtliche Hauskoordinate) liegt in einem Gebaeude
            val addrPoints = elements.filter { e ->
                e.ring == null && e.tags["addr:housenumber"]?.let { numberMatches(it, houseNumber) } == true && streetOk(e.tags, street)
            }.mapNotNull { it.point }.sortedBy { Geo.distance(dest, it) }
            for (ap in addrPoints) {
                val inside = buildings.filter { Geo.pointInRing(ap, it.ring!!) }.minByOrNull { Geo.ringArea(it.ring!!) }
                if (inside != null) return Result(inside.ring!!, ap, houseNumber, levels(inside.tags))
                val near = buildings.minByOrNull { Geo.distanceToRing(ap, it.ring!!) }
                if (near != null && Geo.distanceToRing(ap, near.ring!!) <= 12.0) return Result(near.ring, ap, houseNumber, levels(near.tags))
            }
        }
        // 3. Zielpunkt liegt in einem Gebaeude
        val containing = buildings.filter { Geo.pointInRing(dest, it.ring!!) }.minByOrNull { Geo.ringArea(it.ring!!) }
        if (containing != null) return Result(containing.ring!!, Geo.ringCentroid(containing.ring), houseNumber, levels(containing.tags))
        // 4. naechstes Gebaeude
        val nearest = buildings.minByOrNull { Geo.distanceToRing(dest, it.ring!!) } ?: return null
        return if (Geo.distanceToRing(dest, nearest.ring!!) <= 25.0)
            Result(nearest.ring, Geo.ringCentroid(nearest.ring), houseNumber, levels(nearest.tags)) else null
    }
}
