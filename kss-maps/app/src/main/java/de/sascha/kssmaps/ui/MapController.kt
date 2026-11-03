package de.sascha.kssmaps.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.util.Log
import android.view.Choreographer
import de.sascha.kssmaps.NaviApp
import de.sascha.kssmaps.core.Bavaria
import de.sascha.kssmaps.core.Geo
import de.sascha.kssmaps.core.HazardType
import de.sascha.kssmaps.core.LatLon
import de.sascha.kssmaps.core.PuckSmoother
import de.sascha.kssmaps.core.RankedStation
import de.sascha.kssmaps.core.Route
import de.sascha.kssmaps.core.RouteHazard
import de.sascha.kssmaps.core.TomTom
import de.sascha.kssmaps.core.Tankerkoenig
import de.sascha.kssmaps.core.UtaStatus
import de.sascha.kssmaps.nav.NavEngine
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Alles, was auf der Karte passiert – fuer die Handy-Karte und die Android-Auto-Karte gleich.
 * Haelt keine eigene Logik, sondern zeichnet den Zustand der NavEngine.
 */
class MapController(private val ctx: Context, val map: MapLibreMap, private val forCar: Boolean) {

    companion object {
        const val STYLE_DAY = "https://tiles.openfreemap.org/styles/liberty"
        const val STYLE_NIGHT = "https://tiles.openfreemap.org/styles/dark"

        /** Amtliche Luftbilder Bayern (DOP 40 cm, Open Data der Bayerischen Vermessungsverwaltung, CC BY 4.0). */
        const val BY_DOP_WMS = "https://geoservices.bayern.de/od/wms/dop/v1/dop40?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap" +
            "&LAYERS=by_dop40c&STYLES=&SRS=EPSG:3857&BBOX={bbox-epsg-3857}&WIDTH=512&HEIGHT=512&FORMAT=image/jpeg"
        const val ESRI_SAT = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

        private const val SRC_SAT_BY = "nav-sat-by"
        private const val SRC_SAT_ESRI = "nav-sat-esri"
        private const val SRC_FLOW = "nav-flow"
        private const val SRC_ROUTES = "nav-routes"
        private const val SRC_TRAFFIC = "nav-traffic"
        private const val SRC_AVOID = "nav-avoid"
        private const val SRC_BUILDING = "nav-building"
        private const val SRC_POINTS = "nav-points"
        private const val SRC_POS = "nav-pos"
    }

    private val engine: NavEngine = NaviApp.instance.engine
    private val prefs = engine.prefs
    private val density = ctx.resources.displayMetrics.density

    var style: Style? = null
        private set
    private var styleUrl: String? = null
    var onStyleLoaded: (() -> Unit)? = null

    /** Karte folgt der Position (aus, sobald man selbst schiebt). Beim Einschalten weicher Uebergang. */
    var following = true
        set(v) {
            if (v && !field) beginTransition()
            field = v
            if (v) scheduleFrame()
        }
    /** Zeitpunkt der letzten Kamera-Bewegung durch Programm (Uebersicht, Punkt zeigen, Zoom). */
    var lastManualMove = 0L
        private set
    /** Zusaetzlicher Rand (px) fuer Bedienelemente: links, oben, rechts, unten. */
    var uiPadding = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        set(v) {
            field = v
            scheduleFrame()
        }
    var viewHeightPx = 0
    /** false, solange die Karte nicht sichtbar ist: dann werden keine Bilder berechnet. */
    var active = true
        set(v) {
            field = v
            if (v) scheduleFrame() else cancelFrame()
        }
    private var disposed = false
    private val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private var fuelResults: List<RankedStation> = emptyList()
    private var hiddenBaseLayers = ArrayList<String>()
    private var satVisible = false
    private var lastRouteUpdateAlong = -1e9
    private var lastRouteObj: Route? = null

    private fun px(dp: Int) = (dp * density).roundToInt()

    // ---------------------------------------------------------------------------------------
    // Stil
    // ---------------------------------------------------------------------------------------

    /** Android Auto gibt Tag/Nacht selbst vor (carContext.isDarkMode). */
    var nightOverride: (() -> Boolean)? = null

    private fun night(): Boolean = nightOverride?.invoke() ?: engine.isNight()

    fun applyStyle(force: Boolean = false) {
        // Im Luftbild immer den hellen Stil: dessen weisse Strassen und Beschriftungen mit Rand
        // liegen gut lesbar auf dem Foto (der Nachtstil zeichnet schwarze Strassen darauf).
        val url = if (night() && prefs.mapMode != 1) STYLE_NIGHT else STYLE_DAY
        if (!force && url == styleUrl && style != null) {
            updateSatellite()
            updateTrafficOverlay()
            return
        }
        loadStyle(url)
    }

    private fun loadStyle(url: String) {
        styleUrl = url
        style = null
        map.setStyle(Style.Builder().fromUri(url)) { st ->
            style = st
            hiddenBaseLayers.clear()
            satVisible = false
            try {
                install(st)
            } catch (e: Exception) {
                Log.e("KSSMaps", "Kartenebenen", e)
            }
            refreshAll()
            onStyleLoaded?.invoke()
        }
    }

    /** Vom Besitzer der MapView aufrufen, wenn der Stil nicht geladen werden konnte (z. B. Nachtstil fehlt). */
    fun onStyleFailed() {
        if (styleUrl != STYLE_DAY) loadStyle(STYLE_DAY)
    }

    private fun firstSymbolLayerId(st: Style): String? = st.layers.firstOrNull { it is SymbolLayer }?.id

    private fun isRoadLine(l: Layer): Boolean = l is LineLayer && !l.id.startsWith("nav-") &&
        (l.sourceLayer == "transportation" || l.id.startsWith("road") || l.id.startsWith("tunnel") ||
            l.id.startsWith("bridge") || l.id.startsWith("highway"))

    /** Erste Beschriftungsebene oberhalb der letzten Strassen-Ebene. */
    private fun labelsAboveRoads(st: Style): String? {
        val layers = st.layers
        val lastRoad = layers.indexOfLast { isRoadLine(it) }
        if (lastRoad < 0) return null
        return layers.drop(lastRoad + 1).firstOrNull { it is SymbolLayer }?.id
    }

    private fun firstRoadLayerId(st: Style): String? = st.layers.firstOrNull { l ->
        l is LineLayer && (l.sourceLayer == "transportation" || l.id.startsWith("road") || l.id.startsWith("tunnel") || l.id.startsWith("highway"))
    }?.id

    private fun labelFont(st: Style): Array<String> {
        for (l in st.layers) {
            if (l is SymbolLayer) {
                val f = l.textFont.value
                if (f != null && f.isNotEmpty()) {
                    val bold = f.firstOrNull { it.contains("Bold", true) }
                    return if (bold != null) arrayOf(bold) else f
                }
            }
        }
        return arrayOf("Noto Sans Bold")
    }

    private fun addBelow(st: Style, layer: Layer, below: String?) {
        if (below != null && st.getLayer(below) != null) st.addLayerBelow(layer, below) else st.addLayer(layer)
    }

    /**
     * MapLibre leitet die Groesse eines Symbols aus der Pixeldichte der Bitmap ab. Neue Bitmaps
     * tragen die Dichte des Handy-Bildschirms – auf der Android-Auto-Flaeche (eigene, meist
     * niedrigere Dichte) waeren alle Symbole dann 2–3x zu klein. Deshalb die Dichte der Karte setzen.
     */
    private fun img(b: android.graphics.Bitmap): android.graphics.Bitmap {
        b.density = ctx.resources.displayMetrics.densityDpi
        return b
    }

    private fun install(st: Style) {
        lastPuckPos = null
        st.addImage("puck", img(Icons.puck(px(if (forCar) 52 else 46))))
        for (t in HazardType.values()) st.addImage("hz-" + t.name, img(Icons.hazard(t, px(if (forCar) 36 else 34))))
        st.addImage("dest-pin", img(Icons.destinationPin(px(if (forCar) 46 else 42))))
        st.addImage("fuel-uta", img(Icons.fuelMarker(px(32), true)))
        st.addImage("fuel-other", img(Icons.fuelMarker(px(32), false)))
        st.addImage("avoid", img(Icons.avoidMarker(px(26))))

        // Routen ueber ALLEN Strassen des Grundstils, aber unter deren Beschriftungen. Im Nachtstil
        // liegen Autobahn-Ebenen hinter der ersten Beschriftungsebene – "unter der ersten
        // Beschriftung" allein liesse die Route dort unter der Autobahn verschwinden.
        val labels = labelsAboveRoads(st) ?: firstSymbolLayerId(st)
        val roads = firstRoadLayerId(st) ?: labels
        val font = labelFont(st)

        // --- Luftbild / Satellit (unter Strassen und Beschriftungen = Hybrid) ---
        st.addSource(RasterSource(SRC_SAT_ESRI, TileSet("2.2.0", ESRI_SAT).also {
            it.attribution = "Satellit: Esri, Maxar, Earthstar Geographics"
            it.maxZoom = 19f
        }, 256))
        st.addSource(RasterSource(SRC_SAT_BY, TileSet("2.2.0", BY_DOP_WMS).also {
            it.attribution = "Luftbild: © Bayerische Vermessungsverwaltung (CC BY 4.0)"
            it.minZoom = 6f
            it.maxZoom = 20f
        }, 512))
        addBelow(st, RasterLayer("nav-sat-esri", SRC_SAT_ESRI).withProperties(PropertyFactory.visibility(Property.NONE)), roads)
        addBelow(st, RasterLayer("nav-sat-by", SRC_SAT_BY).withProperties(PropertyFactory.visibility(Property.NONE)), roads)

        // --- Routen ---
        st.addSource(GeoJsonSource(SRC_ROUTES))
        st.addSource(GeoJsonSource(SRC_TRAFFIC))
        st.addSource(GeoJsonSource(SRC_AVOID))
        st.addSource(GeoJsonSource(SRC_BUILDING))
        st.addSource(GeoJsonSource(SRC_POINTS))
        st.addSource(GeoJsonSource(SRC_POS))

        val w = { base: Float ->
            Expression.interpolate(
                Expression.exponential(1.5f), Expression.zoom(),
                Expression.stop(8, base * 0.45f), Expression.stop(13, base), Expression.stop(18, base * 2.2f)
            )
        }
        val notSel = Expression.eq(Expression.get("sel"), Expression.literal(false))
        val isSel = Expression.eq(Expression.get("sel"), Expression.literal(true))
        addBelow(st, LineLayer("nav-alt-casing", SRC_ROUTES).withFilter(notSel).withProperties(
            PropertyFactory.lineColor("#5B7896"), PropertyFactory.lineWidth(w(10f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ), labels)
        addBelow(st, LineLayer("nav-alt", SRC_ROUTES).withFilter(notSel).withProperties(
            PropertyFactory.lineColor("#A9C2DD"), PropertyFactory.lineWidth(w(6.5f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ), labels)
        addBelow(st, LineLayer("nav-sel-casing", SRC_ROUTES).withFilter(isSel).withProperties(
            PropertyFactory.lineColor("#0B4A8F"), PropertyFactory.lineWidth(w(11f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ), labels)
        addBelow(st, LineLayer("nav-sel", SRC_ROUTES).withFilter(isSel).withProperties(
            PropertyFactory.lineColor("#2F80ED"), PropertyFactory.lineWidth(w(7f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ), labels)
        // Stau-Abschnitte auf der Route (rot = Stau, orange = stockend, lila = gesperrt, gelb = Baustelle)
        addBelow(st, LineLayer("nav-traffic", SRC_TRAFFIC).withProperties(
            PropertyFactory.lineColor(Expression.get("c")), PropertyFactory.lineWidth(w(7f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ), labels)
        // gesperrte Abschnitte
        addBelow(st, LineLayer("nav-avoid", SRC_AVOID).withProperties(
            PropertyFactory.lineColor(Expression.get("c")), PropertyFactory.lineWidth(w(6f)),
            PropertyFactory.lineDasharray(arrayOf(1.2f, 1.0f)), PropertyFactory.lineOpacity(0.9f),
        ), labels)

        // --- Zielgebaeude: kraeftig magenta, auch in 3D ---
        // UEBER den Gebaeude-Ebenen des Grundstils, sonst verdecken dessen (3D-)Gebaeude die
        // Markierung (nachts sah man nur den Umriss). Die 3D-Variante etwas hoeher als das Original.
        val bld = listOf(
            FillExtrusionLayer("nav-bld-3d", SRC_BUILDING).withProperties(
                PropertyFactory.fillExtrusionColor("#E91E63"),
                PropertyFactory.fillExtrusionHeight(Expression.get("h")),
                PropertyFactory.fillExtrusionBase(0f),
                PropertyFactory.fillExtrusionOpacity(0.9f),
            ),
            FillLayer("nav-bld-fill", SRC_BUILDING).withProperties(
                PropertyFactory.fillColor("#E91E63"), PropertyFactory.fillOpacity(0.9f),
            ),
            LineLayer("nav-bld-line", SRC_BUILDING).withProperties(
                PropertyFactory.lineColor("#FFFFFF"), PropertyFactory.lineWidth(3f),
            ),
        )
        val baseBuildings = st.layers.lastOrNull { l ->
            !l.id.startsWith("nav-") && ((l is FillLayer && l.sourceLayer == "building") || (l is FillExtrusionLayer && l.sourceLayer == "building"))
        }?.id
        var above = baseBuildings
        for (l in bld) {
            if (above != null && st.getLayer(above) != null) st.addLayerAbove(l, above) else addBelow(st, l, labels)
            above = l.id
        }

        // --- Punkte: Meldungen, Tankstellen, Sperrpunkte, Ziel ---
        // Farbiger Punkt unter jedem Meldungs-Symbol (Rueckfallebene, wird vom Symbol verdeckt)
        st.addLayer(CircleLayer("nav-points-dot", SRC_POINTS)
            .withFilter(Expression.eq(Expression.get("k"), Expression.literal("icon")))
            .withProperties(
                PropertyFactory.circleRadius(if (forCar) 6f else 7f),
                PropertyFactory.circleColor(Expression.get("c")),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
            ))
        st.addLayer(SymbolLayer("nav-points", SRC_POINTS)
            .withFilter(Expression.eq(Expression.get("k"), Expression.literal("icon")))
            .withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.symbolSortKey(Expression.get("z")),
            ))
        st.addLayer(SymbolLayer("nav-fuel", SRC_POINTS)
            .withFilter(Expression.eq(Expression.get("k"), Expression.literal("fuel")))
            .withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.textField(Expression.get("t")), PropertyFactory.textFont(font),
                PropertyFactory.textSize(13f), PropertyFactory.textColor("#1B5E20"),
                PropertyFactory.textHaloColor("#FFFFFF"), PropertyFactory.textHaloWidth(2f),
                PropertyFactory.textOffset(arrayOf(0f, 1.6f)), PropertyFactory.textAllowOverlap(true),
                PropertyFactory.symbolSortKey(Expression.get("z")),
            ))
        st.addLayer(SymbolLayer("nav-pin", SRC_POINTS)
            .withFilter(Expression.eq(Expression.get("k"), Expression.literal("pin")))
            .withProperties(
                PropertyFactory.iconImage("dest-pin"), PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            ))
        // Hausnummer gross und farbig auf dem Gebaeude
        st.addLayer(SymbolLayer("nav-housenumber", SRC_POINTS)
            .withFilter(Expression.eq(Expression.get("k"), Expression.literal("hn")))
            .withProperties(
                PropertyFactory.textField(Expression.get("t")), PropertyFactory.textFont(font),
                PropertyFactory.textSize(if (forCar) 28f else 26f), PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textHaloColor("#5C0026"), PropertyFactory.textHaloWidth(3f),
                PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
            ))
        // Eigene Position ganz oben: blauer Punkt (ohne Bild, wird immer gezeichnet) und darueber
        // der Fahrtpfeil, der den Punkt verdeckt.
        st.addLayer(CircleLayer("nav-pos-dot", SRC_POS).withProperties(
            PropertyFactory.circleRadius(if (forCar) 8f else 9f),
            PropertyFactory.circleColor("#1A73E8"),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
        ))
        st.addLayer(SymbolLayer("nav-pos", SRC_POS).withProperties(
            PropertyFactory.iconImage("puck"),
            PropertyFactory.iconRotate(Expression.get("b")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
        ))
        updateTrafficOverlay()
    }

    // ---------------------------------------------------------------------------------------
    // Luftbild & Verkehrsfluss
    // ---------------------------------------------------------------------------------------

    private var lastIdleWork = 0L
    private var satKey = ""

    /**
     * Vom Besitzer bei Kamera-Stillstand aufrufen. Die Kamera folgt dem Pfeil Bild fuer Bild, MapLibre
     * meldet dann nach jedem Bild "Stillstand" – Luftbild-Umschaltung und Gebaeudesuche darum hoechstens
     * zweimal pro Sekunde.
     */
    fun onCameraIdle() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastIdleWork < 500) return
        lastIdleWork = now
        updateSatellite()
        tryBuildingFromMap()
    }

    fun updateSatellite() {
        val st = style ?: return
        val on = prefs.mapMode == 1
        val center = map.cameraPosition.target
        val inBavaria = center != null && Bavaria.contains(LatLon(center.latitude, center.longitude))
        val key = "$on/$inBavaria/${System.identityHashCode(st)}"
        if (key != satKey) {
            satKey = key
            st.getLayer("nav-sat-by")?.setProperties(PropertyFactory.visibility(if (on && inBavaria) Property.VISIBLE else Property.NONE))
            st.getLayer("nav-sat-esri")?.setProperties(PropertyFactory.visibility(if (on && !inBavaria) Property.VISIBLE else Property.NONE))
        }
        if (on != satVisible) {
            satVisible = on
            if (on) {
                // Flaechen des Grundstils ausblenden, damit das Luftbild nicht verdeckt wird
                for (l in st.layers) {
                    if (l.id.startsWith("nav-")) continue
                    if (l is FillLayer || l is FillExtrusionLayer || l is BackgroundLayer) {
                        if (l.visibility.value != Property.NONE) {
                            l.setProperties(PropertyFactory.visibility(Property.NONE))
                            hiddenBaseLayers.add(l.id)
                        }
                    }
                }
            } else {
                for (id in hiddenBaseLayers) st.getLayer(id)?.setProperties(PropertyFactory.visibility(Property.VISIBLE))
                hiddenBaseLayers.clear()
            }
        }
    }

    fun updateTrafficOverlay() {
        val st = style ?: return
        val key = prefs.tomtomKey
        val want = key.isNotBlank() && prefs.trafficOverlay
        val existing = st.getLayer("nav-flow")
        if (!want) {
            existing?.setProperties(PropertyFactory.visibility(Property.NONE))
            return
        }
        if (existing == null) {
            st.addSource(RasterSource(SRC_FLOW, TileSet("2.2.0", TomTom.flowTileUrl(key, night())).also {
                it.attribution = "Verkehr: © TomTom"
                it.maxZoom = 22f
            }, 512))
            addBelow(st, RasterLayer("nav-flow", SRC_FLOW).withProperties(PropertyFactory.rasterOpacity(0.85f)), "nav-alt-casing")
        } else existing.setProperties(PropertyFactory.visibility(Property.VISIBLE))
    }

    // ---------------------------------------------------------------------------------------
    // Daten zeichnen
    // ---------------------------------------------------------------------------------------

    fun refreshAll() {
        lastRouteObj = null
        updateRoutes(force = true)
        updatePoints()
        updateBuilding()
        updateAvoid()
        updatePosition()
        updateSatellite()
    }

    private fun pt(p: LatLon): Point = Point.fromLngLat(p.lon, p.lat)

    private fun line(points: List<LatLon>): LineString = LineString.fromLngLats(points.map { pt(it) })

    /** Route(n). Waehrend der Fahrt nur der Rest ab der aktuellen Position. */
    fun updateRoutes(force: Boolean = false) {
        val st = style ?: return
        val src = st.getSourceAs<GeoJsonSource>(SRC_ROUTES) ?: return
        val tsrc = st.getSourceAs<GeoJsonSource>(SRC_TRAFFIC) ?: return
        val feats = ArrayList<Feature>()
        val traffic = ArrayList<Feature>()
        when (engine.mode) {
            NavEngine.Mode.NAVIGATING -> {
                val r = engine.activeRoute
                if (r != null) {
                    // Ab dem angezeigten (geglaetteten) Pfeil, mit kurzem Rest dahinter: sonst endet
                    // die Linie zwischen zwei GPS-Punkten sichtbar vor oder hinter dem Pfeil.
                    noteState()
                    val along = when {
                        lineIsActive(r) -> smoother.along ?: 0.0
                        // Zustand gehoert noch zur alten Route (gerade neu berechnet): neue beginnt hier
                        stateRoute === r -> engine.state?.along ?: 0.0
                        else -> 0.0
                    }
                    if (!force && r === lastRouteObj && abs(along - lastRouteUpdateAlong) < 30.0) return
                    lastRouteObj = r
                    lastRouteUpdateAlong = along
                    val rest = r.geometry.slice(maxOf(0.0, along - 12.0), r.geometry.length)
                    if (rest.size >= 2) feats.add(Feature.fromGeometry(line(rest)).also { it.addBooleanProperty("sel", true) })
                    addTraffic(traffic, r, along)
                }
            }
            NavEngine.Mode.PREVIEW -> {
                lastRouteObj = null
                engine.routes.forEachIndexed { i, r ->
                    val f = Feature.fromGeometry(line(r.geometry.points))
                    f.addBooleanProperty("sel", i == engine.selectedIndex)
                    f.addNumberProperty("idx", i)
                    feats.add(f)
                }
                engine.selectedRoute?.let { addTraffic(traffic, it, 0.0) }
            }
            else -> lastRouteObj = null
        }
        src.setGeoJson(FeatureCollection.fromFeatures(feats))
        tsrc.setGeoJson(FeatureCollection.fromFeatures(traffic))
    }

    private fun trafficColor(t: HazardType): String? = when (t) {
        HazardType.JAM -> "#D50000"
        HazardType.SLOW_TRAFFIC -> "#FF8F00"
        HazardType.CLOSURE -> "#6A1B9A"
        HazardType.ROADWORKS -> "#FBC02D"
        else -> null
    }

    private fun addTraffic(out: MutableList<Feature>, r: Route, from: Double) {
        for (rh in r.hazards) {
            val c = trafficColor(rh.hazard.type) ?: continue
            if (rh.endAlong < from || rh.endAlong - rh.startAlong < 30) continue
            val seg = r.geometry.slice(maxOf(from, rh.startAlong), rh.endAlong)
            if (seg.size < 2) continue
            out.add(Feature.fromGeometry(line(seg)).also { it.addStringProperty("c", c) })
        }
    }

    fun setFuelResults(list: List<RankedStation>) {
        fuelResults = list
        updatePoints()
    }

    /** Meldungen nur auf der eigenen Route (beim Fahren: nur voraus). */
    fun updatePoints() {
        val st = style ?: return
        val src = st.getSourceAs<GeoJsonSource>(SRC_POINTS) ?: return
        val feats = ArrayList<Feature>()
        val route = when (engine.mode) {
            NavEngine.Mode.NAVIGATING -> engine.activeRoute
            NavEngine.Mode.PREVIEW -> engine.selectedRoute
            else -> null
        }
        val along = if (engine.mode == NavEngine.Mode.NAVIGATING) engine.state?.along ?: 0.0 else -1e9
        if (route != null) {
            for (rh in route.hazards) {
                if (rh.endAlong < along - 30) continue
                if (rh.hazard.type.isEnforcement && !prefs.camerasEnabled) continue
                feats.add(iconFeature(markerPoint(route, rh), "hz-" + rh.hazard.type.name, if (rh.hazard.type.isEnforcement) 5 else 4))
            }
        } else {
            engine.cameraAhead?.let { feats.add(iconFeature(it.first.point, "hz-" + it.first.type.name, 5)) }
        }
        for (a in engine.avoidSections) {
            for (p in a.points) feats.add(iconFeature(p, "avoid", 3).also { it.addBooleanProperty("on", a.enabled) })
        }
        engine.fuelStop?.let { fs ->
            feats.add(Feature.fromGeometry(pt(fs.pos)).also {
                it.addStringProperty("k", "fuel"); it.addStringProperty("icon", "fuel-uta")
                it.addStringProperty("t", Tankerkoenig.formatPrice(fs.price(prefs.fuelType))); it.addNumberProperty("z", 8)
            })
        }
        for ((i, rs) in fuelResults.take(25).withIndex()) {
            if (engine.fuelStop?.id == rs.station.id) continue
            feats.add(Feature.fromGeometry(pt(rs.station.pos)).also {
                it.addStringProperty("k", "fuel")
                it.addStringProperty("icon", if (rs.station.uta == UtaStatus.YES) "fuel-uta" else "fuel-other")
                it.addStringProperty("t", "${i + 1}. " + Tankerkoenig.formatPrice(rs.price))
                it.addNumberProperty("z", 7)
            })
        }
        val dest = engine.destination
        if (dest != null) {
            feats.add(Feature.fromGeometry(pt(dest.pos)).also { it.addStringProperty("k", "pin") })
            val b = destinationRing()
            val hn = dest.houseNumber
            if (b != null && !hn.isNullOrBlank()) {
                feats.add(Feature.fromGeometry(pt(b.second)).also {
                    it.addStringProperty("k", "hn")
                    it.addStringProperty("t", hn)
                })
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    private fun markerPoint(r: Route, rh: RouteHazard): LatLon =
        if (rh.hazard.type.isEnforcement && rh.hazard.geometry.size < 2) rh.hazard.point else r.geometry.pointAt(rh.startAlong)

    private fun iconFeature(p: LatLon, icon: String, z: Int): Feature = Feature.fromGeometry(pt(p)).also {
        it.addStringProperty("k", "icon")
        it.addStringProperty("icon", icon)
        it.addNumberProperty("z", z)
        // Farbe fuer den Punkt unter dem Symbol (sichtbar, falls ein Geraet das Symbol nicht zeichnet)
        val type = HazardType.values().firstOrNull { t -> icon == "hz-" + t.name }
        val c = if (type != null) Icons.hazardColor(type) else Color.parseColor("#D32F2F")
        it.addStringProperty("c", String.format("#%06X", 0xFFFFFF and c))
    }

    // Rueckfallebene, falls die Overpass-Abfrage nichts liefert (z. B. Server ueberlastet):
    // Gebaeudeumriss direkt aus den Vektorkacheln der Karte, sobald das Ziel im Bild ist.
    private var mapBuildingRing: List<LatLon>? = null
    private var mapBuildingFor: de.sascha.kssmaps.core.Place? = null

    /** Gebaeude fuer die Markierung: Overpass-Ergebnis, sonst das aus der Karte. */
    private fun destinationRing(): Pair<List<LatLon>, LatLon>? {
        val dest = engine.destination ?: return null
        engine.building?.let { return it.ring to it.label }
        val r = mapBuildingRing
        if (r != null && mapBuildingFor == dest) return r to Geo.ringCentroid(r)
        return null
    }

    /** Auf Kamera-Stillstand aufrufen: sucht das Zielgebaeude in den gerenderten Gebaeude-Ebenen. */
    fun tryBuildingFromMap() {
        val st = style ?: return
        val dest = engine.destination ?: return
        if (engine.building != null || (mapBuildingFor == dest && mapBuildingRing != null)) return
        if (map.cameraPosition.zoom < 15.0) return
        val sp = map.projection.toScreenLocation(LatLng(dest.lat, dest.lon))
        if (sp.x < 0 || sp.y < 0 || sp.x > map.width || sp.y > map.height) return
        val layerIds = st.layers.filter { l ->
            !l.id.startsWith("nav-") && ((l is FillLayer && l.sourceLayer == "building") || (l is FillExtrusionLayer && l.sourceLayer == "building"))
        }.map { it.id }
        if (layerIds.isEmpty()) return
        val r = px(40).toFloat()
        val feats = try {
            map.queryRenderedFeatures(android.graphics.RectF(sp.x - r, sp.y - r, sp.x + r, sp.y + r), *layerIds.toTypedArray())
        } catch (e: Exception) {
            return
        }
        var best: List<LatLon>? = null
        var bestD = Double.MAX_VALUE
        for (f in feats) {
            val polys: List<Polygon> = when (val g = f.geometry()) {
                is Polygon -> listOf(g)
                is org.maplibre.geojson.MultiPolygon -> g.polygons()
                else -> emptyList()
            }
            for (poly in polys) {
                val ring = poly.coordinates().firstOrNull() ?: continue
                if (ring.size < 4) continue
                val ll = ring.map { LatLon(it.latitude(), it.longitude()) }
                val d = Geo.distanceToRing(dest.pos, ll)
                if (d < bestD) {
                    bestD = d
                    best = ll
                }
            }
        }
        if (best != null && bestD <= 25.0) {
            mapBuildingRing = best
            mapBuildingFor = dest
            updateBuilding()
            updatePoints()
        }
    }

    fun updateBuilding() {
        val st = style ?: return
        val src = st.getSourceAs<GeoJsonSource>(SRC_BUILDING) ?: return
        val d = destinationRing()
        if (d == null) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            return
        }
        val ring = d.first.map { pt(it) }.toMutableList()
        if (ring.first() != ring.last()) ring.add(ring.first())
        val f = Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
        f.addNumberProperty("h", ((engine.building?.levels ?: 3) * 3.5 + 6.0).toFloat())
        src.setGeoJson(FeatureCollection.fromFeature(f))
    }

    fun updateAvoid() {
        val st = style ?: return
        val src = st.getSourceAs<GeoJsonSource>(SRC_AVOID) ?: return
        val feats = ArrayList<Feature>()
        for (a in engine.avoidSections) {
            for (s in a.shapes) {
                if (s.size < 2) continue
                feats.add(Feature.fromGeometry(line(s)).also { it.addStringProperty("c", if (a.enabled) "#D32F2F" else "#9E9E9E") })
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    // ---------------------------------------------------------------------------------------
    // Eigene Position: fluessig zwischen den GPS-Punkten (siehe PuckSmoother)
    // ---------------------------------------------------------------------------------------

    private val smoother = PuckSmoother()
    private val choreographer: Choreographer = Choreographer.getInstance()
    private var frameQueued = false
    private val frameCallback = Choreographer.FrameCallback { ns ->
        frameQueued = false
        onFrame(ns / 1e9)
    }
    private var lastFixLoc: Any? = null
    private var lastFixState: Any? = null
    private var lastFixRoute: Any? = null
    private var lastPuckPos: LatLon? = null
    private var lastPuckBearing = Double.NaN
    private var lastFrameT = Double.NaN

    private fun nowSec(): Double = System.nanoTime() / 1e9

    private fun lineIsActive(r: Route): Boolean = engine.mode == NavEngine.Mode.NAVIGATING && lastFixRoute === r && smoother.along != null

    // Zu welcher Route gehoert engine.state? Ein neuer Zustand entsteht nur bei einem GPS-Punkt,
    // synchron mit der dann aktiven Route. Nach einer Neuberechnung ist der alte Zustand bis zum
    // naechsten Punkt noch der der alten Route.
    private var stateSeen: Any? = null
    private var stateRoute: Route? = null

    private fun noteState() {
        val st = engine.state
        if (st !== stateSeen) {
            stateSeen = st
            stateRoute = if (st != null) engine.activeRoute else null
        }
    }

    /** Neuer GPS-Punkt bzw. neuer Zustand: Ziel fuer die Anzeige setzen, gezeichnet wird pro Bild. */
    fun updatePosition() {
        val st = style ?: return
        val loc = engine.location
        if (loc == null) {
            smoother.reset()
            lastPuckPos = null
            st.getSourceAs<GeoJsonSource>(SRC_POS)?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            return
        }
        noteState()
        val nav = if (engine.mode == NavEngine.Mode.NAVIGATING) engine.state else null
        val r = engine.activeRoute
        // Nur echte neue Punkte/Zustaende. Nach einer Neuberechnung gehoert der alte Zustand noch zur
        // alten Route (seine Lage passt nicht zur neuen) – dann bis zum naechsten GPS-Punkt warten.
        if (loc !== lastFixLoc || nav !== lastFixState) {
            lastFixLoc = loc
            lastFixState = nav
            val t = nowSec()
            val sp = engine.speedKmh / 3.6
            if (nav != null && nav.onRoute && r != null && stateRoute === r) {
                lastFixRoute = r
                smoother.fixOnRoute(t, r.geometry, r, nav.along, sp)
            } else {
                lastFixRoute = null
                smoother.fixFree(t, LatLon(loc.latitude, loc.longitude), engine.heading, sp)
            }
        }
        if (lastPuckPos == null) {
            smoother.advance(nowSec())
            drawPuck()
        }
        scheduleFrame()
    }

    private fun drawPuck() {
        val st = style ?: return
        val p = smoother.pos ?: return
        val b = smoother.bearing
        val lp = lastPuckPos
        if (lp != null && Geo.distance(lp, p) < 0.02 && abs(Geo.signedAngle(lastPuckBearing, b)) < 0.05) return
        val src = st.getSourceAs<GeoJsonSource>(SRC_POS) ?: return
        val f = Feature.fromGeometry(pt(p))
        f.addNumberProperty("b", b.toFloat())
        src.setGeoJson(f)
        lastPuckPos = p
        lastPuckBearing = b
    }

    private fun scheduleFrame() {
        if (frameQueued || !active || disposed) return
        frameQueued = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun cancelFrame() {
        if (!frameQueued) return
        choreographer.removeFrameCallback(frameCallback)
        frameQueued = false
        lastFrameT = Double.NaN
    }

    /** Besitzer (Activity, Android-Auto-Flaeche) ruft das vor dem Zerstoeren der Karte auf. */
    fun dispose() {
        disposed = true
        cancelFrame()
    }

    private fun onFrame(t: Double) {
        if (!active || disposed || style == null) {
            lastFrameT = Double.NaN
            return
        }
        val fdt = if (lastFrameT.isNaN()) 1.0 / 60 else (t - lastFrameT).coerceIn(0.0, 0.25)
        lastFrameT = t
        smoother.advance(t)
        drawPuck()
        val camBusy = following && cameraFrame(t, fdt)
        if (debuggable) smoothStats(t, fdt)
        if (!smoother.isSettled(t) || camBusy) scheduleFrame() else lastFrameT = Double.NaN
    }

    // ---- Messung fuer die Testfahrt: wie gleichmaessig laeuft der Pfeil? (nur Test-Builds) ----
    private var statT0 = Double.NaN
    private var statPrev: LatLon? = null
    private var statN = 0
    private var statMin = Double.MAX_VALUE
    private var statMax = 0.0
    private var statSum = 0.0

    private fun smoothStats(t: Double, fdt: Double) {
        val p = smoother.pos ?: return
        val prev = statPrev
        statPrev = p
        val v = engine.speedKmh / 3.6
        if (prev == null || fdt <= 0.0 || v < 3.0 || engine.mode != NavEngine.Mode.NAVIGATING) return
        if (statT0.isNaN()) statT0 = t
        val sp = Geo.distance(prev, p) / fdt
        statN++
        statSum += sp
        statMin = minOf(statMin, sp)
        statMax = maxOf(statMax, sp)
        if (t - statT0 >= 10.0) {
            Log.i("KSSMaps", String.format(java.util.Locale.ROOT, "PUCK_SMOOTH %s bilder=%d fps=%.0f tempo=%.1f pfeil_min=%.1f pfeil_mittel=%.1f pfeil_max=%.1f m/s",
                if (forCar) "auto" else "handy", statN, statN / (t - statT0), v, statMin, statSum / statN, statMax))
            statT0 = t
            statN = 0
            statMin = Double.MAX_VALUE
            statMax = 0.0
            statSum = 0.0
        }
    }

    // ---------------------------------------------------------------------------------------
    // Kamera
    // ---------------------------------------------------------------------------------------

    private var zoomBand = -1

    /** Zoom nach Tempo, mit etwas Spielraum an den Grenzen (sonst pendelt der Zoom bei 55 km/h). */
    private fun zoomForSpeedSteady(kmh: Double): Double {
        val bounds = doubleArrayOf(25.0, 55.0, 85.0, 115.0)
        var band = bounds.count { kmh >= it }
        val cur = zoomBand
        if (cur >= 0 && band != cur) {
            val edge = if (band > cur) bounds[band - 1] else bounds[cur - 1]
            if (abs(kmh - edge) < 4.0) band = cur
        }
        zoomBand = band
        return when (band) {
            0 -> 17.0
            1 -> 16.3
            2 -> 15.5
            3 -> 14.9
            else -> 14.4
        }
    }

    // Kamera folgt dem geglaetteten Pfeil Bild fuer Bild (keine 1-Sekunden-Animationen mehr).
    private var camZoom = Double.NaN
    private var camTilt = Double.NaN
    private var camPad: DoubleArray? = null
    private var idleZoom = 16.0
    private var trFrom: CameraPosition? = null
    private var trStart = 0.0
    private val trDuration = 0.9

    private fun ease(cur: Double, want: Double, dt: Double, tau: Double): Double =
        if (cur.isNaN()) want else cur + (want - cur) * (1.0 - exp(-dt / tau))

    private fun beginTransition() {
        trFrom = map.cameraPosition
        trStart = nowSec()
        camZoom = Double.NaN
        camTilt = Double.NaN
        camPad = null
        idleZoom = maxOf(map.cameraPosition.zoom, 15.0)
        scheduleFrame()
    }

    /** Ein Bild der Verfolgerkamera. true = noch in Bewegung (Uebergang, Zoom, Rand). */
    private fun cameraFrame(t: Double, fdt: Double): Boolean {
        val p = smoother.pos
        if (p == null) {
            if (trFrom != null) trStart = t
            return false
        }
        val navigating = engine.mode == NavEngine.Mode.NAVIGATING
        val wantZoom = if (navigating) zoomForSpeedSteady(engine.speedKmh) else idleZoom
        val wantTilt = if (navigating) 50.0 else 0.0
        val wantBearing = if (navigating) smoother.bearing else 0.0
        val wantPad = uiPadding.copyOf()
        // Beim Navigieren die Position ins untere Drittel schieben: mehr Strecke voraus sichtbar
        if (navigating && viewHeightPx > 0) wantPad[1] = maxOf(wantPad[1], viewHeightPx * 0.42)
        camZoom = ease(camZoom, wantZoom, fdt, 1.0)
        camTilt = ease(camTilt, wantTilt, fdt, 0.5)
        val pad = camPad ?: wantPad.copyOf()
        for (i in 0..3) pad[i] = ease(pad[i], wantPad[i], fdt, 0.3)
        camPad = pad
        var busy = abs(camZoom - wantZoom) > 0.005 || abs(camTilt - wantTilt) > 0.05 || (0..3).any { abs(pad[it] - wantPad[it]) > 0.5 }
        var lat = p.lat
        var lon = p.lon
        var zoom = camZoom
        var bearing = wantBearing
        var tilt = camTilt
        val padding = pad.copyOf()
        val from = trFrom
        if (from != null) {
            val k = ((t - trStart) / trDuration).coerceIn(0.0, 1.0)
            if (k >= 1.0) trFrom = null
            else {
                busy = true
                val e = k * k * (3 - 2 * k)
                from.target?.let { ft ->
                    lat = ft.latitude + (lat - ft.latitude) * e
                    lon = ft.longitude + (lon - ft.longitude) * e
                }
                zoom = from.zoom + (zoom - from.zoom) * e
                bearing = from.bearing + Geo.signedAngle(from.bearing, bearing) * e
                tilt = from.tilt + (tilt - from.tilt) * e
                val fp = from.padding
                if (fp != null && fp.size == 4) for (i in 0..3) padding[i] = fp[i] + (padding[i] - fp[i]) * e
            }
        }
        val cp = CameraPosition.Builder()
            .target(LatLng(lat, lon))
            .zoom(zoom)
            .bearing(bearing)
            .tilt(tilt)
            .padding(padding[0], padding[1], padding[2], padding[3])
            .build()
        map.moveCamera(CameraUpdateFactory.newCameraPosition(cp))
        return busy
    }

    /** Karte mitfuehren (beim Navigieren gedreht und geneigt, sonst genordet) – Bild fuer Bild. */
    @Suppress("UNUSED_PARAMETER")
    fun follow(animate: Boolean = true) {
        if (following) scheduleFrame()
    }

    fun recenter() {
        beginTransition()
        following = true
        scheduleFrame()
    }

    /** Alle Routen der Vorschau ins Bild. */
    fun showRoutesOverview() {
        val pts = engine.routes.flatMap { r -> r.geometry.points.filterIndexed { i, _ -> i % 10 == 0 } + listOfNotNull(r.geometry.points.lastOrNull()) }
        fitPoints(pts)
    }

    fun fitPoints(pts: List<LatLon>) {
        if (pts.size < 2) {
            pts.firstOrNull()?.let { map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat, it.lon), 15.0), 800) }
            return
        }
        val b = LatLngBounds.Builder()
        pts.forEach { b.include(LatLng(it.lat, it.lon)) }
        val bounds = try {
            b.build()
        } catch (e: Exception) {
            return
        }
        following = false
        lastManualMove = System.currentTimeMillis()
        val m = px(40)
        val update = CameraUpdateFactory.newLatLngBounds(
            bounds, (uiPadding[0] + m).toInt(), (uiPadding[1] + m).toInt(), (uiPadding[2] + m).toInt(), (uiPadding[3] + m).toInt()
        )
        try {
            map.animateCamera(update, 900)
        } catch (e: Exception) {
            map.moveCamera(update)
        }
    }

    fun showPoint(p: LatLon, zoom: Double = 17.0) {
        following = false
        lastManualMove = System.currentTimeMillis()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(p.lat, p.lon)).zoom(zoom).bearing(0.0).tilt(0.0).build()), 800)
    }

    fun zoomBy(delta: Double) {
        lastManualMove = System.currentTimeMillis()
        map.animateCamera(CameraUpdateFactory.zoomBy(delta), 300)
    }

    /** Antippen einer Alternativroute -> Index, sonst null. */
    fun routeIndexAt(screenX: Float, screenY: Float): Int? {
        val r = px(14).toFloat()
        val rect = android.graphics.RectF(screenX - r, screenY - r, screenX + r, screenY + r)
        val f = map.queryRenderedFeatures(rect, "nav-alt", "nav-sel").firstOrNull() ?: return null
        return f.getNumberProperty("idx")?.toInt()
    }

    fun distanceToDestinationText(): String? {
        val d = engine.destination ?: return null
        val p = engine.position ?: return null
        return de.sascha.kssmaps.core.Instructions.formatDistance(Geo.distance(p, d.pos))
    }

    fun dimForNight(): Int = if (night()) Color.parseColor("#202124") else Color.WHITE
}
