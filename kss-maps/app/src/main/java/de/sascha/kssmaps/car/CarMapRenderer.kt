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
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import de.sascha.kssmaps.NaviApp
import de.sascha.kssmaps.core.HazardType
import de.sascha.kssmaps.core.Instructions
import de.sascha.kssmaps.nav.NavEngine
import de.sascha.kssmaps.ui.HazardBanner
import de.sascha.kssmaps.ui.MapController
import de.sascha.kssmaps.ui.SpeedWidget
import de.sascha.kssmaps.ui.frameMatch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import kotlin.math.ln

/**
 * Zeichnet die Karte auf die Flaeche, die Android Auto der App gibt: eine VirtualDisplay
 * auf dieser Flaeche, darauf eine Presentation mit derselben MapLibre-Karte wie am Handy
 * plus Tempo-/Tempolimit-Anzeige und Warnbanner.
 */
class CarMapRenderer(private val carContext: CarContext) : SurfaceCallback, NavEngine.Listener {

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
            val dm = carContext.getSystemService(DisplayManager::class.java)
            val vd = dm.createVirtualDisplay(
                "KSSMapsCar", width, height, surfaceContainer.dpi, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            )
            virtualDisplay = vd
            val pres = Presentation(carContext, vd.display)
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
                c.nightOverride = { carContext.isDarkMode }
                ctl = c
                c.onStyleLoaded = { refreshAll(forceCamera = true) }
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

    override fun onStableAreaChanged(stableArea: Rect) {}

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
        val c = ctl ?: return
        if (engine.mode == NavEngine.Mode.PREVIEW) c.routeIndexAt(x, y)?.let { engine.selectRoute(it) }
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
        hazard?.view?.let { h ->
            val lp = h.layoutParams as? FrameLayout.LayoutParams ?: return@let
            lp.leftMargin = v.left + m
            lp.topMargin = v.top + m
            lp.rightMargin = (width - v.right) + m
            h.layoutParams = lp
        }
    }

    // ---- Zustand zeichnen ----

    override fun onStateChanged() = refreshAll(forceCamera = false)

    override fun onLocationChanged() = refreshLive()

    private var lastMode: NavEngine.Mode? = null
    private var lastRoutesKey = 0

    fun refreshAll(forceCamera: Boolean) {
        val c = ctl ?: return
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
                nh != null -> {
                    val h = nh.first.hazard
                    hb.show(h.type, if (nh.second <= 0 && h.type.isTraffic) "Im ${h.type.label}" else "${Instructions.hazardShort(h)} · ${Instructions.formatDistance(nh.second)}")
                }
                cam != null -> hb.show(cam.first.type, "${Instructions.hazardShort(cam.first)} · ${Instructions.formatDistance(cam.second)}")
                else -> hb.hide()
            }
        }
        val c = ctl ?: return
        if (c.style == null) return
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

    fun onDarkModeChanged() {
        ctl?.applyStyle()
    }

    fun release() {
        if (listening) {
            engine.removeListener(this)
            listening = false
        }
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
    }

    @Suppress("unused")
    private fun View.hideIf(b: Boolean) {
        visibility = if (b) View.GONE else View.VISIBLE
    }
}
