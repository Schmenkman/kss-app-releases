package de.sascha.bayernnavi.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Valhalla (oeffentliche Instanz der FOSSGIS, OpenStreetMap-Daten). Kein API-Key, keine
 * Live-Verkehrsdaten – Stau kommt bei dieser Variante aus der Autobahn-API dazu.
 */
object Valhalla {
    const val BASE = "https://valhalla1.openstreetmap.de"

    fun routeRequest(
        points: List<LatLon>,
        opts: RouteOptions,
        avoidPoints: List<LatLon>,
        alternates: Int,
        startHeading: Double?,
        costFactors: List<Pair<List<LatLon>, Double>> = emptyList(),
    ): String {
        val o = JSONObject()
        val locs = JSONArray()
        points.forEachIndexed { i, p ->
            val l = JSONObject().put("lat", p.lat).put("lon", p.lon)
            if (i == 0 && startHeading != null) {
                l.put("heading", startHeading.roundToInt().mod(360))
                l.put("heading_tolerance", 60)
            }
            if (i in 1 until points.size - 1) l.put("type", "break")
            locs.put(l)
        }
        o.put("locations", locs)
        o.put("costing", "auto")
        val auto = JSONObject()
        if (opts.avoidTolls) auto.put("use_tolls", 0.0)
        if (opts.avoidMotorways) auto.put("use_highways", 0.0)
        if (opts.avoidFerries) auto.put("use_ferry", 0.0)
        o.put("costing_options", JSONObject().put("auto", auto))
        o.put("units", "kilometers")
        o.put("language", "de-DE")
        o.put("directions_options", JSONObject().put("units", "kilometers").put("language", "de-DE"))
        o.put("turn_lanes", true)
        if (alternates > 0 && points.size == 2) o.put("alternates", alternates)
        if (avoidPoints.isNotEmpty()) {
            val ex = JSONArray()
            avoidPoints.forEach { ex.put(JSONObject().put("lat", it.lat).put("lon", it.lon)) }
            o.put("exclude_locations", ex)
        }
        if (costFactors.isNotEmpty()) {
            val lc = JSONArray()
            costFactors.forEach { (shape, f) ->
                if (shape.size >= 2) lc.put(JSONObject().put("shape", Geo.encodePolyline(shape, 6)).put("factor", f))
            }
            if (lc.length() > 0) o.put("linear_cost_factors", lc)
        }
        return o.toString()
    }

    fun traceAttributesRequest(shape: List<LatLon>, edgeWalk: Boolean): String {
        val o = JSONObject()
        o.put("encoded_polyline", Geo.encodePolyline(shape, 6))
        o.put("costing", "auto")
        o.put("shape_match", if (edgeWalk) "walk_or_snap" else "map_snap")
        o.put("units", "kilometers")
        o.put(
            "filters", JSONObject()
                .put("attributes", JSONArray(listOf("edge.speed_limit", "edge.begin_shape_index", "edge.end_shape_index", "edge.names", "edge.road_class", "shape")))
                .put("action", "include")
        )
        return o.toString()
    }

    fun locateRequest(p: LatLon, radiusM: Int): String {
        val loc = JSONObject().put("lat", p.lat).put("lon", p.lon).put("radius", radiusM)
        return JSONObject().put("locations", JSONArray().put(loc)).put("costing", "auto").put("verbose", true).toString()
    }

    // -------------------------------------------------------------------------------------
    // Antworten
    // -------------------------------------------------------------------------------------

    class ApiError(msg: String) : Exception(msg)

    fun errorMessage(body: String): String = try {
        val o = JSONObject(body)
        o.optString("error", o.optString("status_message", "Routing-Fehler"))
    } catch (e: Exception) {
        "Routing-Fehler"
    }

    fun parseRoutes(json: String): List<Route> {
        val root = JSONObject(json)
        val out = ArrayList<Route>()
        root.optJSONObject("trip")?.let { out.add(parseTrip(it)) }
        val alts = root.optJSONArray("alternates")
        if (alts != null) {
            for (i in 0 until alts.length()) {
                val t = alts.optJSONObject(i)?.optJSONObject("trip") ?: continue
                try {
                    out.add(parseTrip(t))
                } catch (e: Exception) {
                    // eine kaputte Alternative soll die Hauptroute nicht mitreissen
                }
            }
        }
        if (out.isEmpty()) throw ApiError(errorMessage(json))
        return out
    }

    private class RawManeuver(
        val kind: ManeuverKind, val globalIdx: Int, val street: String?, val exitNumber: String?,
        val branch: String?, val toward: String?, val rbExit: Int, val text: String?, val leg: Int,
        val time: Double, val lanes: List<Lane>, val names: List<String>, val lengthM: Double,
        val bearingBefore: Double?, val bearingAfter: Double?,
    )

    private fun parseTrip(trip: JSONObject): Route {
        val legs = trip.getJSONArray("legs")
        val pts = ArrayList<LatLon>()
        val raw = ArrayList<RawManeuver>()
        val legEndIdx = ArrayList<Int>()
        var hasToll = false
        var hasHighway = false
        var hasFerry = false
        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            val shape = Geo.decodePolyline(leg.getString("shape"), 6)
            val base: Int
            if (pts.isEmpty()) {
                base = 0
                pts.addAll(shape)
            } else {
                base = pts.size - 1
                pts.addAll(shape.drop(1))
            }
            legEndIdx.add(pts.size - 1)
            val sum = leg.optJSONObject("summary")
            if (sum != null) {
                hasToll = hasToll || sum.optBoolean("has_toll")
                hasHighway = hasHighway || sum.optBoolean("has_highway")
                hasFerry = hasFerry || sum.optBoolean("has_ferry")
            }
            val mans = leg.optJSONArray("maneuvers") ?: JSONArray()
            for (mi in 0 until mans.length()) {
                val m = mans.getJSONObject(mi)
                val type = m.optInt("type", 0)
                val isLastLeg = li == legs.length() - 1
                val kind = kindOf(type, isLastLeg)
                val names = m.optJSONArray("street_names").strings()
                val beginNames = m.optJSONArray("begin_street_names").strings()
                val sign = m.optJSONObject("sign")
                raw.add(
                    RawManeuver(
                        kind = kind,
                        globalIdx = base + m.optInt("begin_shape_index", 0),
                        street = pickStreet(names.ifEmpty { beginNames }),
                        exitNumber = sign?.optJSONArray("exit_number_elements")?.let(::signTexts)?.firstOrNull(),
                        branch = sign?.optJSONArray("exit_branch_elements")?.let(::signTexts)?.take(2)?.joinToString(" / ")?.ifBlank { null },
                        toward = sign?.optJSONArray("exit_toward_elements")?.let(::signTexts)?.take(2)?.joinToString(", ")?.ifBlank { null },
                        rbExit = m.optInt("roundabout_exit_count", 0),
                        text = m.optStr("instruction"),
                        leg = li,
                        time = m.optDouble("time", 0.0),
                        lanes = parseLanes(m.optJSONArray("lanes")),
                        names = names,
                        lengthM = m.optDouble("length", 0.0) * 1000.0,
                        bearingBefore = m.optNum("bearing_before"),
                        bearingAfter = m.optNum("bearing_after"),
                    )
                )
            }
        }
        val poly = Polyline(pts)
        val maneuvers = ArrayList<Maneuver>(raw.size)
        val timeProfile = ArrayList<Pair<Double, Double>>()
        var t = 0.0
        val nameLen = LinkedHashMap<String, Double>()
        val refs = LinkedHashSet<String>()
        for (r in raw) {
            val idx = r.globalIdx.coerceIn(0, (pts.size - 1).coerceAtLeast(0))
            val along = if (pts.isEmpty()) 0.0 else poly.cum[idx]
            val angle = if (r.bearingBefore != null && r.bearingAfter != null) Geo.signedAngle(r.bearingBefore, r.bearingAfter) else null
            maneuvers.add(
                Maneuver(
                    kind = r.kind, along = along, point = if (pts.isEmpty()) LatLon(0.0, 0.0) else pts[idx],
                    street = r.street, exitNumber = r.exitNumber, signBranch = r.branch, signToward = r.toward,
                    roundaboutExit = r.rbExit, providerText = r.text, legIndex = r.leg, lanes = r.lanes, turnAngle = angle,
                )
            )
            if (timeProfile.isEmpty() || along > timeProfile.last().first) timeProfile.add(along to t)
            t += r.time
            for (n in r.names) {
                nameLen[n] = (nameLen[n] ?: 0.0) + r.lengthM
                Autobahn.roadId(n)?.let { refs.add(it) }
            }
            r.branch?.let { b -> b.split("/").forEach { part -> Autobahn.roadId(part.trim())?.let { refs.add(it) } } }
        }
        timeProfile.add(poly.length to t)
        val summary = trip.optJSONObject("summary")
        val dist = (summary?.optDouble("length", Double.NaN)?.takeIf { !it.isNaN() }?.times(1000.0)) ?: poly.length
        return Route(
            source = RouteSource.VALHALLA,
            geometry = poly,
            distanceM = dist,
            maneuvers = maneuvers,
            legEnds = legEndIdx.map { poly.cum[it.coerceIn(0, (pts.size - 1).coerceAtLeast(0))] },
            timeProfile = timeProfile,
            roadRefs = refs,
            hasToll = hasToll,
            hasHighway = hasHighway,
            hasFerry = hasFerry,
            via = viaText(nameLen),
        )
    }

    /** "über A 9, B 13": die zwei Strassen mit dem laengsten Anteil, Nummern bevorzugt. */
    fun viaText(nameLen: Map<String, Double>): String {
        val sorted = nameLen.entries.sortedByDescending { it.value }
        val refs = sorted.filter { isRef(it.key) }.map { it.key }
        val names = sorted.map { it.key }
        val pick = (refs.take(2) + names).distinct().take(2)
        return if (pick.isEmpty()) "" else "über " + pick.joinToString(", ")
    }

    fun isRef(s: String) = Regex("^(A|B|St|E|Kr|[A-Z]{1,3})\\s?\\d+[a-z]?$").matches(s.trim())

    /** Strassennummer bevorzugt, wenn vorhanden ("A 9" statt "Autobahn München–Nürnberg"). */
    private fun pickStreet(names: List<String>): String? {
        if (names.isEmpty()) return null
        val ref = names.firstOrNull { isRef(it) }
        val name = names.firstOrNull { !isRef(it) }
        return when {
            ref != null && name != null && ref.startsWith("A") -> ref
            name != null && ref != null -> "$name ($ref)"
            else -> name ?: ref
        }
    }

    private fun signTexts(arr: JSONArray): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.optStr("text")?.let { out.add(it) }
        return out
    }

    private fun parseLanes(arr: JSONArray?): List<Lane> {
        if (arr == null) return emptyList()
        val out = ArrayList<Lane>()
        for (i in 0 until arr.length()) {
            val l = arr.optJSONObject(i) ?: continue
            out.add(Lane(l.optInt("directions", 0), l.optInt("active", 0), l.optInt("valid", 0)))
        }
        return out
    }

    fun kindOf(type: Int, lastLeg: Boolean): ManeuverKind = when (type) {
        1, 2, 3 -> ManeuverKind.DEPART
        4 -> if (lastLeg) ManeuverKind.ARRIVE else ManeuverKind.WAYPOINT
        5 -> if (lastLeg) ManeuverKind.ARRIVE_RIGHT else ManeuverKind.WAYPOINT
        6 -> if (lastLeg) ManeuverKind.ARRIVE_LEFT else ManeuverKind.WAYPOINT
        7 -> ManeuverKind.NAME_CHANGE
        8 -> ManeuverKind.CONTINUE
        9 -> ManeuverKind.SLIGHT_RIGHT
        10 -> ManeuverKind.RIGHT
        11 -> ManeuverKind.SHARP_RIGHT
        12, 13 -> ManeuverKind.UTURN
        14 -> ManeuverKind.SHARP_LEFT
        15 -> ManeuverKind.LEFT
        16 -> ManeuverKind.SLIGHT_LEFT
        17 -> ManeuverKind.RAMP_STRAIGHT
        18 -> ManeuverKind.RAMP_RIGHT
        19 -> ManeuverKind.RAMP_LEFT
        20 -> ManeuverKind.EXIT_RIGHT
        21 -> ManeuverKind.EXIT_LEFT
        22 -> ManeuverKind.STRAIGHT
        23 -> ManeuverKind.KEEP_RIGHT
        24 -> ManeuverKind.KEEP_LEFT
        25 -> ManeuverKind.MERGE
        26 -> ManeuverKind.ROUNDABOUT
        27 -> ManeuverKind.ROUNDABOUT_EXIT
        28 -> ManeuverKind.FERRY
        37 -> ManeuverKind.MERGE_RIGHT
        38 -> ManeuverKind.MERGE_LEFT
        else -> ManeuverKind.CONTINUE
    }

    /** Ergebnis von trace_attributes: Tempolimits als Bereiche entlang der uebergebenen Strecke. */
    data class TraceResult(val limits: List<SpeedLimitRange>, val lastNames: List<String>, val lastRoadClass: String?)

    fun parseTraceAttributes(json: String, offsetAlong: Double): TraceResult {
        val root = JSONObject(json)
        val shapeStr = root.optStr("shape")
        val edges = root.optJSONArray("edges") ?: return TraceResult(emptyList(), emptyList(), null)
        val poly = shapeStr?.let { Polyline(Geo.decodePolyline(it, 6)) }
        val out = ArrayList<SpeedLimitRange>()
        var lastNames: List<String> = emptyList()
        var lastClass: String? = null
        for (i in 0 until edges.length()) {
            val e = edges.optJSONObject(i) ?: continue
            val b = e.optInt("begin_shape_index", -1)
            val en = e.optInt("end_shape_index", -1)
            val kmh = when (val v = e.opt("speed_limit")) {
                is Number -> v.toInt()
                is String -> if (v.equals("unlimited", true)) SpeedLimitRange.UNLIMITED else v.toIntOrNull() ?: 0
                else -> 0
            }
            lastNames = e.optJSONArray("names").strings()
            lastClass = e.optStr("road_class")
            if (poly == null || b < 0 || en < 0 || b >= poly.size || en >= poly.size) continue
            val from = offsetAlong + poly.cum[b]
            val to = offsetAlong + poly.cum[en]
            // Benachbarte Kanten mit gleichem Limit zusammenfassen.
            val last = out.lastOrNull()
            if (last != null && last.kmh == kmh && from - last.to < 5.0) out[out.size - 1] = last.copy(to = to)
            else out.add(SpeedLimitRange(from, to, kmh))
        }
        return TraceResult(out, lastNames, lastClass)
    }

    data class LocatedEdge(
        val wayId: Long,
        val names: List<String>,
        val shape: List<LatLon>,
        val point: LatLon,
        val roadClass: String?,
        val speedLimit: Int?,
        val forward: Boolean,
    )

    fun parseLocate(json: String): List<LocatedEdge> {
        val arr = JSONArray(json)
        val first = arr.optJSONObject(0) ?: return emptyList()
        val edges = first.optJSONArray("edges") ?: return emptyList()
        val out = ArrayList<LocatedEdge>()
        for (i in 0 until edges.length()) {
            val e = edges.optJSONObject(i) ?: continue
            val info = e.optJSONObject("edge_info")
            val edge = e.optJSONObject("edge")
            val lat = e.optNum("correlated_lat") ?: continue
            val lon = e.optNum("correlated_lon") ?: continue
            val cls = edge?.optJSONObject("classification")?.optStr("classification")
            val limit = edge?.opt("speed_limit")?.let { if (it is Number) it.toInt() else null }
            out.add(
                LocatedEdge(
                    wayId = e.optLong("way_id", info?.optLong("way_id") ?: 0L),
                    names = info?.optJSONArray("names").strings(),
                    shape = info?.optStr("shape")?.let { Geo.decodePolyline(it, 6) } ?: emptyList(),
                    point = LatLon(lat, lon),
                    roadClass = cls,
                    speedLimit = limit,
                    forward = edge?.optBoolean("forward", true) ?: true,
                )
            )
        }
        return out
    }
}
