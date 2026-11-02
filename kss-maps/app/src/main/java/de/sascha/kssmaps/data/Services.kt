package de.sascha.kssmaps.data

import android.content.Context
import android.util.Log
import de.sascha.kssmaps.core.Autobahn
import de.sascha.kssmaps.core.AvoidSection
import de.sascha.kssmaps.core.BBox
import de.sascha.kssmaps.core.Bavaria
import de.sascha.kssmaps.core.BuildingFinder
import de.sascha.kssmaps.core.FuelStation
import de.sascha.kssmaps.core.Geo
import de.sascha.kssmaps.core.Geocoding
import de.sascha.kssmaps.core.Hazard
import de.sascha.kssmaps.core.HazardType
import de.sascha.kssmaps.core.LatLon
import de.sascha.kssmaps.core.Overpass
import de.sascha.kssmaps.core.Place
import de.sascha.kssmaps.core.Route
import de.sascha.kssmaps.core.RouteSource
import de.sascha.kssmaps.core.SpeedLimitRange
import de.sascha.kssmaps.core.Tankerkoenig
import de.sascha.kssmaps.core.TomTom
import de.sascha.kssmaps.core.Uta
import de.sascha.kssmaps.core.Valhalla
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.UUID

/** Alle Netzwerkzugriffe an einer Stelle. Jede Funktion laeuft auf Dispatchers.IO. */
class Services(private val ctx: Context, private val prefs: Prefs) {

    companion object {
        private const val TAG = "KSSMaps"
        private const val DAY = 24L * 3600 * 1000
    }

    /** Letzte Fehlermeldung des Routers (fuer die Anzeige, wenn TomTom auf Valhalla zurueckfaellt). */
    @Volatile var lastRoutingNote: String? = null

    // ---------------------------------------------------------------------------------------
    // Suche
    // ---------------------------------------------------------------------------------------

    suspend fun search(q: String, bias: LatLon?): List<Place> = withContext(Dispatchers.IO) {
        Geocoding.parseCoordinates(q)?.let { c ->
            return@withContext listOf(Place("Koordinate", c.fmt(), c.lat, c.lon))
        }
        try {
            val r = Geocoding.parsePhoton(Http.get(Geocoding.photonSearchUrl(q, bias), 10_000))
            if (r.isNotEmpty()) return@withContext Geocoding.preferAddress(q, r)
        } catch (e: Exception) {
            Log.w(TAG, "Photon: ${e.message}")
        }
        Geocoding.preferAddress(q, Geocoding.dedupe(Geocoding.parseNominatim(Http.get(Geocoding.nominatimSearchUrl(q), 12_000))))
    }

    suspend fun reverse(p: LatLon): Place? = withContext(Dispatchers.IO) {
        try {
            Geocoding.parsePhoton(Http.get(Geocoding.photonReverseUrl(p), 8_000)).firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Routen
    // ---------------------------------------------------------------------------------------

    suspend fun routes(
        points: List<LatLon>,
        heading: Double?,
        alternatives: Int,
        extraAvoid: List<LatLon> = emptyList(),
        costFactors: List<Pair<List<LatLon>, Double>> = emptyList(),
        forceValhalla: Boolean = false,
    ): List<Route> = withContext(Dispatchers.IO) {
        val avoid = prefs.avoidSections.filter { it.enabled }.flatMap { it.points } + extraAvoid
        val opts = prefs.routeOptions()
        val key = prefs.tomtomKey
        lastRoutingNote = null
        if (key.isNotBlank() && !forceValhalla) {
            try {
                val url = TomTom.routeUrl(points, key, opts, alternatives, heading)
                val body = if (avoid.isEmpty()) Http.get(url, 20_000)
                else Http.post(url, TomTom.routeBody(mergeAvoid(avoid)), timeoutMs = 25_000)
                val r = TomTom.parseRoutes(body)
                if (r.isNotEmpty()) return@withContext r
            } catch (e: Http.HttpError) {
                lastRoutingNote = "TomTom: ${TomTom.errorMessage(e.body)} – Route ohne Live-Verkehr"
                Log.w(TAG, "TomTom ${e.code}: ${e.body.take(300)}")
            } catch (e: Exception) {
                lastRoutingNote = "TomTom nicht erreichbar – Route ohne Live-Verkehr"
                Log.w(TAG, "TomTom: ${e.message}")
            }
        }
        val req = Valhalla.routeRequest(points, opts, avoid, alternatives, heading, costFactors)
        try {
            Valhalla.parseRoutes(Http.post("${Valhalla.BASE}/route", req, timeoutMs = 30_000))
        } catch (e: Http.HttpError) {
            if (costFactors.isNotEmpty()) {
                // aeltere Server kennen linear_cost_factors evtl. nicht -> ohne nochmal
                Valhalla.parseRoutes(Http.post("${Valhalla.BASE}/route", Valhalla.routeRequest(points, opts, avoid, alternatives, heading), timeoutMs = 30_000))
            } else throw Valhalla.ApiError(Valhalla.errorMessage(e.body))
        }
    }

    /** TomTom erlaubt max. 10 Sperrflaechen: nahe Punkte zu einem Rechteck zusammenfassen. */
    private fun mergeAvoid(points: List<LatLon>): List<BBox> {
        val boxes = ArrayList<BBox>()
        for (p in points) {
            val b = BBox.around(p, 40.0)
            val idx = boxes.indexOfFirst { it.expanded(60.0).intersects(b) }
            if (idx >= 0) {
                val o = boxes[idx]
                boxes[idx] = BBox(minOf(o.minLat, b.minLat), minOf(o.minLon, b.minLon), maxOf(o.maxLat, b.maxLat), maxOf(o.maxLon, b.maxLon))
            } else boxes.add(b)
        }
        return boxes.take(10)
    }

    // ---------------------------------------------------------------------------------------
    // Verkehr (Autobahn GmbH)
    // ---------------------------------------------------------------------------------------

    private val autobahnCache = HashMap<String, Pair<Long, List<Hazard>>>()

    suspend fun autobahnEvents(roads: Set<String>): List<Hazard> = coroutineScope {
        roads.take(12).flatMap { road ->
            Autobahn.SERVICES.map { svc -> async(Dispatchers.IO) { fetchAutobahn(road, svc) } }
        }.awaitAll().flatten()
    }

    private fun fetchAutobahn(road: String, svc: String): List<Hazard> {
        val key = "$road/$svc"
        val maxAge = if (svc == "warning") 150_000L else 20 * 60_000L
        val now = System.currentTimeMillis()
        synchronized(autobahnCache) {
            autobahnCache[key]?.let { if (now - it.first < maxAge) return it.second }
        }
        return try {
            val list = Autobahn.parse(Http.get(Autobahn.url(road, svc), 20_000), svc, road)
            synchronized(autobahnCache) { autobahnCache[key] = now to list }
            list
        } catch (e: Exception) {
            Log.w(TAG, "Autobahn $key: ${e.message}")
            synchronized(autobahnCache) { autobahnCache[key]?.second } ?: emptyList()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Verkehrsueberwachung aus OpenStreetMap: Blitzer, Rotlicht, Abschnittskontrolle,
    // Abstandsmessung, Mindestabstand. Bayern in 4 Kacheln (kleinere, robustere Abfragen),
    // Datei-Cache 7 Tage. Zusaetzlich je Route eine Korridor-Abfrage als Rueckfallebene.
    // ---------------------------------------------------------------------------------------

    @Volatile private var cameraCache: List<Hazard>? = null
    private val cameraLock = Mutex()
    private val tileFailedAt = LongArray(4)

    fun camerasCached(): List<Hazard> = cameraCache ?: emptyList()

    /** Mehrere gleichzeitige Aufrufer warten auf denselben Download (Mutex). */
    suspend fun cameras(forceRefresh: Boolean = false): List<Hazard> = cameraLock.withLock { loadCameras(forceRefresh) }

    private fun bavariaTiles(): List<BBox> {
        val b = Bavaria.BBOX
        val midLat = (b.minLat + b.maxLat) / 2
        val midLon = (b.minLon + b.maxLon) / 2
        return listOf(
            BBox(b.minLat, b.minLon, midLat, midLon), BBox(b.minLat, midLon, midLat, b.maxLon),
            BBox(midLat, b.minLon, b.maxLat, midLon), BBox(midLat, midLon, b.maxLat, b.maxLon),
        )
    }

    /** Startbestand aus der App (beim Bauen geladen), falls noch nichts heruntergeladen wurde. */
    @Volatile private var seed: List<Hazard>? = null

    private fun seedHazards(): List<Hazard> {
        seed?.let { return it }
        val list = try {
            ctx.assets.open("enforcement/bayern.json").bufferedReader().use { Overpass.parseEnforcement(it.readText()) }
        } catch (e: Exception) {
            emptyList()
        }
        seed = list
        return list
    }

    /** Geparste Kacheln im Speicher (Datei-Zeitstempel -> Objekte), damit nicht jedes Mal neu gelesen wird. */
    private val tileMem = arrayOfNulls<Pair<Long, List<Hazard>>>(4)

    private fun readTile(i: Int, f: File): List<Hazard>? {
        if (!f.exists()) return null
        val lm = f.lastModified()
        tileMem[i]?.let { if (it.first == lm) return it.second }
        return try {
            Overpass.parseEnforcement(f.readText()).also { tileMem[i] = lm to it }
        } catch (e: Exception) {
            null
        }
    }

    /** Vorhandener Bestand ohne Download: Dateien, fehlende Kacheln aus dem Startbestand. */
    private fun assemble(dir: File, tiles: List<BBox>): List<Hazard> {
        val out = ArrayList<Hazard>()
        for ((i, b) in tiles.withIndex()) {
            val got = readTile(i, File(dir, "tile$i.json")) ?: seedHazards().filter { b.contains(it.point) }
            out.addAll(got)
        }
        return out.distinctBy { it.id }
    }

    private suspend fun loadCameras(forceRefresh: Boolean): List<Hazard> = withContext(Dispatchers.IO) {
        val dir = File(ctx.filesDir, "enforcement").also { it.mkdirs() }
        val now = System.currentTimeMillis()
        val tiles = bavariaTiles()
        // Sofort den vorhandenen Bestand bereitstellen – der Download unten kann dauern.
        if (cameraCache == null) {
            val have = assemble(dir, tiles)
            if (have.isNotEmpty()) cameraCache = have
        }
        val cached = cameraCache
        val allFresh = tiles.indices.all { i -> File(dir, "tile$i.json").let { it.exists() && now - it.lastModified() < 7 * DAY } }
        if (!forceRefresh && cached != null && allFresh) return@withContext cached
        val anyTry = tiles.indices.any { i -> forceRefresh || now - tileFailedAt[i] > 15 * 60_000 }
        if (!forceRefresh && cached != null && !anyTry) return@withContext cached
        for ((i, b) in tiles.withIndex()) {
            val f = File(dir, "tile$i.json")
            val fresh = f.exists() && now - f.lastModified() < 7 * DAY
            val mayTry = forceRefresh || now - tileFailedAt[i] > 15 * 60_000
            if ((!fresh || forceRefresh) && mayTry) {
                try {
                    val json = overpass(Overpass.enforcementBBoxQuery(b), 60_000)
                    Overpass.parseEnforcement(json) // nur gueltige Antworten speichern
                    f.writeText(json)
                } catch (e: Exception) {
                    tileFailedAt[i] = now
                    Log.w(TAG, "Blitzer-Kachel $i: ${e.message}")
                }
            }
        }
        val result = assemble(dir, tiles)
        if (result.isNotEmpty() || cached == null) cameraCache = result
        Log.i(TAG, "Verkehrsueberwachung: ${result.size} Objekte (Blitzer ${result.count { it.type == HazardType.SPEED_CAMERA }}, Abstand ${result.count { it.type == HazardType.DISTANCE_CHECK }})")
        cameraCache ?: emptyList()
    }

    private val corridorCache = LinkedHashMap<String, List<Hazard>>()

    /**
     * Blitzer/Abstandsmessungen direkt entlang der Route (Overpass "around" um den Linienzug).
     * Rueckfallebene, wenn der Bayern-Cache fehlt, und fuer Routen ausserhalb Bayerns.
     */
    suspend fun enforcementAlong(route: Route): List<Hazard> = withContext(Dispatchers.IO) {
        val pts = Geo.simplify(route.geometry.points, 20.0)
        if (pts.size < 2) return@withContext emptyList()
        val key = pts.first().fmt() + pts.last().fmt() + pts.size
        synchronized(corridorCache) { corridorCache[key]?.let { return@withContext it } }
        // Bestand aus dem Bayern-Cache/Startbestand, der in den Rechtecken liegt, gleich mitnehmen
        val boxes = Overpass.corridorBoxes(pts).take(40)
        val res = try {
            Overpass.parseEnforcement(overpass(Overpass.enforcementBoxesQuery(boxes), 45_000)).distinctBy { it.id }
        } catch (e: Exception) {
            Log.w(TAG, "Blitzer entlang Route: ${e.message}")
            return@withContext seedHazards().filter { h -> boxes.any { it.contains(h.point) } }
        }
        synchronized(corridorCache) {
            corridorCache[key] = res
            while (corridorCache.size > 6) corridorCache.remove(corridorCache.keys.first())
        }
        res
    }

    private fun overpass(query: String, timeoutMs: Int): String {
        var last: Exception? = null
        for (ep in Overpass.ENDPOINTS) {
            try {
                return Http.post(ep, "data=" + URLEncoder.encode(query, "UTF-8"), "application/x-www-form-urlencoded", timeoutMs)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Overpass nicht erreichbar")
    }

    // ---------------------------------------------------------------------------------------
    // Tempolimits (Valhalla trace_attributes)
    // ---------------------------------------------------------------------------------------

    suspend fun speedLimits(route: Route, from: Double, length: Double): List<SpeedLimitRange> = withContext(Dispatchers.IO) {
        val slice = route.geometry.slice(from, from + length)
        if (slice.size < 2) return@withContext emptyList()
        val body = Http.post(
            "${Valhalla.BASE}/trace_attributes",
            Valhalla.traceAttributesRequest(slice, route.source == RouteSource.VALHALLA), timeoutMs = 30_000
        )
        Valhalla.parseTraceAttributes(body, from).limits
    }

    /** Freie Fahrt: letzte GPS-Punkte auf die Strasse legen -> Tempolimit + Strassenname. */
    suspend fun matchTrace(points: List<LatLon>): Valhalla.TraceResult? = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext null
        try {
            Valhalla.parseTraceAttributes(
                Http.post("${Valhalla.BASE}/trace_attributes", Valhalla.traceAttributesRequest(points, false), timeoutMs = 10_000), 0.0
            )
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Abschnitt meiden: Strasse am Tippunkt bestimmen
    // ---------------------------------------------------------------------------------------

    suspend fun avoidSectionAt(p: LatLon): AvoidSection? = withContext(Dispatchers.IO) {
        val edges = Valhalla.parseLocate(Http.post("${Valhalla.BASE}/locate", Valhalla.locateRequest(p, 45), timeoutMs = 12_000))
        if (edges.isEmpty()) return@withContext null
        val nearest = edges.minByOrNull { Geo.distance(p, it.point) } ?: return@withContext null
        val sameRoad = edges.filter { e ->
            Geo.distance(p, e.point) < 50.0 && e.roadClass == nearest.roadClass &&
                (nearest.names.isEmpty() || e.names.any { it in nearest.names })
        }
        // Je Fahrbahn (OSM-Weg) ein Sperrpunkt – bei Autobahnen also beide Richtungen.
        val perWay = sameRoad.groupBy { it.wayId }.values.mapNotNull { l -> l.minByOrNull { Geo.distance(p, it.point) } }
            .sortedBy { Geo.distance(p, it.point) }.take(3)
        val shapes = perWay.map { it.shape }.filter { it.size >= 2 }
        val road = nearest.names.firstOrNull { Valhalla.isRef(it) } ?: nearest.names.firstOrNull() ?: "Straße"
        val near = try {
            Geocoding.parsePhoton(Http.get(Geocoding.photonReverseUrl(p), 8_000)).firstOrNull()?.let { it.city ?: it.name }
        } catch (e: Exception) {
            null
        }
        AvoidSection(
            id = UUID.randomUUID().toString(),
            name = if (near != null) "$road bei $near" else road,
            points = perWay.map { it.point },
            shapes = shapes,
            enabled = true,
            created = System.currentTimeMillis(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Zielgebaeude
    // ---------------------------------------------------------------------------------------

    /** null = kein passendes Gebaeude; Netzwerkfehler werden geworfen (Aufrufer versucht es erneut). */
    suspend fun building(place: Place): BuildingFinder.Result? = withContext(Dispatchers.IO) {
        val els = Overpass.parseElements(overpass(Overpass.buildingsQuery(place.pos), 45_000))
        BuildingFinder.find(place.pos, place.houseNumber, place.street, els)
    }

    // ---------------------------------------------------------------------------------------
    // Tankstellen
    // ---------------------------------------------------------------------------------------

    @Volatile private var utaOsm: List<Pair<LatLon, Boolean>>? = null

    /** Fuer Downloads, auf die niemand warten soll. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var utaLoading = false

    /** OSM-Tags payment:uta in Bayern. Laedt im Hintergrund – die Tankstellensuche wartet nie darauf. */
    private fun utaFromOsm(): List<Pair<LatLon, Boolean>> {
        utaOsm?.let { return it }
        val file = File(ctx.filesDir, "uta_osm.json")
        val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > 14 * DAY
        if (stale && !utaLoading) {
            utaLoading = true
            io.launch {
                try {
                    val txt = overpass(Overpass.utaQuery(), 90_000)
                    val l = Overpass.parseUta(txt)
                    file.writeText(txt)
                    utaOsm = l
                } catch (e: Exception) {
                    Log.w(TAG, "UTA-Tags: ${e.message}")
                } finally {
                    utaLoading = false
                }
            }
        }
        if (!file.exists()) return emptyList()
        return try {
            Overpass.parseUta(file.readText()).also { if (!stale) utaOsm = it }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** true, wenn die letzte Tankstellensuche ohne Preise lief (kein Tankerkoenig-Schluessel). */
    @Volatile var fuelWithoutPrices = false
        private set

    /** Ohne Schluessel: Tankstellen aus OpenStreetMap entlang der Route bzw. im Umkreis (ohne Preise). */
    private fun osmFuelStations(route: Route?, along: Double, here: LatLon): List<FuelStation> {
        val g = route?.geometry
        val boxes = if (g != null && g.length - along > 3000) {
            val ahead = prefs.fuelCorridorKm * 1000.0
            val pts = g.points.filterIndexed { i, _ -> g.cum[i] >= along - 300 && g.cum[i] <= along + ahead }
            Overpass.corridorBoxes(Geo.simplify(pts, 30.0), 15_000.0, 1500.0).take(12)
        } else listOf(BBox(here.lat, here.lon, here.lat, here.lon).expanded(8000.0))
        val json = try {
            overpass(Overpass.fuelQuery(boxes), 30_000)
        } catch (e: Exception) {
            Log.w(TAG, "Tankstellen (OSM): ${e.message}")
            throw MissingKey()
        }
        val osm = utaFromOsm()
        val brands = prefs.utaBrands
        return Overpass.parseFuel(json).map { (st, tag) ->
            val t = tag ?: osm.filter { Geo.distance(it.first, st.pos) < 60.0 }.minByOrNull { Geo.distance(it.first, st.pos) }?.second
            st.copy(uta = Uta.status(st.brand, st.name, brands, t, prefs.utaOverride(st.id)))
        }
    }

    class MissingKey : Exception("Für Tankpreise wird ein kostenloser Tankerkönig-API-Schlüssel benötigt (Einstellungen).")

    /** Tankstellen entlang der Route (Korridor) bzw. im Umkreis, mit UTA-Status. */
    suspend fun fuelStations(route: Route?, along: Double, here: LatLon): List<FuelStation> = withContext(Dispatchers.IO) {
        val key = prefs.tankerkoenigKey
        fuelWithoutPrices = key.isBlank()
        if (key.isBlank()) return@withContext osmFuelStations(route, along, here)
        val samples = if (route != null && route.geometry.length - along > 3000) {
            Tankerkoenig.corridorSamples(route.geometry, along, prefs.fuelCorridorKm * 1000.0, 9000.0)
        } else listOf(here)
        val radius = if (samples.size > 1) 5.0 else 8.0
        val all = ArrayList<FuelStation>()
        var firstError: Exception? = null
        for (s in samples.take(12)) {
            try {
                all.addAll(Tankerkoenig.parseList(Http.get(Tankerkoenig.listUrl(s, radius, key), 12_000)))
            } catch (e: Tankerkoenig.ApiError) {
                throw e
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        if (all.isEmpty() && firstError != null) throw firstError!!
        val osm = utaFromOsm()
        val brands = prefs.utaBrands
        all.distinctBy { it.id }.map { st ->
            val tag = osm.filter { Geo.distance(it.first, st.pos) < 60.0 }.minByOrNull { Geo.distance(it.first, st.pos) }?.second
            st.copy(uta = Uta.status(st.brand, st.name, brands, tag, prefs.utaOverride(st.id)))
        }
    }

    // ---------------------------------------------------------------------------------------
    // App-Update (gleiches Muster wie die anderen Apps: version.json im Release-Repo)
    // ---------------------------------------------------------------------------------------

    data class UpdateInfo(val version: String, val notes: List<String>, val apkUrl: String)

    suspend fun checkUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val o = org.json.JSONObject(
                Http.get("https://raw.githubusercontent.com/Schmenkman/kss-app-releases/main/kss-maps/version.json?t=" + System.currentTimeMillis(), 10_000)
            )
            val notes = o.optJSONArray("notes")
            UpdateInfo(
                o.optString("version"),
                (0 until (notes?.length() ?: 0)).map { notes!!.optString(it) },
                o.optString("apkUrl"),
            )
        } catch (e: Exception) {
            null
        }
    }
}
