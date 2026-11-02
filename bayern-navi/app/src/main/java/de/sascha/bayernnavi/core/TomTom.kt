package de.sascha.bayernnavi.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.roundToInt

/**
 * TomTom Routing mit Live-Verkehr (optional, kostenloser API-Key von developer.tomtom.com).
 * Liefert Stau, Baustellen und Sperrungen auf ALLEN Strassen, nicht nur auf Autobahnen,
 * und rechnet sie direkt in die Fahrzeit und Routenwahl ein.
 */
object TomTom {
    const val BASE = "https://api.tomtom.com"

    fun routeUrl(points: List<LatLon>, key: String, opts: RouteOptions, alternatives: Int, heading: Double?): String {
        val locs = points.joinToString(":") { String.format(Locale.ROOT, "%.6f,%.6f", it.lat, it.lon) }
        val sb = StringBuilder(BASE).append("/routing/1/calculateRoute/").append(locs).append("/json?key=")
            .append(URLEncoder.encode(key.trim(), "UTF-8"))
        sb.append("&traffic=true&travelMode=car&routeType=fastest&routeRepresentation=polyline")
        sb.append("&computeTravelTimeFor=all&instructionsType=text&language=de-DE")
        sb.append("&sectionType=traffic&sectionType=motorway&sectionType=tollRoad")
        if (alternatives > 0 && points.size == 2) sb.append("&maxAlternatives=").append(alternatives)
        if (opts.avoidTolls) sb.append("&avoid=tollRoads")
        if (opts.avoidMotorways) sb.append("&avoid=motorways")
        if (opts.avoidFerries) sb.append("&avoid=ferries")
        if (heading != null) sb.append("&vehicleHeading=").append(heading.roundToInt().mod(360))
        return sb.toString()
    }

    /** POST-Body mit Sperrflaechen (max. 10 Rechtecke). */
    fun routeBody(avoid: List<BBox>): String {
        val rects = JSONArray()
        for (b in avoid.take(10)) {
            rects.put(
                JSONObject()
                    .put("southWestCorner", JSONObject().put("latitude", b.minLat).put("longitude", b.minLon))
                    .put("northEastCorner", JSONObject().put("latitude", b.maxLat).put("longitude", b.maxLon))
            )
        }
        return JSONObject().put("avoidAreas", JSONObject().put("rectangles", rects)).toString()
    }

    fun flowTileUrl(key: String, night: Boolean): String =
        "$BASE/traffic/map/4/tile/flow/${if (night) "relative0-dark" else "relative0"}/{z}/{x}/{y}.png?tileSize=512&key=" +
            URLEncoder.encode(key.trim(), "UTF-8")

    class ApiError(msg: String) : Exception(msg)

    fun errorMessage(body: String): String = try {
        val o = JSONObject(body)
        o.optJSONObject("error")?.optString("description")
            ?: o.optJSONObject("detailedError")?.optString("message")
            ?: o.optString("errorText", "TomTom-Fehler")
    } catch (e: Exception) {
        "TomTom-Fehler"
    }

    fun parseRoutes(json: String): List<Route> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("routes") ?: throw ApiError(errorMessage(json))
        val out = ArrayList<Route>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            try {
                out.add(parseRoute(r))
            } catch (e: Exception) {
                if (i == 0) throw e
            }
        }
        return out
    }

    private fun parseRoute(r: JSONObject): Route {
        val summary = r.getJSONObject("summary")
        val length = summary.optDouble("lengthInMeters", 0.0)
        val travel = summary.optDouble("travelTimeInSeconds", 0.0)
        val delay = summary.optDouble("trafficDelayInSeconds", 0.0)
        val legs = r.getJSONArray("legs")
        val pts = ArrayList<LatLon>()
        val legLens = ArrayList<Double>()
        for (li in 0 until legs.length()) {
            val leg = legs.getJSONObject(li)
            val p = leg.optJSONArray("points") ?: JSONArray()
            for (k in 0 until p.length()) {
                val o = p.getJSONObject(k)
                pts.add(LatLon(o.getDouble("latitude"), o.getDouble("longitude")))
            }
            legLens.add(leg.optJSONObject("summary")?.optDouble("lengthInMeters", 0.0) ?: 0.0)
        }
        val poly = Polyline(pts)
        // Abschnitts-Enden: TomTom-Laengen aufsummieren, auf die Geometrielaenge skaliert.
        val legEnds = ArrayList<Double>()
        val total = legLens.sum().takeIf { it > 0 } ?: poly.length
        var acc = 0.0
        for (l in legLens) {
            acc += l
            legEnds.add(acc / total * poly.length)
        }
        if (legEnds.isEmpty()) legEnds.add(poly.length)
        legEnds[legEnds.size - 1] = poly.length

        val maneuvers = ArrayList<Maneuver>()
        val timeProfile = ArrayList<Pair<Double, Double>>()
        timeProfile.add(0.0 to 0.0)
        val refs = LinkedHashSet<String>()
        val nameLen = LinkedHashMap<String, Double>()
        val instr = r.optJSONObject("guidance")?.optJSONArray("instructions") ?: JSONArray()
        var prevAlong = 0.0
        var prevName: String? = null
        var waypointCount = 0
        for (k in 0 until instr.length()) {
            val ins = instr.getJSONObject(k)
            val along = ins.optDouble("routeOffsetInMeters", 0.0) / length.coerceAtLeast(1.0) * poly.length
            val t = ins.optDouble("travelTimeInSeconds", 0.0)
            if (along > timeProfile.last().first && t >= timeProfile.last().second) timeProfile.add(along to t)
            val maneuverCode = ins.optString("maneuver", "")
            val roadNumbers = ins.optJSONArray("roadNumbers").strings()
            val street = ins.optStr("street")
            roadNumbers.forEach { rn -> Autobahn.roadId(rn)?.let { refs.add(it) } }
            val angle = ins.optNum("turnAngleInDecimalDegrees")
            var kind = kindOf(maneuverCode, angle)
            if (kind == ManeuverKind.WAYPOINT) waypointCount++
            val label = when {
                roadNumbers.isNotEmpty() && (street == null || roadNumbers.first().startsWith("A")) -> roadNumbers.first()
                street != null && roadNumbers.isNotEmpty() -> "$street (${roadNumbers.first()})"
                else -> street
            }
            if (prevName != null) nameLen[prevName] = (nameLen[prevName] ?: 0.0) + (along - prevAlong)
            prevName = roadNumbers.firstOrNull() ?: street
            prevAlong = along
            val ptObj = ins.optJSONObject("point")
            val pt = if (ptObj != null) LatLon(ptObj.optDouble("latitude"), ptObj.optDouble("longitude")) else poly.pointAt(along)
            if (kind == ManeuverKind.DEPART && k > 0) kind = ManeuverKind.CONTINUE
            maneuvers.add(
                Maneuver(
                    kind = kind, along = along, point = pt, street = label,
                    exitNumber = ins.optStr("exitNumber"),
                    signToward = ins.optStr("signpostText"),
                    roundaboutExit = ins.optInt("roundaboutExitNumber", 0),
                    providerText = ins.optStr("message"),
                    legIndex = waypointCount.coerceAtMost(legEnds.size - 1),
                    turnAngle = angle,
                )
            )
        }
        if (prevName != null) nameLen[prevName] = (nameLen[prevName] ?: 0.0) + (poly.length - prevAlong)
        if (timeProfile.last().first < poly.length) timeProfile.add(poly.length to travel)

        var hasToll = false
        var hasHighway = false
        val hazards = ArrayList<RouteHazard>()
        val sections = r.optJSONArray("sections") ?: JSONArray()
        for (k in 0 until sections.length()) {
            val s = sections.getJSONObject(k)
            val si = s.optInt("startPointIndex", 0).coerceIn(0, (pts.size - 1).coerceAtLeast(0))
            val ei = s.optInt("endPointIndex", 0).coerceIn(0, (pts.size - 1).coerceAtLeast(0))
            when (s.optString("sectionType")) {
                "TOLL_ROAD" -> hasToll = true
                "MOTORWAY" -> hasHighway = true
                "TRAFFIC" -> {
                    if (pts.isEmpty()) continue
                    val h = trafficHazard(s, pts, si, ei, k)
                    hazards.add(RouteHazard(h, poly.cum[si], poly.cum[ei]))
                }
            }
        }
        return Route(
            source = RouteSource.TOMTOM,
            geometry = poly,
            distanceM = length,
            maneuvers = maneuvers,
            legEnds = legEnds,
            timeProfile = timeProfile,
            roadRefs = refs,
            hasToll = hasToll,
            hasHighway = hasHighway,
            hasFerry = false,
            via = Valhalla.viaText(nameLen),
            providerHazards = hazards.sortedBy { it.startAlong },
            providerDelaySec = delay,
        )
    }

    private fun trafficHazard(s: JSONObject, pts: List<LatLon>, si: Int, ei: Int, k: Int): Hazard {
        val cat = s.optString("simpleCategory")
        val magnitude = s.optInt("magnitudeOfDelay", 0)
        val delay = s.optInt("delayInSeconds", 0)
        val speed = s.optNum("effectiveSpeedInKmh")?.roundToInt()
        val cause = s.optJSONObject("tec")?.optJSONArray("causes")?.optJSONObject(0)?.optInt("mainCauseCode", 0) ?: 0
        val type = when {
            cat == "ROAD_CLOSURE" -> HazardType.CLOSURE
            cat == "ROAD_WORK" -> HazardType.ROADWORKS
            cause == 2 -> HazardType.ACCIDENT
            cat == "JAM" && magnitude <= 1 -> HazardType.SLOW_TRAFFIC
            cat == "JAM" -> HazardType.JAM
            else -> HazardType.DANGER
        }
        val causeText = causeText(cause)
        val title = when (type) {
            HazardType.DANGER -> causeText ?: "Gefahrenstelle"
            else -> type.label
        }
        val geom = if (ei > si) pts.subList(si, ei + 1).toList() else emptyList()
        val desc = buildString {
            if (causeText != null && type != HazardType.DANGER) append(causeText)
            if (speed != null && type.isTraffic) {
                if (isNotEmpty()) append(" · ")
                append("ca. $speed km/h")
            }
        }
        return Hazard(
            id = s.optString("eventId").ifBlank { "tt-$k-$si" },
            type = type, title = title, description = desc,
            point = pts[si], geometry = geom, delaySec = delay, speedKmh = speed, source = "tomtom",
        )
    }

    /** TPEG-TEC Hauptursachen (Auswahl). */
    fun causeText(code: Int): String? = when (code) {
        1 -> "Stau"
        2 -> "Unfall"
        3 -> "Baustelle"
        4 -> "Fahrbahnverengung"
        5 -> "Strasse unpassierbar"
        6 -> "Glätte"
        7 -> "Aquaplaning"
        8 -> "Brand"
        9 -> "Gefährliche Fahrbedingungen"
        10 -> "Gegenstand auf der Fahrbahn"
        11 -> "Tiere auf der Fahrbahn"
        12 -> "Personen auf der Fahrbahn"
        13 -> "Liegengebliebenes Fahrzeug"
        14 -> "Falschfahrer"
        15 -> "Bergungsarbeiten"
        17 -> "Unwetter"
        18 -> "Sichtbehinderung"
        26 -> "Langsames Fahrzeug"
        27 -> "Gefährliches Stauende"
        30 -> "Polizeikontrolle"
        else -> null
    }

    fun kindOf(code: String, angle: Double?): ManeuverKind = when (code) {
        "DEPART" -> ManeuverKind.DEPART
        "ARRIVE" -> ManeuverKind.ARRIVE
        "ARRIVE_LEFT" -> ManeuverKind.ARRIVE_LEFT
        "ARRIVE_RIGHT" -> ManeuverKind.ARRIVE_RIGHT
        "WAYPOINT_REACHED", "WAYPOINT_LEFT", "WAYPOINT_RIGHT" -> ManeuverKind.WAYPOINT
        "STRAIGHT" -> ManeuverKind.STRAIGHT
        "KEEP_RIGHT" -> ManeuverKind.KEEP_RIGHT
        "BEAR_RIGHT" -> ManeuverKind.SLIGHT_RIGHT
        "TURN_RIGHT" -> ManeuverKind.RIGHT
        "SHARP_RIGHT" -> ManeuverKind.SHARP_RIGHT
        "KEEP_LEFT" -> ManeuverKind.KEEP_LEFT
        "BEAR_LEFT" -> ManeuverKind.SLIGHT_LEFT
        "TURN_LEFT" -> ManeuverKind.LEFT
        "SHARP_LEFT" -> ManeuverKind.SHARP_LEFT
        "MAKE_UTURN", "TRY_MAKE_UTURN" -> ManeuverKind.UTURN
        "ENTER_MOTORWAY", "ENTER_FREEWAY", "ENTER_HIGHWAY", "ENTRANCE_RAMP" ->
            if ((angle ?: 0.0) < -10.0) ManeuverKind.RAMP_LEFT else ManeuverKind.RAMP_RIGHT
        "TAKE_EXIT", "MOTORWAY_EXIT_RIGHT" -> ManeuverKind.EXIT_RIGHT
        "MOTORWAY_EXIT_LEFT" -> ManeuverKind.EXIT_LEFT
        "TAKE_FERRY" -> ManeuverKind.FERRY
        "ROUNDABOUT_CROSS", "ROUNDABOUT_RIGHT", "ROUNDABOUT_LEFT", "ROUNDABOUT_BACK" -> ManeuverKind.ROUNDABOUT
        "SWITCH_PARALLEL_ROAD", "SWITCH_MAIN_ROAD" -> ManeuverKind.KEEP_RIGHT
        "FOLLOW" -> ManeuverKind.CONTINUE
        else -> ManeuverKind.CONTINUE
    }
}
