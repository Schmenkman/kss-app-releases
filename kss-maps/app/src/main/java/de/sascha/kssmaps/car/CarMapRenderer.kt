package de.sascha.kssmaps.car

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import de.sascha.kssmaps.NaviApp
import de.sascha.kssmaps.core.HazardType
import de.sascha.kssmaps.core.Instructions
import de.sascha.kssmaps.nav.NavEngine
import de.sascha.kssmaps.ui.HazardBanner
import de.sascha.kssmaps.ui.Icons
import de.sascha.kssmaps.ui.MapController
import de.sascha.kssmaps.ui.SpeedWidget
import de.sascha.kssmaps.ui.Ui
import de.sascha.kssmaps.ui.frameMatch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import kotlin.math.ln

/**
 * Zeichnet die Karte auf die Flaeche, die Android Auto der App gibt: eine VirtualDisplay
 * auf dieser Flaeche, darauf eine Presentation mit derselben MapLibre-Karte wie am Handy
 * plus Tempo-/Tempolimit-Anzeige, Warnbanner und – ab Android-Auto-Schnittstelle 5 – eigene,
 * immer sichtbare Knoepfe (rechts untereinander, Zoom und Beenden unten). Tag/Nacht folgt der
 * Einstellung der App (Automatisch = Sonnenstand), nicht dem Nachtmodus des Autos.
 */
class CarMapRenderer(
    private val context: Context,
    /** Eigene Knoepfe auf der Karte (Android Auto meldet Antippen erst ab Schnittstelle 5). */
    private val customButtons: Boolean = true,
) : SurfaceCallback, NavEngine.Listener {

    enum class Btn { CALL, APPOINTMENTS, OVERVIEW, DAYNIGHT, TRAFFIC_FIX, FUEL, MUTE, SETTINGS, RECENTER, ZOOM_OUT, ZOOM_IN, END }

    /** Knopf angetippt – der Bildschirm im Auto oeffnet dann die passende Ansicht. */
    var onButton: ((Btn) -> Unit)? = null
    private val buttons = LinkedHashMap<Btn, ImageView>()
    private val buttonState = HashMap<Btn, String>()
    private var rightCol: LinearLayout? = null
    private var bottomRow: LinearLayout? = null
    private var stableArea: Rect? = null
    private var lastStyleCheck = 0L

    private val engine: NavEngine get() = NaviApp.instance.engine
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    var ctl: MapController? = null
        private set
    private var speed: SpeedWidget? = null
    private var hazard: HazardBanner? = null
    private var overlayRoot: FrameLayout? = null
    private var width = 0
    private var height = 0
    private var visibleArea: Rect? = null
    private var listening = false

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface ?: return
        release()
        width = surfaceContainer.width
        height = surfaceContainer.height
        if (width <= 0 || height <= 0) return
        try {
            val dm = context.getSystemService(DisplayManager::class.java)
            val vd = dm.createVirtualDisplay(
                "KSSMapsCar", width, height, surfaceContainer.dpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
            virtualDisplay = vd
            val pres = Presentation(context, vd.display)
            pres.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            val ctx: Context = pres.context
            val opts = MapLibreMapOptions.createFromAttributes(ctx)
                .textureMode(true).attributionEnabled(false).logoEnabled(false).compassEnabled(false)
            val mv = MapView(ctx, opts)
            val root = FrameLayout(ctx)
            root.addView(mv, frameMatch())
            val ov = FrameLayout(ctx)
            val sw = SpeedWidget(ctx)
            ov.addView(sw.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
            val hb = HazardBanner(ctx)
            ov.addView(hb.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
            if (customButtons) buildButtons(ctx, ov)
            root.addView(ov, frameMatch())
            pres.setContentView(root)
            pres.show()
            mv.onCreate(null)
            mv.onStart()
            mv.onResume()
            mapView = mv
            presentation = pres
            speed = sw
            hazard = hb
            overlayRoot = ov
            mv.getMapAsync { map ->
                map.uiSettings.setAllGesturesEnabled(false)
                map.uiSettings.isCompassEnabled = false
                val c = MapController(ctx, map, forCar = true)
                ctl = c
                c.onStyleLoaded = { refreshAll(forceCamera = true) }
                map.addOnCameraIdleListener { c.onCameraIdle() }
                applyPadding()
                val p = engine.position ?: engine.prefs.lastPosition
                if (p != null) map.moveCamera(CameraUpdateFactory.newLatLngZoom(org.maplibre.android.geometry.LatLng(p.lat, p.lon), 15.0))
                c.applyStyle()
            }
            mv.addOnDidFailLoadingMapListener { ctl?.onStyleFailed() }
            if (!listening) {
                engine.addListener(this)
                listening = true
            }
            layoutOverlay()
        } catch (e: Exception) {
            Log.e("KSSMaps", "Android-Auto-Karte", e)
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = Rect(visibleArea)
        applyPadding()
        layoutOverlay()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        this.stableArea = Rect(stableArea)
        layoutOverlay()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        release()
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val c = ctl ?: return
        c.following = false
        c.map.scrollBy(-distanceX, -distanceY, 0)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        val c = ctl ?: return
        c.following = false
        c.map.scrollBy(velocityX * 0.15f, velocityY * 0.15f, 300)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        val c = ctl ?: return
        if (scaleFactor <= 0f) return
        val delta = ln(scaleFactor.toDouble()) / ln(2.0)
        if (focusX < 0 || focusY < 0) c.map.moveCamera(CameraUpdateFactory.zoomBy(delta))
        else c.map.moveCamera(CameraUpdateFactory.zoomBy(delta, Point(focusX.toInt(), focusY.toInt())))
    }

    override fun onClick(x: Float, y: Float) {
        hitButton(x, y)?.let { b ->
            val cb = onButton
            if (cb != null) cb(b) else Log.i("KSSMaps", "TEST_CAR_BUTTON $b")
            return
        }
        val c = ctl ?: return
        if (engine.mode == NavEngine.Mode.PREVIEW) c.routeIndexAt(x, y)?.let { engine.selectRoute(it) }
        else if (engine.navAlternatives.isNotEmpty()) c.routeIndexAt(x, y)?.let { engine.selectNavAlternative(it) }
    }

    // ---- eigene Knoepfe ----

    private fun buildButtons(ctx: Context, ov: FrameLayout) {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (b in COLUMN) {
            val v = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER
                elevation = Ui.dpf(ctx, 4f)
                contentDescription = b.name
            }
            buttons[b] = v
            col.addView(v)
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (b in listOf(Btn.ZOOM_OUT, Btn.ZOOM_IN, Btn.END)) {
            val v = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER
                elevation = Ui.dpf(ctx, 4f)
                contentDescription = b.name
            }
            buttons[b] = v
            row.addView(v)
        }
        ov.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        ov.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        rightCol = col
        bottomRow = row
    }

    private fun density(): Float = presentation?.context?.resources?.displayMetrics?.density ?: 1f

    companion object {
        /** Rechte Knopfspalte von oben nach unten. */
        private val COLUMN = listOf(Btn.CALL, Btn.APPOINTMENTS, Btn.OVERVIEW, Btn.DAYNIGHT, Btn.TRAFFIC_FIX, Btn.FUEL, Btn.MUTE, Btn.SETTINGS, Btn.RECENTER)
    }

    private fun buttonSizePx(): Int {
        val d = density()
        val sa = stableArea ?: visibleArea ?: Rect(0, 0, width, height)
        val n = COLUMN.size
        val m = 8 * d
        val gap = 6 * d
        return ((sa.height() - 2 * m - (n - 1) * gap) / n).coerceIn(28 * d, 56 * d).toInt()
    }

    /** Knoepfe zeichnen: aktiv/inaktiv, Tag/Nacht-Symbol, Beenden nur mit Route. */
    private fun updateButtons() {
        if (buttons.isEmpty()) return
        val ctx = presentation?.context ?: return
        val e = engine
        val night = e.isNight()
        val size = buttonSizePx()
        val icon = (size * 0.56f).toInt()
        val bg = if (night) Color.parseColor("#303134") else Color.WHITE
        val fg = if (night) Color.parseColor("#E8EAED") else Ui.TEXT
        val off = if (night) Color.parseColor("#6E7175") else Color.parseColor("#B0B4B8")
        val navigating = e.mode == NavEngine.Mode.NAVIGATING
        for ((b, v) in buttons) {
            val visible = b != Btn.END || e.mode != NavEngine.Mode.IDLE
            v.visibility = if (visible) View.VISIBLE else View.GONE
            if (!visible) continue
            val (glyph, color, back) = when (b) {
                Btn.CALL -> Triple(Icons.Glyph.PHONE, if (e.activeAppointment != null) Color.WHITE else off, if (e.activeAppointment != null) Ui.GREEN_DARK else bg)
                Btn.APPOINTMENTS -> Triple(Icons.Glyph.CALENDAR, if (night) Color.parseColor("#8AB4F8") else Ui.BLUE, bg)
                Btn.OVERVIEW -> Triple(Icons.Glyph.ROUTE, if (navigating) fg else off, bg)
                Btn.DAYNIGHT -> Triple(when (e.prefs.nightMode) { 1 -> Icons.Glyph.SUN; 2 -> Icons.Glyph.MOON; else -> Icons.Glyph.DAYNIGHT }, fg, bg)
                Btn.TRAFFIC_FIX -> Triple(Icons.Glyph.JAM_AVOID, if (navigating) fg else off, bg)
                Btn.FUEL -> Triple(Icons.Glyph.FUEL, fg, bg)
                Btn.MUTE -> if (e.prefs.voiceMuted) Triple(Icons.Glyph.VOLUME_OFF, Color.WHITE, Ui.RED) else Triple(Icons.Glyph.VOLUME_ON, fg, bg)
                Btn.SETTINGS -> Triple(Icons.Glyph.SETTINGS, fg, bg)
                Btn.RECENTER -> Triple(Icons.Glyph.RECENTER, if (ctl?.following == false) Color.WHITE else fg, if (ctl?.following == false) Ui.BLUE else bg)
                Btn.ZOOM_OUT -> Triple(Icons.Glyph.MINUS, fg, bg)
                Btn.ZOOM_IN -> Triple(Icons.Glyph.PLUS, fg, bg)
                Btn.END -> Triple(Icons.Glyph.CLOSE, Color.WHITE, Ui.RED)
            }
            val key = "$glyph/$color/$back/$size"
            if (buttonState[b] == key) continue
            buttonState[b] = key
            // Bild mit der Dichte des Auto-Bildschirms: sonst rechnet Android es von der (hoeheren)
            // Dichte des Handys herunter und das Symbol wird winzig
            v.setImageBitmap(Icons.glyph(glyph, icon, color).apply { density = ctx.resources.displayMetrics.densityDpi })
            v.background = Ui.bg(ctx, back, (size / density() / 2).toInt())
        }
    }

    /** Welcher Knopf liegt unter dem Finger (mit etwas Rand, Auto-Bildschirme sind grob)? */
    private fun hitButton(x: Float, y: Float): Btn? {
        val pad = 6 * density()
        val loc = IntArray(2)
        for ((b, v) in buttons) {
            if (!v.isShown || v.width == 0) continue
            v.getLocationInWindow(loc)
            if (x >= loc[0] - pad && x <= loc[0] + v.width + pad && y >= loc[1] - pad && y <= loc[1] + v.height + pad) return b
        }
        return null
    }

    /** Nur Testfahrt: Antippen eines Knopfs nachstellen (Mitte des Knopfs). */
    fun testClick(name: String) {
        val v = buttons.entries.firstOrNull { it.key.name == name }?.value ?: return
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        onClick(loc[0] + v.width / 2f, loc[1] + v.height / 2f)
    }

    private fun applyPadding() {
        val c = ctl ?: return
        val v = visibleArea
        if (v != null && width > 0 && height > 0) {
            c.uiPadding = doubleArrayOf(v.left.toDouble(), v.top.toDouble(), (width - v.right).toDouble(), (height - v.bottom).toDouble())
            c.viewHeightPx = v.height()
        } else {
            c.uiPadding = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
            c.viewHeightPx = height
        }
    }

    private fun layoutOverlay() {
        val v = visibleArea ?: Rect(0, 0, width, height)
        val m = (8 * (presentation?.context?.resources?.displayMetrics?.density ?: 1f)).toInt()
        speed?.view?.let { s ->
            val lp = s.layoutParams as? FrameLayout.LayoutParams ?: return@let
            lp.leftMargin = v.left + m
            lp.bottomMargin = (height - v.bottom) + m
            s.layoutParams = lp
        }
        val size = buttonSizePx()
        val gap = (6 * density()).toInt()
        val sa = stableArea ?: v
        rightCol?.let { col ->
            for (i in 0 until col.childCount) {
                val lp = col.getChildAt(i).layoutParams as LinearLayout.LayoutParams
                lp.width = size
                lp.height = size
                lp.topMargin = if (i == 0) 0 else gap
                col.getChildAt(i).layoutParams = lp
            }
            val lp = col.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = sa.top + m
            lp.rightMargin = (width - sa.right) + m
            col.layoutParams = lp
        }
        bottomRow?.let { row ->
            for (i in 0 until row.childCount) {
                val lp = row.getChildAt(i).layoutParams as LinearLayout.LayoutParams
                lp.width = size
                lp.height = size
                lp.leftMargin = if (i == 0) 0 else gap
                row.getChildAt(i).layoutParams = lp
            }
            // neben Tempo und Tempolimit (unten links), dort stoert es die Sicht auf die Strasse nicht
            val lp = row.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = v.left + m + (148 * density()).toInt()
            lp.bottomMargin = (height - v.bottom) + m
            row.layoutParams = lp
        }
        hazard?.view?.let { h ->
            val lp = h.layoutParams as? FrameLayout.LayoutParams ?: return@let
            lp.leftMargin = v.left + m
            lp.topMargin = v.top + m
            // nicht unter die Knopfspalte
            lp.rightMargin = (width - v.right) + m + if (rightCol != null) size + m else 0
            h.layoutParams = lp
        }
        buttonState.clear()
        updateButtons()
    }

    // ---- Zustand zeichnen ----

    override fun onStateChanged() = refreshAll(forceCamera = false)

    override fun onLocationChanged() = refreshLive()

    private var lastMode: NavEngine.Mode? = null
    private var lastRoutesKey = 0

    fun refreshAll(forceCamera: Boolean) {
        val c = ctl ?: return
        if (c.style == null) return
        c.applyStyle() // Luftbild am Handy umgeschaltet / Tag-Nacht – laedt nur bei Aenderung neu
        if (c.style == null) return
        c.updateRoutes(force = true)
        c.updatePoints()
        c.updateBuilding()
        c.updateAvoid()
        c.updatePosition()
        val e = engine
        val key = System.identityHashCode(e.routes) + e.selectedIndex * 31
        if (forceCamera || e.mode != lastMode || (e.mode == NavEngine.Mode.PREVIEW && key != lastRoutesKey)) {
            when (e.mode) {
                NavEngine.Mode.PREVIEW -> if (e.routes.isNotEmpty()) c.showRoutesOverview()
                NavEngine.Mode.NAVIGATING -> c.recenter()
                NavEngine.Mode.IDLE -> if (lastMode == NavEngine.Mode.NAVIGATING) e.destination?.let { c.showPoint(it.pos, 18.0) } ?: c.recenter() else c.recenter()
            }
            lastRoutesKey = key
        }
        lastMode = e.mode
        refreshLive()
    }

    private fun refreshLive() {
        val e = engine
        speed?.render(e)
        val nh = e.nearestHazard
        val cam = e.cameraAhead
        val hb = hazard
        if (hb != null) {
            when {
                // "Baustelle in 800 m", in der Baustelle "Baustelle noch 1,2 km"
                nh != null -> hb.show(nh.first.hazard.type, Instructions.hazardBanner(nh.first, nh.second, e.state?.along ?: 0.0))
                cam != null -> hb.show(cam.first.type, Instructions.cameraBanner(cam.first, cam.second))
                else -> hb.hide()
            }
        }
        updateButtons()
        val c = ctl ?: return
        if (c.style == null) return
        val now = System.currentTimeMillis()
        if (now - lastStyleCheck > 30_000) {
            lastStyleCheck = now
            c.applyStyle() // Automatisch: Sonnenauf- und -untergang
        }
        c.updatePosition()
        if (e.mode == NavEngine.Mode.NAVIGATING) {
            c.updateRoutes()
            c.updatePoints()
        } else if (cam != null) c.updatePoints()
        if (c.following && e.mode != NavEngine.Mode.PREVIEW) c.follow()
    }

    fun recenter() {
        ctl?.recenter()
    }

    fun zoom(delta: Double) {
        ctl?.let {
            it.following = false
            it.zoomBy(delta)
        }
    }

    /** Nachtmodus des Autos geaendert: Karte folgt der App-Einstellung, nur neu pruefen. */
    fun onDarkModeChanged() {
        ctl?.applyStyle()
    }

    /** Uebersicht: ganze Strecke ab hier mit Alternativen. */
    fun showOverview() {
        ctl?.showNavAlternativesOverview()
        updateButtons()
    }

    fun release() {
        if (listening) {
            engine.removeListener(this)
            listening = false
        }
        ctl?.dispose()
        try {
            mapView?.let {
                it.onPause()
                it.onStop()
                it.onDestroy()
            }
        } catch (e: Exception) {
            Log.w("KSSMaps", "MapView beenden: ${e.message}")
        }
        mapView = null
        ctl = null
        try {
            presentation?.dismiss()
        } catch (e: Exception) {
            // Display schon weg
        }
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
        speed = null
        hazard = null
        overlayRoot = null
        buttons.clear()
        buttonState.clear()
        rightCol = null
        bottomRow = null
    }

    @Suppress("unused")
    private fun View.hideIf(b: Boolean) {
        visibility = if (b) View.GONE else View.VISIBLE
    }
}
