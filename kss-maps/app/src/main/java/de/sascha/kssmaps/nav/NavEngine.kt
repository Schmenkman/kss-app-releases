package de.sascha.kssmaps.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import de.sascha.kssmaps.NavService
import de.sascha.kssmaps.core.AnnouncePlan
import de.sascha.kssmaps.core.AvoidSection
import de.sascha.kssmaps.core.Bavaria
import de.sascha.kssmaps.core.BuildingFinder
import de.sascha.kssmaps.core.FuelRanking
import de.sascha.kssmaps.core.FuelStation
import de.sascha.kssmaps.core.Geo
import de.sascha.kssmaps.core.Hazard
import de.sascha.kssmaps.core.HazardMatcher
import de.sascha.kssmaps.core.HazardType
import de.sascha.kssmaps.core.Instructions
import de.sascha.kssmaps.core.LatLon
import de.sascha.kssmaps.core.Place
import de.sascha.kssmaps.core.RankedStation
import de.sascha.kssmaps.core.Route
import de.sascha.kssmaps.core.RouteHazard
import de.sascha.kssmaps.core.RouteSource
import de.sascha.kssmaps.core.RouteTracker
import de.sascha.kssmaps.core.SpeedLimitRange
import de.sascha.kssmaps.core.Sun
import de.sascha.kssmaps.data.Prefs
import de.sascha.kssmaps.data.Services
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Zentrale Navigationslogik – EIN Objekt fuer Handy und Android Auto. Beide Oberflaechen lesen
 * nur den Zustand hier und rufen die Aktionen auf; so zeigen sie immer dasselbe.
 * Alles laeuft auf dem Main-Thread, Netzwerk in Coroutinen.
 */
class NavEngine(
    private val ctx: Context,
    val prefs: Prefs,
    val services: Services,
    val voice: Voice,
) {
    enum class Mode { IDLE, PREVIEW, NAVIGATING }

    data class Alert(
        val id: String,
        val title: String,
        val text: String,
        val type: HazardType? = null,
        val actionLabel: String? = null,
        val action: (() -> Unit)? = null,
    )

    interface Listener {
        fun onStateChanged() {}
        fun onLocationChanged() {}
        fun onAlert(alert: Alert) {}
    }

    companion object {
        private const val TAG = "KSSMaps"
    }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Hintergrund fuer Netzabfragen, auf die nur begrenzt gewartet wird. */
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Wartet hoechstens [ms] auf [block]. withTimeoutOrNull allein reicht nicht: blockierende
     * HTTP-Aufrufe reagieren nicht auf Abbruch, die Route wuerde minutenlang "berechnet".
     * Deshalb laeuft [block] in einem eigenen Bereich weiter (fuellt die Caches), und
     * [onLate] bekommt ein verspaetetes Ergebnis.
     */
    private suspend fun <T> upTo(ms: Long, onLate: ((T) -> Unit)? = null, block: suspend () -> T): T? {
        val d = bg.async { block() }
        val r = try {
            withTimeoutOrNull(ms) { d.await() }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            null
        } catch (e: Exception) {
            null
        }
        if (r == null && onLate != null && !d.isCompleted) {
            scope.launch {
                val v = try {
                    d.await()
                } catch (e: Exception) {
                    return@launch
                }
                onLate(v)
            }
        }
        return r
    }

    private var lateJob: Job? = null

    /** Spaet eingetroffene Blitzer-Daten in die angezeigten Routen uebernehmen (gebuendelt). */
    private fun lateEnforcement() {
        if (lateJob?.isActive == true) return
        lateJob = scope.launch {
            delay(400)
            reannotateShown()
        }
    }
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun changed() = listeners.forEach { it.onStateChanged() }
    private fun alert(a: Alert) = listeners.forEach { it.onAlert(a) }

    // ---------------------------------------------------------------------------------------
    // Zustand
    // ---------------------------------------------------------------------------------------

    var mode = Mode.IDLE
        private set
    var destination: Place? = null
        private set
    /** Eigener Startpunkt fuer die Planung (null = aktueller Standort). */
    var origin: Place? = null
        private set
    var fuelStop: FuelStation? = null
        private set
    var routes: List<Route> = emptyList()
        private set
    var selectedIndex = 0
        private set
    val selectedRoute: Route? get() = routes.getOrNull(selectedIndex)
    var activeRoute: Route? = null
        private set
    var state: RouteTracker.State? = null
        private set
    var loading = false
        private set
    var message: String? = null
        private set
    var building: BuildingFinder.Result? = null
        private set
    var betterRoute: Route? = null
        private set
    var betterRouteSavingSec = 0.0
        private set
    var arrived = false
        private set
    var rerouting = false
        private set

    var location: Location? = null
        private set
    val position: LatLon? get() = location?.let { LatLon(it.latitude, it.longitude) }
    var speedKmh = 0.0
        private set
    var heading: Double? = null
        private set
    /** km/h; -1 = unbegrenzt; null = unbekannt. */
    var speedLimit: Int? = null
        private set
    var currentRoad: String? = null
        private set
    var overspeed = false
        private set
    /** Naechste Meldung innerhalb des Warnabstands: (Meldung, Entfernung). */
    var nearestHazard: Pair<RouteHazard, Double>? = null
        private set
    /** Freie Fahrt: Blitzer voraus. */
    var cameraAhead: Pair<Hazard, Double>? = null
        private set

    private var tracker: RouteTracker? = null
    /** Schon gesprochene Ansagestufen je Manoever – Schluessel ist der Ort, nicht der Index,
     *  damit eine aufgefrischte/neu berechnete Route dieselbe Kreuzung nicht nochmal ansagt. */
    private val spokenStages = HashMap<String, MutableSet<AnnouncePlan.Stage>>()

    private fun maneuverKey(m: de.sascha.kssmaps.core.Maneuver): String =
        "${m.kind}@${(m.point.lat * 20000).roundToInt()},${(m.point.lon * 20000).roundToInt()}"
    private val warned = HashSet<String>()
    private var lastReroute = 0L
    private var lastRerouteVoice = 0L
    private var routeJob: Job? = null
    private var trafficJob: Job? = null
    private var speedLimitJob: Job? = null
    private var buildingJob: Job? = null
    private var speedLimitCoveredTo = 0.0
    private var speedLimitRoute: Route? = null
    private val freeTrace = ArrayList<LatLon>()
    private var lastFreeTrace = 0L
    private var lastFreeTracePos: LatLon? = null
    private var overspeedSince = 0L
    private var lastOverspeedBeep = 0L
    private var pendingRouteCalc = false
    /** "Los" bei eigenem Startpunkt: erst ab dem Standort neu berechnen, dann automatisch starten. */
    private var startWhenReady = false
    private var fuelArrivalSpoken = false
    private var lastSavedPos = 0L

    init {
        voice.muted = prefs.voiceMuted
        // Blitzer-Daten im Hintergrund laden (Datei-Cache, woechentlich erneuert). Kommen sie erst
        // nach der Routenberechnung an, werden die angezeigten Routen nachtraeglich ergaenzt.
        scope.launch {
            if (prefs.camerasEnabled) {
                val had = services.camerasCached().isNotEmpty()
                try {
                    services.cameras()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Blitzer laden: ${e.message}")
                }
                if (!had && services.camerasCached().isNotEmpty()) lateEnforcement()
            }
        }
    }

    private suspend fun reannotateShown() {
        when (mode) {
            Mode.NAVIGATING -> activeRoute?.let { annotate(listOf(it)) }
            Mode.PREVIEW -> if (!loading && routes.isNotEmpty()) annotate(routes)
            else -> return
        }
        changed()
    }

    // ---------------------------------------------------------------------------------------
    // Standort
    // ---------------------------------------------------------------------------------------

    private val lm: LocationManager = ctx.getSystemService(LocationManager::class.java)
    private var locationUsers = 0
    private var locating = false
    private var lastGpsFix = 0L

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastGpsFix = SystemClock.elapsedRealtime()
            onLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val netListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Netzwerkortung nur, solange GPS schweigt (z. B. im Haus beim Planen).
            if (SystemClock.elapsedRealtime() - lastGpsFix > 15_000) onLocation(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun hasLocationPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Jede sichtbare Oberflaeche (Handy, Auto) meldet sich an; Navigation haelt GPS zusaetzlich am Laufen. */
    fun acquireLocation() {
        locationUsers++
        startLocation()
    }

    fun releaseLocation() {
        locationUsers = max(0, locationUsers - 1)
        if (locationUsers == 0 && mode != Mode.NAVIGATING) stopLocation()
    }

    @SuppressLint("MissingPermission")
    fun startLocation() {
        if (locating || !hasLocationPermission()) return
        try {
            if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                // 0,5 s: Android verwirft sonst bei leicht schwankendem Takt einzelne echte Punkte
                // (das GPS liefert meist ohnehin 1 Punkt pro Sekunde; Wiederholungen filtert onLocation).
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, gpsListener, Looper.getMainLooper())
            }
            if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 4000L, 5f, netListener, Looper.getMainLooper())
            }
            locating = true
            if (location == null) {
                val last = listOfNotNull(
                    runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull(),
                    runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull(),
                ).maxByOrNull { it.time }
                if (last != null) onLocation(last)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Standort: ${e.message}")
        }
    }

    private fun stopLocation() {
        if (!locating) return
        lm.removeUpdates(gpsListener)
        lm.removeUpdates(netListener)
        locating = false
    }

    private fun onLocation(loc: Location) {
        val prev = location
        // Derselbe Punkt kurz danach noch einmal (manche Geraete, der Emulator): beim Fahren veraltet –
        // verwerfen, sonst "steht" das Auto fuer einen Moment (Tempo, Pfeil, Ansagen).
        if (prev != null && speedKmh > 7.0 && loc.time - prev.time in 0..800 && prev.distanceTo(loc) < 0.5f) return
        location = loc
        val now = SystemClock.elapsedRealtime()
        val prevSp = speedKmh / 3.6
        // Ohne Tempoangabe erst einmal das bisherige Tempo halten: manche Geraete (und der Emulator)
        // melden denselben Punkt kurz hintereinander doppelt – daraus wuerde sonst Tempo 0.
        var sp = if (loc.hasSpeed()) loc.speed.toDouble() else prevSp
        if (prev != null) {
            // Ohne Tempoangabe (oder unplausibel 0 trotz deutlicher Bewegung): aus den Positionen
            val dt = (loc.time - prev.time) / 1000.0
            if (dt in 0.5..10.0) {
                val d = prev.distanceTo(loc).toDouble()
                val calc = d / dt
                val acc = maxOf(loc.accuracy, prev.accuracy).toDouble()
                if (calc > 85.0) {
                    // Positionssprung (GPS-Aussetzer): kein Tempo daraus ableiten
                } else if (!loc.hasSpeed()) sp = calc
                else if (sp < 0.5 && calc > 3.0 && acc < 25.0 && d > 2 * acc) sp = calc
            } else if (!loc.hasSpeed() && dt > 10.0) sp = 0.0
        } else if (!loc.hasSpeed()) sp = 0.0
        speedKmh = sp.coerceIn(0.0, 85.0) * 3.6
        if (loc.hasBearing() && sp > 1.5) heading = loc.bearing.toDouble()
        else if (prev != null && prev.distanceTo(loc) > 8 && sp > 1.5) heading = prev.bearingTo(loc).toDouble().let { (it + 360) % 360 }
        if (now - lastSavedPos > 60_000) {
            lastSavedPos = now
            prefs.lastPosition = LatLon(loc.latitude, loc.longitude)
        }
        if (mode == Mode.NAVIGATING) navigateStep(loc, sp) else freeDriveStep(loc, sp)
        updateOverspeed()
        if (pendingRouteCalc && mode == Mode.PREVIEW) {
            pendingRouteCalc = false
            computePreview()
        }
        listeners.forEach { it.onLocationChanged() }
    }

    // ---------------------------------------------------------------------------------------
    // Ziel & Routen
    // ---------------------------------------------------------------------------------------

    fun setDestination(place: Place) {
        if (mode == Mode.NAVIGATING) stopNavigation(keepDestination = false, silent = true)
        destination = place
        fuelStop = null
        betterRoute = null
        arrived = false
        building = null
        prefs.addToHistory(place)
        mode = Mode.PREVIEW
        computePreview()
        loadBuilding(place)
        changed()
    }

    fun cancelPreview() {
        routeJob?.cancel()
        mode = Mode.IDLE
        routes = emptyList()
        destination = null
        origin = null
        startWhenReady = false
        building = null
        fuelStop = null
        loading = false
        message = null
        changed()
    }

    /** Startpunkt setzen (null = wieder ab dem eigenen Standort) und die Routen neu berechnen. */
    fun setOrigin(place: Place?) {
        origin = place
        startWhenReady = false
        if (place != null) prefs.addToHistory(place)
        if (mode == Mode.PREVIEW) computePreview()
        changed()
    }

    /** Start und Ziel tauschen (Rueckweg planen). Ohne eigenen Start wird der Standort zum Ziel. */
    fun swapOriginDestination() {
        val d = destination ?: return
        val from = origin ?: (position ?: prefs.lastPosition)?.let { Place("Mein Standort", it.fmt(), it.lat, it.lon) } ?: return
        origin = d
        destination = from
        fuelStop = null
        building = null
        startWhenReady = false
        if (mode != Mode.PREVIEW) mode = Mode.PREVIEW
        computePreview()
        loadBuilding(from)
        changed()
    }

    fun clearDestination() {
        if (mode == Mode.NAVIGATING) stopNavigation() else cancelPreview()
    }

    fun selectRoute(i: Int) {
        if (i in routes.indices) {
            selectedIndex = i
            changed()
        }
    }

    /** Routen berechnen. avoidJams = Variante, die alle Staus/Sperrungen der gewaehlten Route umfaehrt. */
    fun computePreview(avoidJams: Boolean = false) {
        val dest = destination ?: return
        val start = origin?.pos ?: position ?: prefs.lastPosition
        if (start == null) {
            pendingRouteCalc = true
            message = "Warte auf GPS-Standort …"
            changed()
            return
        }
        val jamBase = if (avoidJams) selectedRoute else null
        routeJob?.cancel()
        loading = true
        message = null
        changed()
        routeJob = scope.launch {
            try {
                val pts = listOfNotNull(start, fuelStop?.pos, dest.pos)
                // Fahrtrichtung nur, wenn die Route am eigenen Standort beginnt
                val hd = heading.takeIf { speedKmh > 10 && origin == null }
                if (jamBase != null) {
                    val avoid = jamAvoidPoints(jamBase)
                    if (avoid.isEmpty()) {
                        message = "Auf dieser Route ist kein Stau gemeldet."
                    } else {
                        val alt = annotate(services.routes(pts, hd, 0, extraAvoid = avoid))
                        alt.firstOrNull()?.let { r ->
                            r.label = "Stau umfahren"
                            routes = listOf(r) + routes.filterNot { it === r }
                            selectedIndex = 0
                        }
                    }
                } else {
                    var list = annotate(services.routes(pts, hd, 2))
                    message = services.lastRoutingNote
                    // Zweiter Durchgang (nur Valhalla): Stau als Kostenfaktor -> stauarme Variante.
                    val best = list.firstOrNull()
                    if (best != null && best.source == RouteSource.VALHALLA && fuelStop == null &&
                        best.hazards.any { it.hazard.type.isTraffic && it.hazard.delaySec >= 180 }
                    ) {
                        try {
                            val cf = jamCostFactors(best)
                            val alt = annotate(services.routes(pts, hd, 0, costFactors = cf, forceValhalla = true)).firstOrNull()
                            if (alt != null && list.none { sameRoute(it, alt) } && alt.durationSec + 60 < best.durationSec) {
                                alt.label = "Stau-optimiert"
                                list = listOf(alt) + list
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Stau-Variante: ${e.message}")
                        }
                    }
                    labelRoutes(list)
                    routes = list
                    selectedIndex = 0
                    if (debuggable) list.firstOrNull()?.let { b ->
                        val enc = de.sascha.kssmaps.core.Geo.encodePolyline(b.geometry.points, 6).chunked(3000)
                        enc.forEachIndexed { i, c -> Log.i(TAG, "PREVIEW_CHUNK ${i + 1}/${enc.size} $c") }
                    }
                    Log.i(TAG, "ROUTES " + list.joinToString(" | ") { r ->
                        "${r.label}: ${r.source} ${(r.distanceM / 1000).roundToInt()} km ${(r.durationSec / 60).roundToInt()} min " +
                            "Stau=${r.hazards.count { it.hazard.type.isTraffic }} Blitzer=${r.count(HazardType.SPEED_CAMERA)} Baustellen=${r.count(HazardType.ROADWORKS)} via=${r.via}"
                    })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Route", e)
                message = "Route konnte nicht berechnet werden: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                loading = false
                changed()
            }
            if (startWhenReady && origin == null && mode == Mode.PREVIEW) {
                startWhenReady = false
                if (routes.isNotEmpty()) startNavigation(0)
            }
        }
    }

    private fun labelRoutes(list: List<Route>) {
        val byTime = list.sortedBy { it.durationSec }
        list.forEach { r -> if (r.label.isBlank() || r.label.startsWith("Alternative") || r.label == "Schnellste") r.label = "" }
        byTime.firstOrNull()?.let { if (it.label.isBlank()) it.label = "Schnellste" }
        val noJam = list.filter { r -> r.hazards.none { it.hazard.type.isTraffic || it.hazard.type == HazardType.CLOSURE } }
            .minByOrNull { it.durationSec }
        if (noJam != null && noJam.label.isBlank()) noJam.label = "Ohne Stau"
        var n = 1
        list.forEach { if (it.label.isBlank()) it.label = "Alternative ${n++}" }
    }

    /** Meldungen (Autobahn-API, Blitzer) zuordnen, Verzoegerungen einrechnen, nach Fahrzeit sortieren. */
    private suspend fun annotate(list: List<Route>): List<Route> {
        if (list.isEmpty()) return list
        val roads = list.flatMap { it.roadRefs }.toSet()
        // Nicht ewig auf langsame Dienste warten: lieber Route ohne einzelne Meldungen zeigen.
        // Spaete Blitzer-Daten werden nachgetragen, Verkehr holt die Pruefung alle 3 Minuten.
        // Autobahn-Meldungen und Blitzer laufen parallel.
        val (events, cams) = coroutineScope {
            val ev = async { upTo(10_000) { services.autobahnEvents(roads) } ?: emptyList() }
            val cm = async { if (prefs.camerasEnabled) enforcementFor(list) else emptyList() }
            ev.await() to cm.await()
        }
        for (r in list) {
            val matched = HazardMatcher.dedupe(HazardMatcher.matchAll(r, events + cams))
            r.hazards = merge(r, matched)
            r.extraDelays = HazardMatcher.extraDelays(r, r.hazards)
        }
        return list.sortedBy { it.durationSec }
    }

    /** Blitzer/Abstandsmessungen fuer die Routen: Bayern-Bestand, bei Bedarf Abfrage entlang der Route. */
    private suspend fun enforcementFor(list: List<Route>): List<Hazard> {
        val late: (List<Hazard>) -> Unit = { if (it.isNotEmpty()) lateEnforcement() }
        val cached = upTo(5_000, late) { services.cameras() } ?: services.camerasCached()
        // Abfrage entlang der Routen, wenn der Bayern-Bestand fehlt oder die Route Bayern verlaesst
        val needCorridor = cached.isEmpty() || list.any { r -> r.geometry.points.any { !Bavaria.contains(it) } }
        val corridor = if (needCorridor) coroutineScope {
            list.take(3).map { r -> async { upTo(12_000, late) { services.enforcementAlong(r) } ?: emptyList() } }
                .awaitAll().flatten()
        } else emptyList()
        return (cached + corridor).distinctBy { it.id } + reportHazards()
    }

    /** TomTom-Verkehr hat Vorrang; Autobahn-Meldungen nur ergaenzen, wenn dort noch nichts gemeldet ist. */
    private fun merge(r: Route, matched: List<RouteHazard>): List<RouteHazard> {
        if (r.source != RouteSource.TOMTOM) return matched
        val provider = r.providerHazards
        val extra = matched.filter { m ->
            if (m.hazard.type.isEnforcement) return@filter true
            if (m.hazard.type.isTraffic) return@filter false
            provider.none { p -> HazardMatcher.overlaps(p, m.startAlong - 500, m.endAlong + 500) && p.hazard.type == m.hazard.type }
        }
        return (provider + extra).sortedBy { it.startAlong }
    }

    private fun jamAvoidPoints(r: Route): List<LatLon> {
        val out = ArrayList<LatLon>()
        val from = if (mode == Mode.NAVIGATING) state?.along ?: 0.0 else 0.0
        for (rh in r.hazards) {
            val t = rh.hazard.type
            if (!(t.isTraffic || t == HazardType.CLOSURE || t == HazardType.ACCIDENT)) continue
            if (rh.endAlong < from) continue
            val len = rh.endAlong - rh.startAlong
            if (len < 3000) out.add(r.geometry.pointAt((rh.startAlong + rh.endAlong) / 2))
            else {
                var a = rh.startAlong + 500
                while (a < rh.endAlong) {
                    out.add(r.geometry.pointAt(a))
                    a += 2000
                }
            }
        }
        return out.take(10)
    }

    private fun jamCostFactors(r: Route): List<Pair<List<LatLon>, Double>> = r.hazards
        .filter { it.hazard.type.isTraffic && it.endAlong - it.startAlong > 100 }
        .map { rh ->
            val len = rh.endAlong - rh.startAlong
            val free = len / (100 / 3.6)
            val factor = ((free + rh.hazard.delaySec) / free).coerceIn(1.5, 25.0)
            r.geometry.slice(rh.startAlong, rh.endAlong) to factor
        }
        .filter { it.first.size >= 2 }

    /** Gleicher Weg? Stichproben der neuen Route liegen auf der alten. */
    private fun sameRoute(a: Route, b: Route, fromAlongB: Double = 0.0): Boolean {
        val len = minOf(a.geometry.length, 25_000.0)
        if (len < 100) return true
        var hits = 0
        var n = 0
        var d = 50.0
        while (d < len) {
            n++
            val p = a.geometry.pointAt(d)
            val pr = b.geometry.projectNear(p, 40.0)
            if (pr != null && pr.along >= fromAlongB - 100) hits++
            d += 500.0
        }
        return n == 0 || hits >= n * 0.9
    }

    private fun loadBuilding(place: Place) {
        buildingJob?.cancel()
        buildingJob = scope.launch {
            // Overpass ist oft ausgelastet: bis zu 3 Versuche (die Karte hat zusaetzlich eine Rueckfallebene).
            for (attempt in 0 until 3) {
                try {
                    val b = services.building(place)
                    if (destination != place) return@launch
                    if (b != null) {
                        building = b
                        changed()
                    }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Gebäude (Versuch ${attempt + 1}): ${e.message}")
                }
                delay(20_000L * (attempt + 1))
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------------------------

    private val debuggable = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Test-Builds: aktive Route ins Log (in Stuecken, Logzeilen sind begrenzt) – fuer die simulierte Testfahrt. */
    private fun logRoute(r: Route) {
        if (!debuggable) return
        val enc = de.sascha.kssmaps.core.Geo.encodePolyline(r.geometry.points, 6)
        val chunks = enc.chunked(3000)
        chunks.forEachIndexed { i, c -> Log.i(TAG, "ROUTE_CHUNK ${i + 1}/${chunks.size} $c") }
        Log.i(TAG, "ROUTE_INFO src=${r.source} len=${r.distanceM.roundToInt()} sec=${r.durationSec.roundToInt()} hazards=${r.hazards.size} " +
            r.hazards.joinToString(";") { "${it.hazard.type}@${it.startAlong.roundToInt()}" })
    }

    /** Wird aufgerufen, sobald eine Route aktiv wird (z. B. Karte entlang der Route offline laden). */
    var onRouteActivated: ((Route) -> Unit)? = null

    fun startNavigation(index: Int = selectedIndex) {
        if (origin != null) {
            // Geplant wurde ab einem anderen Ort – gefahren wird ab dem eigenen Standort.
            origin = null
            startWhenReady = true
            voice.say("Route ab Ihrem Standort wird berechnet.")
            computePreview()
            changed()
            return
        }
        val r = routes.getOrNull(index) ?: return
        logRoute(r)
        onRouteActivated?.invoke(r)
        routeJob?.cancel()
        activeRoute = r
        routes = listOf(r)
        selectedIndex = 0
        tracker = RouteTracker(r)
        state = null
        mode = Mode.NAVIGATING
        arrived = false
        spokenStages.clear()
        betterRoute = null
        speedLimitCoveredTo = 0.0
        fetchSpeedLimits(r, 0.0)
        NavService.start(ctx)
        startLocation()
        voice.say(startText(r))
        startTrafficLoop()
        changed()
    }

    private fun startText(r: Route): String {
        val eta = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(System.currentTimeMillis() + (r.durationSec * 1000).toLong()))
        val km = (r.distanceM / 1000).let { if (it < 10) String.format(Locale.GERMANY, "%.1f", it) else it.roundToInt().toString() }
        val jams = r.hazards.count { it.hazard.type.isTraffic }
        val jamText = when {
            jams == 1 -> " Auf der Strecke ist ein Stau gemeldet."
            jams > 1 -> " Auf der Strecke sind $jams Staus gemeldet."
            else -> ""
        }
        return "Route gestartet. $km Kilometer, Ankunft gegen $eta Uhr.$jamText"
    }

    fun stopNavigation(keepDestination: Boolean = false, silent: Boolean = false) {
        trafficJob?.cancel()
        speedLimitJob?.cancel()
        routeJob?.cancel()
        mode = Mode.IDLE
        activeRoute = null
        routes = emptyList()
        tracker = null
        state = null
        fuelStop = null
        betterRoute = null
        nearestHazard = null
        rerouting = false
        if (!keepDestination) {
            destination = null
            building = null
        }
        NavService.stop(ctx)
        if (locationUsers == 0) stopLocation()
        if (!silent) changed()
    }

    private fun navigateStep(loc: Location, sp: Double) {
        val route = activeRoute ?: return
        val tr = tracker ?: return
        val p = LatLon(loc.latitude, loc.longitude)
        val st = tr.update(p, loc.accuracy.toDouble(), sp, heading)
        state = st
        speedLimit = route.speedLimitAt(st.along)
        if (st.arrived) {
            onArrived(route)
            return
        }
        val fs = fuelStop
        if (fs != null && (st.legIndex > 0 || Geo.distance(p, fs.pos) < 35.0)) {
            fuelStop = null
            if (!fuelArrivalSpoken) voice.say("Sie haben die Tankstelle erreicht. Danach geht es automatisch weiter zum Ziel.")
            fuelArrivalSpoken = false
            changed()
        }
        if (st.offRoute && !rerouting && SystemClock.elapsedRealtime() - lastReroute > 8_000) {
            reroute()
            return
        }
        announce(route, st, sp)
        checkHazards(route, st, sp)
        if (st.along + 25_000 > speedLimitCoveredTo || speedLimitRoute !== route) fetchSpeedLimits(route, st.along)
    }

    private fun isMotorwayHere(route: Route, along: Double, sp: Double): Boolean {
        val lim = route.speedLimitAt(along)
        return lim == SpeedLimitRange.UNLIMITED || (lim ?: 0) >= 100 || sp * 3.6 > 90
    }

    private fun announce(route: Route, st: RouteTracker.State, sp: Double) {
        val idx = st.nextIdx
        if (idx < 0) return
        val m = route.maneuvers[idx]
        val stage = AnnouncePlan.stageFor(st.distToNext, sp, isMotorwayHere(route, st.along, sp)) ?: return
        if (spokenStages.size > 500) spokenStages.clear()
        val done = spokenStages.getOrPut(maneuverKey(m)) { HashSet() }
        if (stage in done) return
        if (stage == AnnouncePlan.Stage.FAR && done.isNotEmpty()) return
        if (stage == AnnouncePlan.Stage.NEAR && AnnouncePlan.Stage.NOW in done) return
        done.add(stage)
        val isFuel = m.kind == de.sascha.kssmaps.core.ManeuverKind.WAYPOINT && fuelStop != null
        var text = if (stage == AnnouncePlan.Stage.NOW) Instructions.voice(m, null, isFuel) else Instructions.voice(m, st.distToNext, isFuel)
        if (isFuel && stage == AnnouncePlan.Stage.NOW) {
            text += " Danach geht es automatisch weiter zum Ziel."
            fuelArrivalSpoken = true
        }
        if (stage != AnnouncePlan.Stage.FAR && st.afterIdx >= 0 && !m.kind.isArrival && m.kind != de.sascha.kssmaps.core.ManeuverKind.WAYPOINT) {
            val after = route.maneuvers[st.afterIdx]
            if (after.along - m.along < 250) text += " " + Instructions.thenText(after)
        }
        voice.say(text, urgent = stage == AnnouncePlan.Stage.NOW)
    }

    private fun checkHazards(route: Route, st: RouteTracker.State, sp: Double) {
        var nearest: Pair<RouteHazard, Double>? = null
        for (rh in route.hazards) {
            val h = rh.hazard
            if (h.type.isEnforcement && !prefs.camerasEnabled) continue
            val distStart = rh.startAlong - st.along
            val inside = st.along >= rh.startAlong && st.along <= rh.endAlong + 20
            if (distStart > 6_000) break
            if (distStart < -20 && !inside) continue
            val warnDist = AnnouncePlan.hazardWarnDistance(h.type, sp)
            val d = max(0.0, distStart)
            if (d <= warnDist || inside) {
                if (nearest == null || d < nearest.second) nearest = rh to d
                if (!inside && warned.add(h.id)) {
                    voice.say(Instructions.hazardVoice(h, d))
                    alert(Alert(h.id, h.type.label + " in " + Instructions.formatDistance(d), h.description.lineSequence().firstOrNull() ?: "", h.type))
                }
            }
        }
        nearestHazard = nearest
        if (warned.size > 2000) warned.clear()
    }

    private fun onArrived(route: Route) {
        if (arrived) return
        arrived = true
        nearestHazard = null
        val last = route.maneuvers.lastOrNull()
        if (last != null && spokenStages[maneuverKey(last)]?.contains(AnnouncePlan.Stage.NOW) != true) voice.say(Instructions.voice(last, null))
        trafficJob?.cancel()
        changed()
        scope.launch {
            delay(20_000)
            if (mode == Mode.NAVIGATING && arrived) stopNavigation(keepDestination = true)
        }
    }

    private fun reroute() {
        val dest = destination ?: return
        val start = position ?: return
        rerouting = true
        lastReroute = SystemClock.elapsedRealtime()
        if (lastReroute - lastRerouteVoice > 45_000) {
            lastRerouteVoice = lastReroute
            voice.say("Route wird neu berechnet.")
        }
        changed()
        routeJob?.cancel()
        routeJob = scope.launch {
            try {
                val pts = listOfNotNull(start, fuelStop?.pos, dest.pos)
                val r = annotate(services.routes(pts, heading, 0)).firstOrNull()
                if (r != null && mode == Mode.NAVIGATING) replaceActive(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Neuberechnung fehlgeschlagen: ${e.message}"
            } finally {
                rerouting = false
                lastReroute = SystemClock.elapsedRealtime()
                changed()
            }
        }
    }

    private fun replaceActive(r: Route) {
        logRoute(r)
        onRouteActivated?.invoke(r)
        activeRoute = r
        routes = listOf(r)
        selectedIndex = 0
        tracker = RouteTracker(r)
        speedLimitCoveredTo = 0.0
        fetchSpeedLimits(r, 0.0)
        changed()
    }

    private fun fetchSpeedLimits(r: Route, from: Double) {
        if (speedLimitJob?.isActive == true && speedLimitRoute === r) return
        speedLimitJob?.cancel()
        speedLimitRoute = r
        val start = max(0.0, from - 200.0)
        val len = 80_000.0
        speedLimitCoveredTo = start + len
        speedLimitJob = scope.launch {
            try {
                val lim = services.speedLimits(r, start, len)
                r.speedLimits = (r.speedLimits.filter { it.to < start } + lim).sortedBy { it.from }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Tempolimits: ${e.message}")
                speedLimitCoveredTo = start + 3_000 // spaeter erneut versuchen
            }
        }
    }

    // ---- Verkehr waehrend der Fahrt: alle 3 Minuten pruefen ----

    private fun startTrafficLoop() {
        trafficJob?.cancel()
        trafficJob = scope.launch {
            while (isActive && mode == Mode.NAVIGATING) {
                delay(180_000)
                try {
                    refreshTraffic()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Verkehr: ${e.message}")
                }
            }
        }
    }

    /** Sofort nach Stau suchen (Knopf "Verkehr pruefen"). */
    fun refreshTrafficNow() {
        if (mode != Mode.NAVIGATING) {
            if (mode == Mode.PREVIEW) computePreview()
            return
        }
        scope.launch {
            try {
                refreshTraffic(manual = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Verkehrsabfrage fehlgeschlagen"
                changed()
            }
        }
    }

    private suspend fun refreshTraffic(manual: Boolean = false) {
        val r = activeRoute ?: return
        val st = state ?: return
        val dest = destination ?: return
        val pos = position ?: return
        // 1. Meldungen auf der aktuellen Route auffrischen (Valhalla: Autobahn-API, Blitzer)
        if (r.source == RouteSource.VALHALLA) {
            annotate(listOf(r))
            changed()
        }
        val pts = listOfNotNull(pos, fuelStop?.pos, dest.pos)
        // TomTom liefert frischen Verkehr nur ueber eine neue Berechnung – also auch ohne
        // "schnellere Route anbieten" neu rechnen, damit Stau-Anzeige und Ankunftszeit aktuell bleiben.
        if (!prefs.trafficReroute && !manual && r.source != RouteSource.TOMTOM) return
        val cands = annotate(services.routes(pts, heading, if (fuelStop == null) 1 else 0))
        if (cands.isEmpty() || mode != Mode.NAVIGATING || activeRoute !== r) return
        // Ist die bisherige Route unter den Ergebnissen? Dann deren frische Daten uebernehmen.
        val same = cands.firstOrNull { sameRoute(it, r, st.along) }
        if (same != null && same.source == RouteSource.TOMTOM) replaceActive(same)
        val remaining = same?.durationSec ?: r.remainingSec(st.along)
        if (!prefs.trafficReroute && !manual) return
        val best = cands.minByOrNull { it.durationSec } ?: return
        if (best === same || sameRoute(best, r, st.along)) {
            if (manual) voice.say("Die aktuelle Route ist weiterhin die schnellste.")
            return
        }
        val saving = remaining - best.durationSec
        if (saving >= max(180.0, remaining * 0.07)) {
            betterRoute = best
            betterRouteSavingSec = saving
            val min = (saving / 60).roundToInt()
            voice.say("Es gibt eine schnellere Route. Sie spart etwa $min Minuten.")
            alert(Alert("better-route", "Schnellere Route: −$min Min.", best.via, null, "Übernehmen") { acceptBetterRoute() })
            changed()
        } else if (manual) {
            voice.say("Keine schnellere Route gefunden.")
        }
    }

    fun acceptBetterRoute() {
        val b = betterRoute ?: return
        betterRoute = null
        replaceActive(b)
        voice.say("Neue Route übernommen.")
    }

    fun dismissBetterRoute() {
        betterRoute = null
        changed()
    }

    /** Waehrend der Fahrt: Stau auf der Route gezielt umfahren. */
    fun avoidJamsNow() {
        if (mode == Mode.PREVIEW) {
            computePreview(avoidJams = true)
            return
        }
        val r = activeRoute ?: return
        val dest = destination ?: return
        val pos = position ?: return
        val avoid = jamAvoidPoints(r)
        if (avoid.isEmpty()) {
            voice.say("Auf der Route ist kein Stau gemeldet.")
            message = "Kein Stau auf der Route gemeldet"
            changed()
            return
        }
        rerouting = true
        changed()
        routeJob?.cancel()
        routeJob = scope.launch {
            try {
                val alt = annotate(services.routes(listOfNotNull(pos, fuelStop?.pos, dest.pos), heading, 0, extraAvoid = avoid)).firstOrNull()
                if (alt != null && mode == Mode.NAVIGATING) {
                    val extra = alt.durationSec - r.remainingSec(state?.along ?: 0.0)
                    replaceActive(alt)
                    val min = (extra / 60).roundToInt()
                    voice.say(if (min > 0) "Stau wird umfahren. Die Umfahrung dauert etwa $min Minuten länger." else "Stau wird umfahren.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Umfahrung nicht möglich: ${e.message}"
            } finally {
                rerouting = false
                changed()
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Freie Fahrt (ohne Ziel): Tempolimit + Blitzer voraus
    // ---------------------------------------------------------------------------------------

    private fun freeDriveStep(loc: Location, sp: Double) {
        val p = LatLon(loc.latitude, loc.longitude)
        val now = SystemClock.elapsedRealtime()
        if (loc.accuracy < 40 && (freeTrace.isEmpty() || Geo.distance(freeTrace.last(), p) > 15)) {
            freeTrace.add(p)
            while (freeTrace.size > 10) freeTrace.removeAt(0)
        }
        // Schonend fuer den oeffentlichen Server: hoechstens alle 20 s und erst nach 200 m Fahrt.
        val movedEnough = lastFreeTracePos?.let { Geo.distance(it, p) > 200.0 } ?: true
        if (sp > 2.0 && now - lastFreeTrace > 20_000 && movedEnough && freeTrace.size >= 3) {
            lastFreeTrace = now
            lastFreeTracePos = p
            val pts = freeTrace.toList()
            scope.launch {
                val r = services.matchTrace(pts) ?: return@launch
                speedLimit = r.limits.lastOrNull()?.kmh?.takeIf { it != 0 }
                currentRoad = r.lastNames.firstOrNull()
                listeners.forEach { it.onLocationChanged() }
            }
        }
        val hd = heading
        if (prefs.camerasEnabled && hd != null && sp > 3.0) {
            val warnDist = AnnouncePlan.hazardWarnDistance(HazardType.SPEED_CAMERA, sp)
            val ahead = HazardMatcher.camerasAhead(p, hd, services.camerasCached() + reportHazards(), warnDist).firstOrNull()
            cameraAhead = ahead
            if (ahead != null && warned.add(ahead.first.id)) {
                voice.say(Instructions.hazardVoice(ahead.first, ahead.second))
                alert(Alert(ahead.first.id, "Blitzer in " + Instructions.formatDistance(ahead.second), ahead.first.description, HazardType.SPEED_CAMERA))
            }
        } else if (sp <= 3.0) {
            cameraAhead = null
        }
    }

    private fun updateOverspeed() {
        val lim = speedLimit
        val over = lim != null && lim > 0 && speedKmh > lim + prefs.overspeedTolerance
        val now = SystemClock.elapsedRealtime()
        if (over) {
            if (overspeedSince == 0L) overspeedSince = now
            if (prefs.overspeedWarning && now - overspeedSince > 3_000 && now - lastOverspeedBeep > 30_000) {
                lastOverspeedBeep = now
                voice.beep()
            }
        } else overspeedSince = 0L
        overspeed = over
    }

    // ---------------------------------------------------------------------------------------
    // Tanken
    // ---------------------------------------------------------------------------------------

    /** Tankstellen: zuerst auf dem Weg zum Ziel, dann nach Preis. */
    suspend fun searchFuel(): List<RankedStation> {
        val here = position ?: prefs.lastPosition ?: throw IllegalStateException("Noch kein Standort bekannt")
        val route = when (mode) {
            Mode.NAVIGATING -> activeRoute
            Mode.PREVIEW -> selectedRoute
            else -> null
        }
        val along = if (mode == Mode.NAVIGATING) state?.along ?: 0.0 else 0.0
        val stations = services.fuelStations(route, along, here)
        return FuelRanking.rank(stations, route?.geometry, along, here, prefs.fuelType, prefs.utaOnly, prefs.fuelCorridorKm * 1000.0)
    }

    /** Tankstelle als Zwischenziel – danach geht es ohne neue Eingabe weiter zum Ziel. */
    fun addFuelStop(s: FuelStation) {
        if (destination == null) {
            setDestination(s.toPlace())
            return
        }
        fuelStop = s
        fuelArrivalSpoken = false
        if (mode == Mode.NAVIGATING) {
            voice.say("Tankstelle ${s.displayName()} wird angefahren, danach geht es weiter zum Ziel.")
            lastReroute = 0L
            reroute()
        } else computePreview()
        changed()
    }

    fun removeFuelStop() {
        if (fuelStop == null) return
        fuelStop = null
        if (mode == Mode.NAVIGATING) reroute() else computePreview()
        changed()
    }

    // ---------------------------------------------------------------------------------------
    // Abschnitte meiden
    // ---------------------------------------------------------------------------------------

    val avoidSections: List<AvoidSection> get() = prefs.avoidSections

    suspend fun addAvoidSectionAt(p: LatLon): AvoidSection? {
        val a = services.avoidSectionAt(p) ?: return null
        if (a.points.isEmpty()) return null
        prefs.avoidSections = prefs.avoidSections + a
        recomputeAfterAvoidChange()
        return a
    }

    fun setAvoidEnabled(id: String, enabled: Boolean) {
        prefs.avoidSections = prefs.avoidSections.map { if (it.id == id) it.copy(enabled = enabled) else it }
        recomputeAfterAvoidChange()
    }

    fun deleteAvoid(id: String) {
        prefs.avoidSections = prefs.avoidSections.filterNot { it.id == id }
        recomputeAfterAvoidChange()
    }

    fun renameAvoid(id: String, name: String) {
        prefs.avoidSections = prefs.avoidSections.map { if (it.id == id) it.copy(name = name) else it }
        changed()
    }

    private fun recomputeAfterAvoidChange() {
        when (mode) {
            Mode.PREVIEW -> computePreview()
            Mode.NAVIGATING -> {
                lastReroute = 0L
                reroute()
            }
            else -> {}
        }
        changed()
    }

    // ---------------------------------------------------------------------------------------
    // Eigene Meldungen: mobiler Blitzer / Abstandsmessung an der aktuellen Position
    // ---------------------------------------------------------------------------------------

    private var reportCache: List<de.sascha.kssmaps.core.UserReport>? = null

    private fun activeReports(): List<de.sascha.kssmaps.core.UserReport> {
        val now = System.currentTimeMillis()
        val all = reportCache ?: prefs.userReports.also { reportCache = it }
        val active = all.filter { it.isActive(now) }
        if (active.size != all.size) {
            prefs.userReports = active
            reportCache = active
        }
        return active
    }

    fun reportHazards(): List<Hazard> = activeReports().map { it.toHazard() }

    val reportCount: Int get() = activeReports().size

    /** Meldet an der aktuellen Position (Fahrtrichtung wird gespeichert). permanent = fester Messplatz. */
    fun reportHere(type: HazardType, permanent: Boolean): Boolean {
        val p = position ?: return false
        val now = System.currentTimeMillis()
        val r = de.sascha.kssmaps.core.UserReport(
            id = java.util.UUID.randomUUID().toString(), type = type, lat = p.lat, lon = p.lon,
            heading = heading.takeIf { speedKmh > 8 }, created = now,
            expires = if (permanent) 0L else now + de.sascha.kssmaps.core.UserReport.TEMP_DURATION_MS,
        )
        val list = activeReports() + r
        prefs.userReports = list
        reportCache = list
        // eigene Meldung nicht sofort selbst ansagen
        warned.add(r.toHazard().id)
        voice.say(if (type == HazardType.DISTANCE_CHECK) "Abstandsmessung gespeichert." else "Blitzer gespeichert.")
        scope.launch { reannotateShown() }
        changed()
        return true
    }

    fun clearReports() {
        prefs.userReports = emptyList()
        reportCache = emptyList()
        scope.launch { reannotateShown() }
        changed()
    }

    // ---------------------------------------------------------------------------------------
    // Sonstiges
    // ---------------------------------------------------------------------------------------

    fun setMuted(m: Boolean) {
        prefs.voiceMuted = m
        voice.muted = m
        if (!m) voice.say("Ton an.")
        changed()
    }

    fun toggleMuted() = setMuted(!prefs.voiceMuted)

    fun setCamerasEnabled(on: Boolean) {
        prefs.camerasEnabled = on
        if (on) scope.launch {
            services.cameras()
            when (mode) {
                Mode.PREVIEW -> computePreview()
                Mode.NAVIGATING -> activeRoute?.let { annotate(listOf(it)); changed() }
                else -> {}
            }
        } else {
            routes.forEach { r -> r.hazards = r.hazards.filter { !it.hazard.type.isEnforcement } }
            activeRoute?.let { r -> r.hazards = r.hazards.filter { !it.hazard.type.isEnforcement } }
            nearestHazard = null
            cameraAhead = null
        }
        changed()
    }

    fun isNight(): Boolean = when (prefs.nightMode) {
        1 -> false
        2 -> true
        else -> {
            val p = position ?: prefs.lastPosition ?: Bavaria.CENTER
            Sun.isNight(p.lat, p.lon)
        }
    }

    /** Ankunftszeit "14:35". */
    fun etaText(remainingSec: Double): String =
        SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(System.currentTimeMillis() + (remainingSec * 1000).toLong()))

    /** Nur fuer Tests (z. B. Android-Auto-Vorlagen ohne Netz und GPS pruefen). */
    internal fun testSetState(
        mode: Mode,
        destination: Place?,
        routes: List<Route>,
        active: Route? = null,
        state: RouteTracker.State? = null,
        arrived: Boolean = false,
    ) {
        this.mode = mode
        this.destination = destination
        this.routes = routes
        this.selectedIndex = 0
        this.activeRoute = active
        this.state = state
        this.arrived = arrived
        this.loading = false
        changed()
    }

    fun clearMessage() {
        message = null
        changed()
    }

    /** Navigation von aussen (Intent, Android-Auto-Sprachbefehl): Koordinate oder Suchtext. */
    fun navigateTo(target: LatLon?, query: String?) {
        scope.launch {
            try {
                if (target != null) {
                    val rev = services.reverse(target)
                    val place = rev?.copy(lat = target.lat, lon = target.lon) ?: Place(query ?: "Ziel", target.fmt(), target.lat, target.lon)
                    setDestination(place)
                } else if (!query.isNullOrBlank()) {
                    val res = services.search(query, position ?: prefs.lastPosition)
                    res.firstOrNull()?.let { setDestination(it) } ?: run {
                        message = "Nichts gefunden für \"$query\""
                        changed()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Suche fehlgeschlagen: ${e.message}"
                changed()
            }
        }
    }
}
