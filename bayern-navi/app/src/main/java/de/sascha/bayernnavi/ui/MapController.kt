package de.sascha.bayernnavi.ui

import android.content.Context
import android.graphics.Color
import android.util.Log
import de.sascha.bayernnavi.NaviApp
import de.sascha.bayernnavi.core.Bavaria
import de.sascha.bayernnavi.core.Geo
import de.sascha.bayernnavi.core.HazardType
import de.sascha.bayernnavi.core.LatLon
import de.sascha.bayernnavi.core.RankedStation
import de.sascha.bayernnavi.core.Route
import de.sascha.bayernnavi.core.RouteHazard
import de.sascha.bayernnavi.core.TomTom
import de.sascha.bayernnavi.core.Tankerkoenig
import de.sascha.bayernnavi.core.UtaStatus
import de.sascha.bayernnavi.nav.NavEngine
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
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

    /** Karte folgt der Position (aus, sobald man selbst schiebt). */
    var following = true
    /** Zeitpunkt der letzten Kamera-Bewegung durch Programm (Uebersicht, Punkt zeigen, Zoom). */
    var lastManualMove = 0L
        private set
    /** Zusaetzlicher Rand (px) fuer Bedienelemente: links, oben, rechts, unten. */
    var uiPadding = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    var viewHeightPx = 0
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
        val url = if (night()) STYLE_NIGHT else STYLE_DAY
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
                Log.e("BayernNavi", "Kartenebenen", e)
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

    private fun install(st: Style) {
        st.addImage("puck", Icons.puck(px(if (forCar) 40 else 46)))
        for (t in HazardType.values()) st.addImage("hz-" + t.name, Icons.hazard(t, px(if (forCar) 30 else 34)))
        st.addImage("dest-pin", Icons.destinationPin(px(42)))
        st.addImage("fuel-uta", Icons.fuelMarker(px(32), true))
        st.addImage("fuel-other", Icons.fuelMarker(px(32), false))
        st.addImage("avoid", Icons.avoidMarker(px(26)))

        val labels = firstSymbolLayerId(st)
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
        addBelow(st, FillLayer("nav-bld-fill", SRC_BUILDING).withProperties(
            PropertyFactory.fillColor("#E91E63"), PropertyFactory.fillOpacity(0.85f),
        ), labels)
        addBelow(st, FillExtrusionLayer("nav-bld-3d", SRC_BUILDING).withProperties(
            PropertyFactory.fillExtrusionColor("#E91E63"),
            PropertyFactory.fillExtrusionHeight(Expression.get("h")),
            PropertyFactory.fillExtrusionBase(0f),
            PropertyFactory.fillExtrusionOpacity(0.9f),
        ), labels)
        addBelow(st, LineLayer("nav-bld-line", SRC_BUILDING).withProperties(
            PropertyFactory.lineColor("#FFFFFF"), PropertyFactory.lineWidth(3f),
        ), labels)

        // --- Punkte: Meldungen, Tankstellen, Sperrpunkte, Ziel ---
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
                PropertyFactory.textSize(if (forCar) 20f else 24f), PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textHaloColor("#AD1457"), PropertyFactory.textHaloWidth(4f),
                PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
            ))
        // Eigene Position ganz oben
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

    fun updateSatellite() {
        val st = style ?: return
        val on = prefs.mapMode == 1
        val center = map.cameraPosition.target
        val inBavaria = center != null && Bavaria.contains(LatLon(center.latitude, center.longitude))
        st.getLayer("nav-sat-by")?.setProperties(PropertyFactory.visibility(if (on && inBavaria) Property.VISIBLE else Property.NONE))
        st.getLayer("nav-sat-esri")?.setProperties(PropertyFactory.visibility(if (on && !inBavaria) Property.VISIBLE else Property.NONE))
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
                    val along = engine.state?.along ?: 0.0
                    if (!force && r === lastRouteObj && along - lastRouteUpdateAlong < 40.0) return
                    lastRouteObj = r
                    lastRouteUpdateAlong = along
                    val rest = r.geometry.slice(along, r.geometry.length)
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
                if (rh.hazard.type == HazardType.SPEED_CAMERA && !prefs.camerasEnabled) continue
                feats.add(iconFeature(markerPoint(route, rh), "hz-" + rh.hazard.type.name, if (rh.hazard.type == HazardType.SPEED_CAMERA) 5 else 4))
            }
        } else {
            engine.cameraAhead?.let { feats.add(iconFeature(it.first.point, "hz-SPEED_CAMERA", 5)) }
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
            val b = engine.building
            val hn = dest.houseNumber
            if (b != null && !hn.isNullOrBlank()) {
                feats.add(Feature.fromGeometry(pt(b.label)).also {
                    it.addStringProperty("k", "hn")
                    it.addStringProperty("t", hn)
                })
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    private fun markerPoint(r: Route, rh: RouteHazard): LatLon =
        if (rh.hazard.type == HazardType.SPEED_CAMERA) rh.hazard.point else r.geometry.pointAt(rh.startAlong)

    private fun iconFeature(p: LatLon, icon: String, z: Int): Feature = Feature.fromGeometry(pt(p)).also {
        it.addStringProperty("k", "icon")
        it.addStringProperty("icon", icon)
        it.addNumberProperty("z", z)
    }

    fun updateBuilding() {
        val st = style ?: return
        val src = st.getSourceAs<GeoJsonSource>(SRC_BUILDING) ?: return
        val b = engine.building
        if (b == null || engine.destination == null) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            return
        }
        val ring = b.ring.map { pt(it) }.toMutableList()
        if (ring.first() != ring.last()) ring.add(ring.first())
        val f = Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
        f.addNumberProperty("h", ((b.levels ?: 2) * 3.2 + 3.0).toFloat())
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

    fun updatePosition() {
        val st = style ?: return
        val src = st.getSourceAs<GeoJsonSource>(SRC_POS) ?: return
        val p = (if (engine.mode == NavEngine.Mode.NAVIGATING) engine.state?.takeIf { it.onRoute }?.snapped else null) ?: engine.position
        if (p == null) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            return
        }
        val f = Feature.fromGeometry(pt(p))
        val b = if (engine.mode == NavEngine.Mode.NAVIGATING && engine.state?.onRoute == true) engine.state?.routeBearing else engine.heading
        f.addNumberProperty("b", (b ?: 0.0).toFloat())
        src.setGeoJson(FeatureCollection.fromFeature(f))
    }

    // ---------------------------------------------------------------------------------------
    // Kamera
    // ---------------------------------------------------------------------------------------

    fun zoomForSpeed(kmh: Double): Double = when {
        kmh < 25 -> 17.0
        kmh < 55 -> 16.3
        kmh < 85 -> 15.5
        kmh < 115 -> 14.9
        else -> 14.4
    }

    /** Karte mitfuehren: beim Navigieren in Fahrtrichtung gedreht und geneigt, sonst genordet. */
    fun follow(animate: Boolean = true) {
        if (!following) return
        val navigating = engine.mode == NavEngine.Mode.NAVIGATING
        val p = (if (navigating) engine.state?.takeIf { it.onRoute }?.snapped else null) ?: engine.position ?: return
        val bearing = if (navigating) (engine.state?.takeIf { it.onRoute }?.routeBearing ?: engine.heading ?: map.cameraPosition.bearing) else 0.0
        val zoom = if (navigating) zoomForSpeed(engine.speedKmh) else maxOf(map.cameraPosition.zoom, 15.0)
        val pad = uiPadding.copyOf()
        if (navigating && viewHeightPx > 0) {
            // Position ins untere Drittel schieben, damit man mehr von der Strecke voraus sieht
            pad[1] = maxOf(pad[1], viewHeightPx * 0.42)
        }
        val cp = CameraPosition.Builder()
            .target(LatLng(p.lat, p.lon))
            .zoom(zoom)
            .bearing(bearing)
            .tilt(if (navigating) 50.0 else 0.0)
            .padding(pad[0], pad[1], pad[2], pad[3])
            .build()
        if (animate) map.animateCamera(CameraUpdateFactory.newCameraPosition(cp), 950) else map.moveCamera(CameraUpdateFactory.newCameraPosition(cp))
    }

    fun recenter() {
        following = true
        follow(animate = true)
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
        return de.sascha.bayernnavi.core.Instructions.formatDistance(Geo.distance(p, d.pos))
    }

    fun dimForNight(): Int = if (night()) Color.parseColor("#202124") else Color.WHITE
}
