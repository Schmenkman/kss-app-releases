package de.sascha.bayernnavi.data

import android.content.Context
import android.util.Log
import de.sascha.bayernnavi.core.Autobahn
import de.sascha.bayernnavi.core.AvoidSection
import de.sascha.bayernnavi.core.BBox
import de.sascha.bayernnavi.core.Bavaria
import de.sascha.bayernnavi.core.BuildingFinder
import de.sascha.bayernnavi.core.FuelStation
import de.sascha.bayernnavi.core.Geo
import de.sascha.bayernnavi.core.Geocoding
import de.sascha.bayernnavi.core.Hazard
import de.sascha.bayernnavi.core.LatLon
import de.sascha.bayernnavi.core.Overpass
import de.sascha.bayernnavi.core.Place
import de.sascha.bayernnavi.core.Route
import de.sascha.bayernnavi.core.RouteSource
import de.sascha.bayernnavi.core.SpeedLimitRange
import de.sascha.bayernnavi.core.Tankerkoenig
import de.sascha.bayernnavi.core.TomTom
import de.sascha.bayernnavi.core.Uta
import de.sascha.bayernnavi.core.Valhalla
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
        private const val TAG = "BayernNavi"
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
            if (r.isNotEmpty()) return@withContext r
        } catch (e: Exception) {
            Log.w(TAG, "Photon: ${e.message}")
        }
        Geocoding.parseNominatim(Http.get(Geocoding.nominatimSearchUrl(q), 12_000))
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
    // Blitzer (OpenStreetMap, ganz Bayern, Datei-Cache 7 Tage)
    // ---------------------------------------------------------------------------------------

    @Volatile private var cameraCache: List<Hazard>? = null
    private val cameraLock = Mutex()

    fun camerasCached(): List<Hazard> = cameraCache ?: emptyList()

    /** Mehrere gleichzeitige Aufrufer warten auf denselben Download (Mutex). */
    suspend fun cameras(forceRefresh: Boolean = false): List<Hazard> = cameraLock.withLock { loadCameras(forceRefresh) }

    private suspend fun loadCameras(forceRefresh: Boolean): List<Hazard> = withContext(Dispatchers.IO) {
        val file = File(ctx.filesDir, "cameras_by.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 7 * DAY
        if (!forceRefresh) {
            cameraCache?.let { if (fresh) return@withContext it }
            if (file.exists()) {
                try {
                    val c = Overpass.parseCameras(file.readText())
                    cameraCache = c
                    if (fresh) return@withContext c
                } catch (e: Exception) {
                    Log.w(TAG, "Blitzer-Cache defekt: ${e.message}")
                }
            }
        }
        try {
            val json = overpass(Overpass.speedCamerasQuery(), 150_000)
            val c = Overpass.parseCameras(json)
            if (c.isNotEmpty()) {
                file.writeText(json)
                cameraCache = c
            }
            c.ifEmpty { cameraCache ?: emptyList() }
        } catch (e: Exception) {
            Log.w(TAG, "Blitzer laden: ${e.message}")
            cameraCache ?: emptyList()
        }
    }

    /** Fuer Routen, die Bayern verlassen: Blitzer im Rechteck um den Teil ausserhalb. */
    suspend fun camerasOutsideBavaria(route: Route): List<Hazard> = withContext(Dispatchers.IO) {
        val outside = route.geometry.points.filterIndexed { i, p -> i % 20 == 0 && !Bavaria.contains(p) }
        if (outside.isEmpty()) return@withContext emptyList()
        val box = BBox.of(outside)?.expanded(2000.0) ?: return@withContext emptyList()
        // zu grosse Flaechen nicht abfragen (Overpass-Last)
        val diag = Geo.distance(LatLon(box.minLat, box.minLon), LatLon(box.maxLat, box.maxLon))
        if (diag > 400_000) return@withContext emptyList()
        try {
            Overpass.parseCameras(overpass(Overpass.speedCamerasBBoxQuery(box), 90_000))
        } catch (e: Exception) {
            emptyList()
        }
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

    suspend fun building(place: Place): BuildingFinder.Result? = withContext(Dispatchers.IO) {
        try {
            val els = Overpass.parseElements(overpass(Overpass.buildingsQuery(place.pos), 30_000))
            BuildingFinder.find(place.pos, place.houseNumber, place.street, els)
        } catch (e: Exception) {
            Log.w(TAG, "Gebäude: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Tankstellen
    // ---------------------------------------------------------------------------------------

    @Volatile private var utaOsm: List<Pair<LatLon, Boolean>>? = null

    private fun utaFromOsm(): List<Pair<LatLon, Boolean>> {
        utaOsm?.let { return it }
        val file = File(ctx.filesDir, "uta_osm.json")
        try {
            if (!file.exists() || System.currentTimeMillis() - file.lastModified() > 14 * DAY) {
                file.writeText(overpass(Overpass.utaQuery(), 90_000))
            }
            val l = Overpass.parseUta(file.readText())
            utaOsm = l
            return l
        } catch (e: Exception) {
            return emptyList()
        }
    }

    class MissingKey : Exception("Für Tankpreise wird ein kostenloser Tankerkönig-API-Schlüssel benötigt (Einstellungen).")

    /** Tankstellen entlang der Route (Korridor) bzw. im Umkreis, mit UTA-Status. */
    suspend fun fuelStations(route: Route?, along: Double, here: LatLon): List<FuelStation> = withContext(Dispatchers.IO) {
        val key = prefs.tankerkoenigKey
        if (key.isBlank()) throw MissingKey()
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
                Http.get("https://raw.githubusercontent.com/Schmenkman/kss-app-releases/main/bayern-navi/version.json?t=" + System.currentTimeMillis(), 10_000)
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
