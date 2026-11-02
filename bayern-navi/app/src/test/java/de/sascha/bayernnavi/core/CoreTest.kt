package de.sascha.bayernnavi.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {

    // Gerade Strecke nach Osten bei 48.1 N, 0.0001 Grad Laenge ~ 7.4 m
    private fun straightLine(n: Int = 200, step: Double = 0.0005): List<LatLon> =
        (0 until n).map { LatLon(48.1, 11.5 + it * step) }

    @Test
    fun polylineRoundTripAndGoogleExample() {
        val pts = listOf(LatLon(38.5, -120.2), LatLon(40.7, -120.95), LatLon(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Geo.encodePolyline(pts, 5))
        val back = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, back.size)
        assertEquals(43.252, back[2].lat, 1e-9)
        assertEquals(-126.453, back[2].lon, 1e-9)
        val p6 = straightLine(50)
        val rt = Geo.decodePolyline(Geo.encodePolyline(p6, 6), 6)
        assertEquals(p6.size, rt.size)
        assertEquals(p6.last().lon, rt.last().lon, 1e-6)
    }

    @Test
    fun distancesAndBearings() {
        val muc = LatLon(48.1374, 11.5755)
        val nue = LatLon(49.4456, 11.0825)
        val d = Geo.distance(muc, nue)
        assertTrue("d=$d", d in 145_000.0..155_000.0)
        assertEquals(90.0, Geo.bearing(LatLon(48.0, 11.0), LatLon(48.0, 11.1)), 0.1)
        assertEquals(10.0, Geo.angleDiff(355.0, 5.0), 1e-9)
        assertEquals(20.0, Geo.signedAngle(350.0, 10.0), 1e-9)
        assertEquals(-20.0, Geo.signedAngle(10.0, 350.0), 1e-9)
    }

    @Test
    fun polylineProjection() {
        val line = Polyline(straightLine())
        assertTrue(line.length > 7000)
        val p = LatLon(48.1001, 11.52) // ~11 m noerdlich
        val pr = line.projectNear(p, 50.0)
        assertNotNull(pr)
        assertEquals(11.1, pr!!.offset, 1.0)
        val far = line.projectNear(LatLon(48.11, 11.52), 50.0)
        assertNull(far)
        val mid = line.pointAt(line.length / 2)
        assertEquals(48.1, mid.lat, 1e-9)
        val slice = line.slice(1000.0, 2000.0)
        assertEquals(1000.0, Geo.lineLength(slice), 1.0)
        assertEquals(90.0, line.bearingAt(500.0), 0.5)
    }

    @Test
    fun simplifyKeepsEnds() {
        val pts = straightLine(100)
        val s = Geo.simplify(pts, 1.0)
        assertEquals(2, s.size)
        assertEquals(pts.first(), s.first())
        assertEquals(pts.last(), s.last())
    }

    private fun valhallaJson(): String {
        val leg1 = straightLine(100)
        val leg2 = (0 until 60).map { LatLon(48.1 + it * 0.0005, leg1.last().lon) }
        fun maneuver(type: Int, begin: Int, end: Int, time: Double, len: Double, names: List<String>, sign: JSONObject? = null, rb: Int = 0) =
            JSONObject().put("type", type).put("begin_shape_index", begin).put("end_shape_index", end)
                .put("time", time).put("length", len).put("street_names", JSONArray(names))
                .put("instruction", "x").also { if (sign != null) it.put("sign", sign); if (rb > 0) it.put("roundabout_exit_count", rb) }
        val legA = JSONObject().put("shape", Geo.encodePolyline(leg1, 6))
            .put("summary", JSONObject().put("has_highway", true))
            .put(
                "maneuvers", JSONArray()
                    .put(maneuver(1, 0, 40, 30.0, 1.5, listOf("Hauptstraße")))
                    .put(
                        maneuver(
                            18, 40, 99, 60.0, 2.2, listOf("A 9"),
                            JSONObject().put("exit_branch_elements", JSONArray().put(JSONObject().put("text", "A 9")))
                                .put("exit_toward_elements", JSONArray().put(JSONObject().put("text", "Nürnberg")))
                        )
                    )
                    .put(maneuver(4, 99, 99, 0.0, 0.0, emptyList()))
            )
        val legB = JSONObject().put("shape", Geo.encodePolyline(leg2, 6))
            .put(
                "maneuvers", JSONArray()
                    .put(maneuver(1, 0, 20, 20.0, 0.5, listOf("B 13")))
                    .put(maneuver(26, 20, 59, 40.0, 1.6, listOf("Ringstraße"), rb = 2))
                    .put(maneuver(5, 59, 59, 0.0, 0.0, emptyList()))
            )
        val trip = JSONObject().put("legs", JSONArray().put(legA).put(legB)).put("summary", JSONObject().put("length", 9.0).put("time", 150.0))
        val alt = JSONObject().put("legs", JSONArray().put(legA)).put("summary", JSONObject().put("length", 7.0))
        return JSONObject().put("trip", trip).put("alternates", JSONArray().put(JSONObject().put("trip", alt))).toString()
    }

    @Test
    fun parsesValhallaRoutes() {
        val routes = Valhalla.parseRoutes(valhallaJson())
        assertEquals(2, routes.size)
        val r = routes[0]
        assertEquals(RouteSource.VALHALLA, r.source)
        assertEquals(2, r.legEnds.size)
        assertEquals(159, r.geometry.size)          // 100 + 60 - 1 (doppelter Punkt am Zwischenziel)
        assertTrue(r.roadRefs.contains("A9"))
        assertTrue(r.hasHighway)
        val ramp = r.maneuvers[1]
        assertEquals(ManeuverKind.RAMP_RIGHT, ramp.kind)
        assertEquals("Nürnberg", ramp.signToward)
        assertEquals(r.geometry.cum[40], ramp.along, 1e-6)
        assertEquals(ManeuverKind.WAYPOINT, r.maneuvers[2].kind)
        val rb = r.maneuvers.first { it.kind == ManeuverKind.ROUNDABOUT }
        assertEquals(2, rb.roundaboutExit)
        assertEquals(ManeuverKind.ARRIVE_RIGHT, r.maneuvers.last().kind)
        assertEquals(150.0, r.baseDurationSec, 1e-6)
        assertEquals(r.geometry.cum[99], r.legEnds[0], 1e-6)
        // Restzeit sinkt monoton
        assertTrue(r.remainingSec(0.0) > r.remainingSec(r.geometry.length / 2))
        assertEquals(0.0, r.remainingSec(r.geometry.length), 1e-6)
        assertTrue(r.via.contains("A 9"))
    }

    @Test
    fun valhallaRequestHasExclusions() {
        val req = JSONObject(
            Valhalla.routeRequest(
                listOf(LatLon(48.0, 11.0), LatLon(48.1, 11.1), LatLon(48.2, 11.2)), RouteOptions(avoidTolls = true),
                listOf(LatLon(48.05, 11.05)), alternates = 2, startHeading = 370.0,
            )
        )
        assertEquals(1, req.getJSONArray("exclude_locations").length())
        assertFalse(req.has("alternates"))                    // bei Zwischenziel keine Alternativen
        assertEquals("break", req.getJSONArray("locations").getJSONObject(1).getString("type"))
        assertEquals(10, req.getJSONArray("locations").getJSONObject(0).getInt("heading"))
        assertEquals(0.0, req.getJSONObject("costing_options").getJSONObject("auto").getDouble("use_tolls"), 1e-9)
    }

    @Test
    fun parsesTraceAttributes() {
        val shape = straightLine(50)
        val json = JSONObject()
            .put("shape", Geo.encodePolyline(shape, 6))
            .put(
                "edges", JSONArray()
                    .put(JSONObject().put("begin_shape_index", 0).put("end_shape_index", 10).put("speed_limit", 50))
                    .put(JSONObject().put("begin_shape_index", 10).put("end_shape_index", 20).put("speed_limit", 50))
                    .put(JSONObject().put("begin_shape_index", 20).put("end_shape_index", 49).put("speed_limit", "unlimited").put("names", JSONArray().put("A 9")))
            ).toString()
        val res = Valhalla.parseTraceAttributes(json, 1000.0)
        assertEquals(2, res.limits.size)                        // 50 + 50 zusammengefasst
        assertEquals(1000.0, res.limits[0].from, 1e-6)
        assertEquals(SpeedLimitRange.UNLIMITED, res.limits[1].kmh)
        assertEquals(listOf("A 9"), res.lastNames)
    }

    @Test
    fun parsesTomTom() {
        val pts = straightLine(100)
        val ptsJson = JSONArray().apply { pts.forEach { put(JSONObject().put("latitude", it.lat).put("longitude", it.lon)) } }
        val len = Geo.lineLength(pts)
        val route = JSONObject()
            .put("summary", JSONObject().put("lengthInMeters", len).put("travelTimeInSeconds", 600).put("trafficDelayInSeconds", 120))
            .put("legs", JSONArray().put(JSONObject().put("points", ptsJson).put("summary", JSONObject().put("lengthInMeters", len))))
            .put(
                "sections", JSONArray()
                    .put(JSONObject().put("sectionType", "TRAFFIC").put("startPointIndex", 20).put("endPointIndex", 40).put("simpleCategory", "JAM").put("magnitudeOfDelay", 3).put("delayInSeconds", 120).put("effectiveSpeedInKmh", 12))
                    .put(JSONObject().put("sectionType", "MOTORWAY").put("startPointIndex", 0).put("endPointIndex", 99))
            )
            .put(
                "guidance", JSONObject().put(
                    "instructions", JSONArray()
                        .put(JSONObject().put("routeOffsetInMeters", 0).put("travelTimeInSeconds", 0).put("maneuver", "DEPART").put("street", "Hauptstraße"))
                        .put(JSONObject().put("routeOffsetInMeters", len / 2).put("travelTimeInSeconds", 300).put("maneuver", "TAKE_EXIT").put("exitNumber", "65").put("roadNumbers", JSONArray().put("A9")).put("signpostText", "Freising"))
                        .put(JSONObject().put("routeOffsetInMeters", len).put("travelTimeInSeconds", 600).put("maneuver", "ARRIVE"))
                )
            )
        val routes = TomTom.parseRoutes(JSONObject().put("routes", JSONArray().put(route)).toString())
        assertEquals(1, routes.size)
        val r = routes[0]
        assertEquals(600.0, r.durationSec, 1e-6)
        assertEquals(120.0, r.trafficDelaySec, 1e-6)
        assertTrue(r.hasHighway)
        assertEquals(1, r.hazards.size)
        assertEquals(HazardType.JAM, r.hazards[0].hazard.type)
        assertEquals(r.geometry.cum[20], r.hazards[0].startAlong, 1e-6)
        assertEquals(ManeuverKind.EXIT_RIGHT, r.maneuvers[1].kind)
        assertTrue(r.roadRefs.contains("A9"))
        assertEquals(300.0, r.remainingSec(r.geometry.length / 2), 1.0)
    }

    @Test
    fun parsesAutobahnApi() {
        val line = straightLine(30)
        val geom = JSONObject().put("type", "LineString").put("coordinates", JSONArray().apply { line.forEach { put(JSONArray().put(it.lon).put(it.lat)) } })
        val json = JSONObject().put(
            "warning", JSONArray()
                .put(
                    JSONObject().put("identifier", "abc").put("title", "A9 | München -> Nürnberg").put("subtitle", "Allershausen - Langenbruck")
                        .put("coordinate", JSONObject().put("lat", "48.1").put("long", "11.5"))
                        .put("description", JSONArray().put("Beginn: 01.01.26").put("Stau, 3 km"))
                        .put("abnormalTrafficType", "STATIONARY_TRAFFIC").put("delayTimeValue", "12").put("isBlocked", "false")
                        .put("future", false).put("geometry", geom)
                )
                .put(JSONObject().put("identifier", "future").put("future", true).put("coordinate", JSONObject().put("lat", "48.1").put("long", "11.5")))
        ).toString()
        val hs = Autobahn.parse(json, "warning", "A9")
        assertEquals(1, hs.size)
        val h = hs[0]
        assertEquals(HazardType.JAM, h.type)
        assertEquals(720, h.delaySec)
        assertEquals(30, h.geometry.size)
        assertTrue(h.description.contains("Allershausen"))
        assertFalse(h.description.contains("Beginn"))
        assertEquals("A9", Autobahn.roadId("A 9"))
        assertEquals("A99", Autobahn.roadId("A99"))
        assertNull(Autobahn.roadId("B 13"))
        assertNull(Autobahn.roadId("Augsburger Straße"))
        val roadworks = JSONObject().put("roadworks", JSONArray().put(JSONObject().put("identifier", "rw").put("isBlocked", "true").put("coordinate", JSONObject().put("lat", "48.1").put("long", "11.5")))).toString()
        assertEquals(HazardType.CLOSURE, Autobahn.parse(roadworks, "roadworks", "A9")[0].type)
    }

    private fun simpleRoute(pts: List<LatLon>, secs: Double = 600.0): Route {
        val poly = Polyline(pts)
        return Route(
            RouteSource.VALHALLA, poly, poly.length,
            listOf(
                Maneuver(ManeuverKind.DEPART, 0.0, pts.first(), "Start"),
                Maneuver(ManeuverKind.RIGHT, poly.length / 2, poly.pointAt(poly.length / 2), "Mittelweg"),
                Maneuver(ManeuverKind.ARRIVE, poly.length, pts.last()),
            ),
            listOf(poly.length), listOf(0.0 to 0.0, poly.length to secs), emptySet(), false, false, false, "",
        )
    }

    @Test
    fun matchesHazardsOnlyOnRoute() {
        val route = simpleRoute(straightLine())
        val onRoute = Hazard("c1", HazardType.SPEED_CAMERA, "Blitzer", "", LatLon(48.10008, 11.52), source = "osm")
        val parallel = Hazard("c2", HazardType.SPEED_CAMERA, "Blitzer", "", LatLon(48.1006, 11.52), source = "osm")
        val wrongDir = Hazard("c3", HazardType.SPEED_CAMERA, "Blitzer", "", LatLon(48.10005, 11.53), source = "osm", directionDeg = 270.0)
        val rightDir = Hazard("c4", HazardType.SPEED_CAMERA, "Blitzer", "", LatLon(48.10005, 11.54), source = "osm", directionDeg = 85.0)
        val jamGeom = (0 until 20).map { LatLon(48.10003, 11.55 + it * 0.0005) }
        val jam = Hazard("j1", HazardType.JAM, "Stau", "", jamGeom[0], jamGeom, delaySec = 300, source = "autobahn")
        val otherRoadGeom = (0 until 20).map { LatLon(48.1004, 11.55 + it * 0.0005) }   // ~44 m daneben
        val other = Hazard("j2", HazardType.JAM, "Stau", "", otherRoadGeom[0], otherRoadGeom, delaySec = 300, source = "autobahn")
        val crossingGeom = (0 until 20).map { LatLon(48.095 + it * 0.0005, 11.56) }        // kreuzt nur
        val crossing = Hazard("j3", HazardType.JAM, "Stau", "", crossingGeom[0], crossingGeom, delaySec = 300, source = "autobahn")
        val m = HazardMatcher.matchAll(route, listOf(onRoute, parallel, wrongDir, rightDir, jam, other, crossing))
        val ids = m.map { it.hazard.id }.toSet()
        assertEquals(setOf("c1", "c4", "j1"), ids)
        val j = m.first { it.hazard.id == "j1" }
        assertTrue(j.endAlong - j.startAlong > 600)
        assertEquals(1, HazardMatcher.extraDelays(route, m).size)
    }

    @Test
    fun tracksProgressAndDetectsOffRoute() {
        val route = simpleRoute(straightLine())
        val tr = RouteTracker(route)
        var st = tr.update(LatLon(48.10002, 11.501), 5.0, 14.0, 90.0)
        assertTrue(st.onRoute)
        assertEquals(1, st.nextIdx)
        // Fahrt in ~74-m-Schritten (wie bei 1 Fix/s und ~270 km/h – aber ohne Spruenge)
        var lon = 11.501
        while (lon < 11.55) {
            lon += 0.001
            st = tr.update(LatLon(48.10002, lon), 5.0, 14.0, 90.0)
            assertFalse(st.offRoute)
        }
        assertTrue("along=${st.along}", st.along > 3600)
        assertTrue(st.distToNext > 0)
        // drei Fixes weit weg -> abgekommen
        repeat(3) { st = tr.update(LatLon(48.103, 11.552), 5.0, 14.0, 0.0) }
        assertTrue(st.offRoute)
        // ganz am Ende -> angekommen
        val tr2 = RouteTracker(route)
        var l2 = 11.5
        while (l2 < route.geometry.points.last().lon - 0.001) {
            tr2.update(LatLon(48.1, l2), 5.0, 10.0, 90.0)
            l2 += 0.002
        }
        val end = tr2.update(route.geometry.points.last(), 5.0, 1.0, 90.0)
        assertTrue(end.arrived)
        // Sprung von 3 km in einem Fix wird nicht geglaubt (GPS-Ausreisser)
        val tr3 = RouteTracker(route)
        tr3.update(LatLon(48.1, 11.5), 5.0, 14.0, 90.0)
        val jump = tr3.update(LatLon(48.1, 11.55), 5.0, 14.0, 90.0)
        assertTrue(jump.along < 600)
    }

    @Test
    fun announcesInStages() {
        assertNull(AnnouncePlan.stageFor(3000.0, 30.0, true))
        assertEquals(AnnouncePlan.Stage.FAR, AnnouncePlan.stageFor(1900.0, 30.0, true))
        assertEquals(AnnouncePlan.Stage.NEAR, AnnouncePlan.stageFor(700.0, 30.0, true))
        assertEquals(AnnouncePlan.Stage.NOW, AnnouncePlan.stageFor(100.0, 30.0, true))
        assertEquals(AnnouncePlan.Stage.NOW, AnnouncePlan.stageFor(30.0, 8.0, false))
    }

    @Test
    fun ranksFuelStationsOnTheWayFirstThenPrice() {
        val line = Polyline(straightLine())
        fun st(id: String, lat: Double, lon: Double, diesel: Double, brand: String = "Aral") =
            FuelStation(id, brand, brand, "Weg", "1", "80000", "Ort", lat, lon, diesel, 1.80, 1.75, true, UtaStatus.YES)
        val onWayExpensive = st("a", 48.1001, 11.55, 1.799)
        val onWayCheap = st("b", 48.1002, 11.57, 1.699)
        val farCheap = st("c", 48.125, 11.56, 1.499)           // ~2.8 km neben der Route
        val behind = st("d", 48.1001, 11.505, 1.599)            // hinter uns
        val noUta = st("e", 48.1001, 11.56, 1.599).copy(uta = UtaStatus.UNKNOWN)
        val ranked = FuelRanking.rank(listOf(onWayExpensive, onWayCheap, farCheap, behind, noUta), line, 2000.0, line.pointAt(2000.0), FuelType.DIESEL, utaOnly = true)
        assertEquals(listOf("b", "a"), ranked.filter { it.onTheWay }.map { it.station.id })
        assertEquals("b", ranked[0].station.id)
        assertFalse(ranked.any { it.station.id == "e" })
        assertFalse(ranked.first { it.station.id == "d" }.onTheWay)
        assertFalse(ranked.first { it.station.id == "c" }.onTheWay)
    }

    @Test
    fun utaBrandMatching() {
        val brands = Uta.DEFAULT_BRANDS
        assertTrue(Uta.brandAccepted("TotalEnergies", "", brands))
        assertTrue(Uta.brandAccepted("ARAL", "Aral Tankstelle", brands))
        assertTrue(Uta.brandAccepted("Shell", "", brands))
        assertTrue(Uta.brandAccepted("", "Jet Tankstelle", brands))
        assertFalse(Uta.brandAccepted("Start Tankstelle", "", listOf("Star")))
        assertFalse(Uta.brandAccepted("Freie Tankstelle", "Müller", brands))
        assertEquals(UtaStatus.NO, Uta.status("Aral", "", brands, osmTag = false, userOverride = null))
        assertEquals(UtaStatus.YES, Uta.status("Freie", "", brands, osmTag = null, userOverride = true))
        assertEquals(UtaStatus.UNKNOWN, Uta.status("Freie", "", brands, osmTag = null, userOverride = null))
    }

    @Test
    fun germanTexts() {
        assertEquals("300 Metern", Instructions.spokenDistance(310.0))
        assertEquals("einem Kilometer", Instructions.spokenDistance(1100.0))
        assertEquals("1,5 Kilometern", Instructions.spokenDistance(1500.0))
        assertEquals("2 Kilometern", Instructions.spokenDistance(2050.0))
        assertEquals("1,2 km", Instructions.formatDistance(1234.0))
        assertEquals("350 m", Instructions.formatDistance(340.0))
        val right = Maneuver(ManeuverKind.RIGHT, 0.0, LatLon(0.0, 0.0), "Hauptstraße")
        assertEquals("In 300 Metern rechts abbiegen auf Hauptstraße.", Instructions.voice(right, 300.0))
        assertEquals("Jetzt rechts abbiegen auf Hauptstraße.", Instructions.voice(right, null))
        val rb = Maneuver(ManeuverKind.ROUNDABOUT, 0.0, LatLon(0.0, 0.0), "B 13", roundaboutExit = 2)
        assertEquals("In 200 Metern im Kreisverkehr die zweite Ausfahrt nehmen auf B 13.", Instructions.voice(rb, 200.0))
        val exit = Maneuver(ManeuverKind.EXIT_RIGHT, 0.0, LatLon(0.0, 0.0), null, exitNumber = "65", signToward = "Freising")
        assertEquals("In 2 Kilometern die Ausfahrt 65 nehmen, Richtung Freising.", Instructions.voice(exit, 2000.0))
        assertEquals("Ausfahrt 65 nehmen Richtung Freising", Instructions.text(exit))
        val arrive = Maneuver(ManeuverKind.ARRIVE_RIGHT, 0.0, LatLon(0.0, 0.0))
        assertEquals("Sie haben Ihr Ziel erreicht, es liegt rechts.", Instructions.voice(arrive, null))
        assertEquals("Weiter auf Staatsstraße 2084", Instructions.speakableRoad("Weiter auf St 2084"))
        val cam = Hazard("x", HazardType.SPEED_CAMERA, "Blitzer", "", LatLon(0.0, 0.0), maxSpeed = 80, source = "osm")
        assertEquals("Achtung, Blitzer in 500 Metern, erlaubt sind 80.", Instructions.hazardVoice(cam, 500.0))
    }

    @Test
    fun findsDestinationBuilding() {
        fun ring(lat: Double, lon: Double, d: Double = 0.0001) =
            listOf(LatLon(lat, lon), LatLon(lat, lon + d), LatLon(lat + d, lon + d), LatLon(lat + d, lon), LatLon(lat, lon))
        val house12 = Overpass.Element("way", 1, mapOf("building" to "house", "addr:street" to "Hauptstraße", "addr:housenumber" to "12"), null, null, ring(48.1, 11.5))
        val house14 = Overpass.Element("way", 2, mapOf("building" to "house"), null, null, ring(48.1, 11.5003))
        val addr14 = Overpass.Element("node", 3, mapOf("addr:street" to "Hauptstr.", "addr:housenumber" to "14"), 48.10005, 11.50035, null)
        val els = listOf(house12, house14, addr14)
        val r12 = BuildingFinder.find(LatLon(48.10005, 11.5002), "12", "Hauptstraße", els)
        assertEquals(house12.ring, r12?.ring)
        val r14 = BuildingFinder.find(LatLon(48.10005, 11.5002), "14", "Hauptstraße", els)
        assertEquals(house14.ring, r14?.ring)
        val inside = BuildingFinder.find(LatLon(48.10005, 11.50005), null, null, els)
        assertEquals(house12.ring, inside?.ring)
        assertTrue(BuildingFinder.numberMatches("10-16", "12"))
        assertTrue(BuildingFinder.numberMatches("12 a", "12a"))
        assertFalse(BuildingFinder.numberMatches("12a", "12"))
    }

    @Test
    fun parsesGeocoderResults() {
        val photon = JSONObject().put(
            "features", JSONArray().put(
                JSONObject().put("geometry", JSONObject().put("coordinates", JSONArray().put(11.5).put(48.1)))
                    .put("properties", JSONObject().put("street", "Hauptstraße").put("housenumber", "12").put("postcode", "85354").put("city", "Freising").put("osm_type", "N").put("osm_id", 42).put("countrycode", "DE"))
            )
        ).toString()
        val p = Geocoding.parsePhoton(photon)
        assertEquals(1, p.size)
        assertEquals("Hauptstraße 12", p[0].name)
        assertEquals("85354 Freising", p[0].address)
        assertEquals("12", p[0].houseNumber)
        val c = Geocoding.parseCoordinates("48,1374; 11,5755")
        assertEquals(48.1374, c!!.lat, 1e-9)
        val (gp, gq) = Geocoding.parseGeoUri("geo:0,0?q=Marienplatz%201%20M%C3%BCnchen")
        assertNull(gp)
        assertEquals("Marienplatz 1 München", gq)
        val (gp2, _) = Geocoding.parseGeoUri("geo:48.1,11.5")
        assertEquals(11.5, gp2!!.lon, 1e-9)
    }

    @Test
    fun parsesTankerkoenig() {
        val json = JSONObject().put("ok", true).put(
            "stations", JSONArray()
                .put(JSONObject().put("id", "1").put("name", "ARAL").put("brand", "ARAL").put("street", "MÜNCHNER STR.").put("houseNumber", "5").put("postCode", 85354).put("place", "FREISING").put("lat", 48.4).put("lng", 11.7).put("diesel", 1.659).put("e5", false).put("e10", 1.719).put("isOpen", true))
        ).toString()
        val l = Tankerkoenig.parseList(json)
        assertEquals(1, l.size)
        assertEquals(1.659, l[0].diesel!!, 1e-9)
        assertNull(l[0].e5)
        assertEquals("Münchner Str.", l[0].street)
        assertEquals("Freising", l[0].place)
        try {
            Tankerkoenig.parseList("{\"ok\":false,\"message\":\"apikey falsch\"}")
            throw AssertionError("Fehler erwartet")
        } catch (e: Tankerkoenig.ApiError) {
            assertEquals("apikey falsch", e.message)
        }
        val samples = Tankerkoenig.corridorSamples(Polyline(straightLine()), 0.0, 5000.0, 2000.0)
        assertEquals(4, samples.size)
    }

    @Test
    fun bavariaOutline() {
        assertTrue(Bavaria.contains(LatLon(48.137, 11.575)))   // München
        assertTrue(Bavaria.contains(LatLon(49.452, 11.077)))   // Nürnberg
        assertTrue(Bavaria.contains(LatLon(49.79, 9.95)))      // Würzburg
        assertTrue(Bavaria.contains(LatLon(49.01, 12.10)))     // Regensburg
        assertTrue(Bavaria.contains(LatLon(48.37, 10.90)))     // Augsburg
        assertFalse(Bavaria.contains(LatLon(48.78, 9.18)))     // Stuttgart
        assertFalse(Bavaria.contains(LatLon(50.11, 8.68)))     // Frankfurt
        assertFalse(Bavaria.contains(LatLon(47.27, 11.40)))    // Innsbruck
        assertFalse(Bavaria.contains(LatLon(50.08, 14.43)))    // Prag
        assertFalse(Bavaria.contains(LatLon(50.98, 11.03)))    // Erfurt
    }

    @Test
    fun historyKeepsTwentyNewestWithoutDuplicates() {
        var h = emptyList<Place>()
        for (i in 0 until 25) h = History.add(h, Place("Ziel $i", "", 48.0 + i * 0.01, 11.0))
        assertEquals(20, h.size)
        assertEquals("Ziel 24", h[0].name)
        h = History.add(h, Place("Ziel 10", "", 48.0 + 10 * 0.01, 11.0))
        assertEquals("Ziel 10", h[0].name)
        assertEquals(20, h.size)
        val back = History.fromJson(History.toJson(h))
        assertEquals(h, back)
    }

    @Test
    fun avoidSectionJsonRoundTrip() {
        val a = AvoidSection("id1", "A 9 bei Allershausen", listOf(LatLon(48.4, 11.6), LatLon(48.4002, 11.6003)), listOf(straightLine(5)), true, 123L)
        val back = AvoidSection.listFromJson(AvoidSection.listToJson(listOf(a)))
        assertEquals(1, back.size)
        assertEquals(a.name, back[0].name)
        assertEquals(2, back[0].points.size)
        assertEquals(5, back[0].shapes[0].size)
    }
}
