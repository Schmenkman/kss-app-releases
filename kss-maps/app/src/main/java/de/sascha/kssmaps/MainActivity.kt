package de.sascha.kssmaps

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.sascha.kssmaps.core.Bavaria
import de.sascha.kssmaps.core.Geocoding
import de.sascha.kssmaps.core.HazardType
import de.sascha.kssmaps.core.Instructions
import de.sascha.kssmaps.core.LatLon
import de.sascha.kssmaps.data.Services
import de.sascha.kssmaps.nav.NavEngine
import de.sascha.kssmaps.ui.AppointmentsPanel
import de.sascha.kssmaps.ui.AvoidPanel
import de.sascha.kssmaps.ui.CustomerDrawer
import de.sascha.kssmaps.ui.FuelPanel
import de.sascha.kssmaps.ui.HazardBanner
import de.sascha.kssmaps.ui.HazardListPanel
import de.sascha.kssmaps.ui.Icons
import de.sascha.kssmaps.ui.ManeuverBanner
import de.sascha.kssmaps.ui.MapController
import de.sascha.kssmaps.ui.NavBar
import de.sascha.kssmaps.ui.Panel
import de.sascha.kssmaps.ui.PanelHost
import de.sascha.kssmaps.ui.RoutePanel
import de.sascha.kssmaps.ui.SearchPanel
import de.sascha.kssmaps.ui.SettingsPanel
import de.sascha.kssmaps.ui.SpeedWidget
import de.sascha.kssmaps.ui.StepsPanel
import de.sascha.kssmaps.ui.Ui
import de.sascha.kssmaps.ui.Ui.margins
import de.sascha.kssmaps.ui.frameMatch
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import kotlin.math.roundToInt

class MainActivity : Activity(), NavEngine.Listener, PanelHost {

    private val app: NaviApp get() = application as NaviApp
    override val engine: NavEngine get() = app.engine
    override val services: Services get() = app.services
    override val context: Context get() = this
    override var mapCtl: MapController? = null
        private set
    private val prefs get() = engine.prefs
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var mapView: MapView
    private lateinit var topCol: LinearLayout
    private lateinit var searchBar: LinearLayout
    private lateinit var searchText: TextView
    private lateinit var maneuver: ManeuverBanner
    private lateinit var hazardBanner: HazardBanner
    private lateinit var betterBanner: LinearLayout
    private lateinit var betterText: TextView
    private lateinit var msgBanner: TextView
    private lateinit var rightCol: LinearLayout
    private lateinit var btnLayers: ImageView
    private lateinit var btnMute: ImageView
    private lateinit var btnRecenter: ImageView
    private lateinit var btnJam: ImageView
    private lateinit var btnFuel: ImageView
    private lateinit var btnReport: ImageView
    private lateinit var btnAvoid: ImageView
    private lateinit var btnAppointments: ImageView
    private lateinit var btnCustomer: ImageView
    private lateinit var speed: SpeedWidget
    private lateinit var speedRow: LinearLayout
    private lateinit var alertCol: LinearLayout
    private lateinit var bottomCol: LinearLayout
    private lateinit var bottom: FrameLayout
    private lateinit var routePanel: RoutePanel
    private lateinit var navBar: NavBar
    private lateinit var roadLabel: TextView

    private lateinit var search: SearchPanel
    private lateinit var fuel: FuelPanel
    private lateinit var avoid: AvoidPanel
    private lateinit var hazardList: HazardListPanel
    private lateinit var settings: SettingsPanel
    private lateinit var steps: StepsPanel
    private lateinit var appointmentsPanel: AppointmentsPanel
    /** Seitliche Kunden-Leiste (Termin, zu dem gefahren wird). */
    private lateinit var drawer: CustomerDrawer
    private val panels = ArrayList<Panel>()

    /** Querformat: Bedienung links (wie Google Maps in Android Auto), Karte rechts frei. */
    private var landscape = false
    /** Breite der linken Spalte im Querformat (px). */
    private var sideW = 0
    private var voiceCallback: ((String) -> Unit)? = null
    private val reqVoice = 4711
    private val reqCall = 43
    /** Nummer, die nach der Anruf-Berechtigung gewaehlt wird. */
    private var pendingCall: String? = null

    private var lastMode: NavEngine.Mode? = null
    private var lastGesture = 0L
    private var lastRoutesKey = 0
    private var lastMessage: String? = null
    private var lastNight: Boolean? = null
    private var lastNightCheck = 0L

    // =======================================================================================
    // Aufbau
    // =======================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map -> setupMap(map) }
        engine.addListener(this)
        requestPermissionsIfNeeded()
        handleIntent(intent)
        firstRunDialogs()
        checkUpdate(manual = false)
        // Termine schon im Hintergrund holen, damit "Termine" (Handy und Auto) sofort etwas zeigt
        if (engine.appointmentSource.configured && engine.appointments.isEmpty() && !engine.appointmentsLoading) engine.loadAppointments()
    }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun buildUi() {
        root = FrameLayout(this)
        root.fitsSystemWindows = true
        root.setBackgroundColor(Color.parseColor("#E8EEF4"))

        mapView = MapView(this, MapLibreMapOptions.createFromAttributes(this).attributionEnabled(true).logoEnabled(false).compassEnabled(true))
        root.addView(mapView, frameMatch())

        // ---- oben: Suchleiste / Manoever-Banner / Warnungen ----
        topCol = Ui.vbox(this)
        searchBar = Ui.hbox(this)
        searchBar.setPadding(dp(16), dp(4), dp(6), dp(4))
        searchBar.background = Ui.bg(this, Color.WHITE, 28)
        searchBar.elevation = Ui.dpf(this, 6f)
        searchBar.addView(ImageView(this).apply { setImageBitmap(Icons.glyph(Icons.Glyph.SEARCH, dp(24), Ui.TEXT2)) }, LinearLayout.LayoutParams(dp(24), dp(24)))
        searchText = Ui.text(this, "Wohin?", 18f, Ui.TEXT2)
        searchText.setPadding(dp(12), dp(12), dp(8), dp(12))
        searchBar.addView(searchText, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchBar.setOnClickListener { openSearch() }
        searchText.setOnClickListener { openSearch() }
        searchBar.addView(Ui.iconButton(this, Icons.glyph(Icons.Glyph.MIC, dp(24), Ui.BLUE), Color.WHITE, 46, "Spracheingabe") { openSearch(voice = true) }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = 0
            elevation = 0f
        })
        searchBar.addView(Ui.iconButton(this, Icons.glyph(Icons.Glyph.SETTINGS, dp(24), Ui.TEXT2), Color.WHITE, 46, "Einstellungen") { openSettings() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = 0
            elevation = 0f
        })
        topCol.addView(searchBar, Ui.linLp())

        maneuver = ManeuverBanner(this)
        topCol.addView(maneuver.view, Ui.linLp())
        maneuver.view.setOnClickListener { hazardList.show() }

        alertCol = Ui.vbox(this)
        hazardBanner = HazardBanner(this)
        alertCol.addView(hazardBanner.view, Ui.linLp().margins(this, t = 8))
        hazardBanner.view.setOnClickListener { hazardList.show() }

        betterBanner = Ui.hbox(this)
        betterBanner.background = Ui.bg(this, Ui.BLUE, 16)
        betterBanner.elevation = Ui.dpf(this, 6f)
        betterBanner.setPadding(dp(14), dp(8), dp(8), dp(8))
        betterText = Ui.text(this, "", 16f, Color.WHITE, bold = true, maxLines = 2)
        betterBanner.addView(betterText, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        betterBanner.addView(Ui.button(this, "Übernehmen", Color.WHITE, Ui.BLUE) { engine.acceptBetterRoute() })
        betterBanner.addView(Ui.button(this, "Nein", Ui.BLUE_DARK) { engine.dismissBetterRoute() }, Ui.linLp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(this, l = 6))
        alertCol.addView(betterBanner, Ui.linLp().margins(this, t = 8))

        msgBanner = Ui.text(this, "", 15f, Color.WHITE, maxLines = 3)
        msgBanner.background = Ui.bg(this, Color.parseColor("#E6202124"), 14)
        msgBanner.setPadding(dp(14), dp(10), dp(14), dp(10))
        msgBanner.setOnClickListener { engine.clearMessage() }
        alertCol.addView(msgBanner, Ui.linLp().margins(this, t = 8))
        topCol.addView(alertCol, Ui.linLp())

        root.addView(topCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(10), dp(10), dp(10), 0)
        })

        // ---- rechts: Knoepfe ----
        rightCol = Ui.vbox(this)
        // Kunde anrufen: nur sichtbar, wenn zu einem Termin gefahren wird
        btnCustomer = Ui.iconButton(this, Icons.glyph(Icons.Glyph.PHONE, dp(26), Color.WHITE), Ui.GREEN_DARK, desc = "Kunde") { toggleCustomer() }
        rightCol.addView(btnCustomer)
        btnLayers = Ui.iconButton(this, Icons.glyph(Icons.Glyph.LAYERS, dp(26), Ui.TEXT), Color.WHITE, desc = "Luftbild") { toggleSatellite() }
        rightCol.addView(btnLayers)
        btnAppointments = Ui.iconButton(this, Icons.glyph(Icons.Glyph.CALENDAR, dp(26), Ui.BLUE), Color.WHITE, desc = "Termine") { openAppointments() }
        rightCol.addView(btnAppointments)
        btnMute = Ui.iconButton(this, Icons.glyph(Icons.Glyph.VOLUME_ON, dp(26), Ui.TEXT), Color.WHITE, desc = "Ton") { engine.toggleMuted() }
        rightCol.addView(btnMute)
        btnReport = Ui.iconButton(this, Icons.glyph(Icons.Glyph.REPORT, dp(26), Ui.RED), Color.WHITE, desc = "Blitzer melden") { reportDialog() }
        rightCol.addView(btnReport)
        btnFuel = Ui.iconButton(this, Icons.glyph(Icons.Glyph.FUEL, dp(26), Ui.TEXT), Color.WHITE, desc = "Tanken") { openFuel() }
        rightCol.addView(btnFuel)
        btnJam = Ui.iconButton(this, Icons.glyph(Icons.Glyph.JAM_AVOID, dp(26), Ui.TEXT), Color.WHITE, desc = "Stau umfahren") {
            engine.avoidJamsNow()
            toast("Suche Umfahrung …")
        }
        rightCol.addView(btnJam)
        btnAvoid = Ui.iconButton(this, Icons.glyph(Icons.Glyph.AVOID, dp(26), Ui.TEXT), Color.WHITE, desc = "Gemiedene Abschnitte") { avoid.show() }
        rightCol.addView(btnAvoid)
        btnRecenter = Ui.iconButton(this, Icons.glyph(Icons.Glyph.RECENTER, dp(26), Color.WHITE), Ui.BLUE, desc = "Zentrieren") {
            mapCtl?.recenter()
            updateRecenter()
        }
        rightCol.addView(btnRecenter)
        // Knopfspalte oben rechts, direkt unter Suchleiste bzw. Abbiege-Banner (Abstand wird
        // in updateMapPadding nachgefuehrt), damit sie nie die Routen-/Fahrtleiste unten verdeckt.
        root.addView(rightCol, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.TOP).apply {
            setMargins(0, dp(80), dp(10), 0)
        })

        // ---- unten: Tempo, Strassenname, Routen-/Navigationsleiste ----
        bottom = FrameLayout(this)
        bottomCol = Ui.vbox(this)
        speedRow = Ui.hbox(this)
        speed = SpeedWidget(this)
        speedRow.addView(speed.view)
        roadLabel = Ui.text(this, "", 15f, Color.WHITE, bold = true, maxLines = 1)
        roadLabel.background = Ui.bg(this, Color.parseColor("#CC202124"), 14)
        roadLabel.setPadding(dp(12), dp(6), dp(12), dp(6))
        speedRow.addView(roadLabel, Ui.linLp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(this, l = 10))
        bottomCol.addView(speedRow, Ui.linLp().margins(this, b = 8))
        routePanel = RoutePanel(
            this,
            onSelect = { engine.selectRoute(it) },
            onStart = { startNavigation() },
            onAvoidJams = { engine.avoidJamsNow() },
            onFuel = { openFuel() },
            onClose = { engine.cancelPreview() },
            onPickOrigin = { closePanels(); search.open(SearchPanel.Target.ORIGIN, voice = false) },
            onPickDestination = { closePanels(); search.open(SearchPanel.Target.DESTINATION, voice = false) },
            onSwap = { engine.swapOriginDestination() },
            onOriginHere = { engine.setOrigin(null) },
            onSteps = { openSteps() },
        )
        bottomCol.addView(routePanel.view, Ui.linLp())
        navBar = NavBar(this, onStop = { confirmStop() }, onFuel = { openFuel() }, onList = { hazardList.show() })
        navBar.onRemoveFuel = {
            AlertDialog.Builder(this).setTitle("Tank-Zwischenstopp entfernen?")
                .setPositiveButton("Entfernen") { _, _ -> engine.removeFuelStop() }
                .setNegativeButton("Abbrechen", null).show()
        }
        bottomCol.addView(navBar.view, Ui.linLp())
        bottom.addView(bottomCol, frameMatch())
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(10), 0, dp(10), dp(10))
        })

        // ---- Overlays ----
        search = SearchPanel(this)
        fuel = FuelPanel(this)
        avoid = AvoidPanel(this)
        hazardList = HazardListPanel(this)
        settings = SettingsPanel(this, onChanged = { onSettingsChanged() }, onCheckUpdate = { checkUpdate(manual = true) })
        steps = StepsPanel(this)
        appointmentsPanel = AppointmentsPanel(this, onSetup = { openSettings() })
        // Kunden-Leiste rechts ueber den Knoepfen; Groesse/Abstaende in layoutDrawer()
        drawer = CustomerDrawer(this, onCall = { name, display, digits -> confirmCall(name, display, digits) }, onClose = { hideCustomer() })
        root.addView(drawer.view, FrameLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END or Gravity.TOP))
        panels.addAll(listOf(search, fuel, avoid, hazardList, steps, appointmentsPanel, settings))
        for (p in panels) root.addView(p.view, frameMatch())

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateMapPadding() }
        setContentView(root)
        applyLayout()
    }

    /**
     * Hochkant: Banner oben, Leiste unten ueber die ganze Breite (wie bisher).
     * Quer: alles in einer Spalte links – Abbiege-Banner oben, Ankunft/Knoepfe unten –, die Karte
     * rechts bleibt frei; Tempo und Strassenname unten links auf der Karte, Warnungen oben.
     */
    private fun applyLayout() {
        val cfg = resources.configuration
        landscape = cfg.screenWidthDp > 0 && cfg.screenWidthDp > cfg.screenHeightDp * 1.2
        sideW = if (landscape) dp((cfg.screenWidthDp * 0.40).roundToInt().coerceIn(290, 420)) else 0
        val m = dp(10)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        topCol.layoutParams = FrameLayout.LayoutParams(if (landscape) sideW else match, wrap, Gravity.TOP or Gravity.START).apply {
            setMargins(m, m, if (landscape) 0 else m, 0)
        }
        bottom.layoutParams = FrameLayout.LayoutParams(if (landscape) sideW else match, wrap, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(m, 0, if (landscape) 0 else m, m)
        }
        (alertCol.parent as? ViewGroup)?.removeView(alertCol)
        if (landscape) {
            root.addView(alertCol, root.indexOfChild(rightCol), FrameLayout.LayoutParams(match, wrap, Gravity.TOP or Gravity.START).apply {
                setMargins(sideW + 2 * m, 0, dp(76), 0)
            })
        } else topCol.addView(alertCol, Ui.linLp())
        (speedRow.parent as? ViewGroup)?.removeView(speedRow)
        if (landscape) {
            root.addView(speedRow, root.indexOfChild(rightCol), FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(sideW + 2 * m, 0, dp(76), m)
            })
        } else bottomCol.addView(speedRow, 0, Ui.linLp().margins(this, b = 8))
        maneuver.setCompact(landscape)
        navBar.setCompact(landscape)
        val h = dp(cfg.screenHeightDp)
        routePanel.setListMaxHeight(if (landscape) (h - dp(250)).coerceAtLeast(dp(80)) else (h * 0.32).roundToInt())
        val sheetW = maxOf(sideW, dp((cfg.screenWidthDp * 0.5).roundToInt()))
        for (p in panels) p.applyLayout(landscape, sheetW)
        mapCtl?.let { applyMapUi(it.map) }
    }

    /** Kompass und Copyright-Hinweis dorthin, wo sie nichts verdecken. */
    private fun applyMapUi(map: MapLibreMap) {
        val ui = map.uiSettings
        if (landscape) {
            ui.compassGravity = Gravity.BOTTOM or Gravity.END
            ui.setCompassMargins(0, 0, dp(14), dp(52))
            ui.attributionGravity = Gravity.BOTTOM or Gravity.END
            ui.setAttributionMargins(0, 0, dp(12), dp(10))
        } else {
            ui.compassGravity = Gravity.TOP or Gravity.START
            ui.setCompassMargins(dp(14), dp(170), 0, 0)
            ui.attributionGravity = Gravity.BOTTOM or Gravity.START
            ui.setAttributionMargins(dp(8), 0, 0, dp(8))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLayout()
        renderAll(false)
        // Nach dem Drehen die Routenuebersicht neu einpassen (die Leiste sitzt jetzt woanders)
        handler.postDelayed({
            updateMapPadding()
            if (engine.mode == NavEngine.Mode.PREVIEW && engine.routes.isNotEmpty()) mapCtl?.showRoutesOverview()
        }, 400)
    }

    private fun setupMap(map: MapLibreMap) {
        val ctl = MapController(this, map, forCar = false)
        mapCtl = ctl
        map.uiSettings.isCompassEnabled = true
        map.uiSettings.isLogoEnabled = false
        map.uiSettings.isAttributionEnabled = true
        applyMapUi(map)
        val start = engine.position ?: prefs.lastPosition
        val c = start ?: Bavaria.CENTER
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(c.lat, c.lon), if (start != null) 15.0 else 7.0))
        ctl.onStyleLoaded = { renderAll(forceCamera = true) }
        ctl.applyStyle()
        map.addOnMapLongClickListener { ll ->
            onMapLongPress(LatLon(ll.latitude, ll.longitude))
            true
        }
        map.addOnMapClickListener { ll ->
            if (engine.mode == NavEngine.Mode.PREVIEW) {
                val sp = map.projection.toScreenLocation(ll)
                val idx = ctl.routeIndexAt(sp.x, sp.y)
                if (idx != null) {
                    engine.selectRoute(idx)
                    return@addOnMapClickListener true
                }
            }
            false
        }
        map.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                ctl.following = false
                lastGesture = System.currentTimeMillis()
                updateRecenter()
            }
        }
        map.addOnCameraIdleListener { ctl.onCameraIdle() }
        mapView.addOnDidFailLoadingMapListener { ctl.onStyleFailed() }
        updateMapPadding()
    }

    private fun updateMapPadding() {
        val m = dp(10)
        // Knopfspalte: hochkant unter Suchleiste/Banner, quer ganz oben (die Spalte links ist frei)
        val wantTop = if (landscape) m else topCol.bottom + dp(4)
        (rightCol.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.topMargin != wantTop && (landscape || topCol.height > 0)) {
                lp.topMargin = wantTop
                rightCol.layoutParams = lp
            }
        }
        layoutDrawer()
        val ctl = mapCtl ?: return
        // Offene Kunden-Leiste: Karte nach links ruecken, damit Pfeil und Fahrweg frei bleiben
        val right = maxOf(rightCol.width + m, if (drawer.isOpen) drawer.widthPx + 2 * m else 0)
        val pad = if (landscape) {
            val panel = topCol.height > 0 || bottom.height > 0
            doubleArrayOf((if (panel) sideW + 2 * m else m).toDouble(), m.toDouble(), right.toDouble(), m.toDouble())
        } else {
            val topH = if (topCol.visibility == View.VISIBLE) topCol.height + m else 0
            val bottomH = bottom.height + m
            doubleArrayOf(m.toDouble(), topH.toDouble(), right.toDouble(), bottomH.toDouble())
        }
        if (!pad.contentEquals(ctl.uiPadding)) ctl.uiPadding = pad
        ctl.viewHeightPx = root.height
    }

    /**
     * Kunden-Leiste: hochkant rechts zwischen Banner oben und Leiste unten (gut 60 % breit),
     * quer rechts ueber die volle Hoehe (die Bedienung links und die Karte dazwischen bleiben frei).
     */
    private fun layoutDrawer() {
        val m = dp(10)
        val cfg = resources.configuration
        val w = if (landscape) dp(320) else dp((cfg.screenWidthDp * 0.62).roundToInt().coerceIn(250, 340))
        val top = if (landscape) m else (topCol.bottom - root.paddingTop + dp(6)).coerceAtLeast(m)
        val bottomM = if (landscape) m else (root.height - root.paddingBottom - bottom.top + dp(6)).coerceAtLeast(m)
        val lp = drawer.view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != w || lp.topMargin != top || lp.bottomMargin != bottomM || lp.rightMargin != m) {
            lp.width = w
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.END or Gravity.TOP
            lp.setMargins(0, top, m, bottomM)
            drawer.view.layoutParams = lp
        }
    }

    // =======================================================================================
    // Darstellung
    // =======================================================================================

    override fun onStateChanged() = renderAll(forceCamera = false)

    override fun onLocationChanged() = renderLive()

    override fun onAlert(alert: NavEngine.Alert) {
        if (alert.id == "better-route") renderAll(false)
        val digits = alert.callDigits
        if (digits != null && !engine.carActive && !isFinishing) confirmCall(alert.callName ?: "Kunde", alert.callDisplay ?: digits, digits)
    }

    private fun renderAll(forceCamera: Boolean) {
        val e = engine
        val mode = e.mode
        // Quer in der Vorschau ohne Suchleiste: Start und Ziel stehen in der Routenleiste links.
        searchBar.visibility = if (mode == NavEngine.Mode.NAVIGATING || (landscape && mode == NavEngine.Mode.PREVIEW)) View.GONE else View.VISIBLE
        searchText.text = e.destination?.name ?: "Wohin?"
        searchText.setTextColor(if (e.destination != null) Ui.TEXT else Ui.TEXT2)
        maneuver.view.visibility = if (mode == NavEngine.Mode.NAVIGATING) View.VISIBLE else View.GONE
        routePanel.view.visibility = if (mode == NavEngine.Mode.PREVIEW) View.VISIBLE else View.GONE
        navBar.view.visibility = if (mode == NavEngine.Mode.NAVIGATING) View.VISIBLE else View.GONE
        // In der Vorschau hat die Routenleiste eigene Knoepfe (Stau umfahren, Tanken) – rechts nur das Noetigste.
        btnJam.visibility = if (mode == NavEngine.Mode.NAVIGATING) View.VISIBLE else View.GONE
        btnMute.visibility = if (mode == NavEngine.Mode.PREVIEW) View.GONE else View.VISIBLE
        btnAvoid.visibility = if (mode == NavEngine.Mode.NAVIGATING) View.GONE else View.VISIBLE
        // Quer beim Fahren ist Tanken in der Leiste links – rechts Platz sparen.
        btnFuel.visibility = if (mode == NavEngine.Mode.PREVIEW || (landscape && mode == NavEngine.Mode.NAVIGATING)) View.GONE else View.VISIBLE
        btnReport.visibility = if (mode == NavEngine.Mode.PREVIEW || !prefs.camerasEnabled) View.GONE else View.VISIBLE
        // Termine: vor der Fahrt; Kunde: sobald ein Termin angefahren wird (auch nach der Ankunft)
        btnAppointments.visibility = if (mode == NavEngine.Mode.NAVIGATING) View.GONE else View.VISIBLE
        btnCustomer.visibility = if (e.activeAppointment != null) View.VISIBLE else View.GONE
        if (e.activeAppointment == null && drawer.isOpen) hideCustomer()
        if (appointmentsPanel.isShown) appointmentsPanel.render()
        if (mode == NavEngine.Mode.PREVIEW) routePanel.render(e)
        if (mode == NavEngine.Mode.NAVIGATING) navBar.render(e)
        val br = e.betterRoute
        if (br != null) {
            betterBanner.visibility = View.VISIBLE
            betterText.text = "Schnellere Route: −${Ui.minutes(e.betterRouteSavingSec)}" + (if (br.via.isNotBlank()) "\n${br.via}" else "")
        } else betterBanner.visibility = View.GONE
        val msg = e.message
        if (msg != null && mode != NavEngine.Mode.PREVIEW) {
            msgBanner.visibility = View.VISIBLE
            msgBanner.text = msg
            if (msg != lastMessage) handler.postDelayed({ if (engine.message == msg) engine.clearMessage() }, 9_000)
        } else msgBanner.visibility = View.GONE
        lastMessage = msg
        btnMute.setImageBitmap(Icons.glyph(if (prefs.voiceMuted) Icons.Glyph.VOLUME_OFF else Icons.Glyph.VOLUME_ON, dp(26), if (prefs.voiceMuted) Ui.RED else Ui.TEXT))
        btnLayers.setImageBitmap(Icons.glyph(Icons.Glyph.LAYERS, dp(26), if (prefs.mapMode == 1) Ui.BLUE else Ui.TEXT))
        if (mode == NavEngine.Mode.NAVIGATING) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (hazardList.isShown) hazardList.render()

        checkNight()
        val ctl = mapCtl
        if (ctl != null && ctl.style != null) {
            ctl.updateRoutes(force = true)
            ctl.updatePoints()
            ctl.updateBuilding()
            ctl.updateAvoid()
            ctl.updatePosition()
            // Auswahl einer anderen Route soll die Kamera nicht bewegen – nur neue Routen.
            val routesKey = System.identityHashCode(e.routes) + e.routes.size
            if (mode != lastMode || forceCamera || (mode == NavEngine.Mode.PREVIEW && routesKey != lastRoutesKey)) {
                when (mode) {
                    NavEngine.Mode.PREVIEW -> if (e.routes.isNotEmpty()) {
                        if (routesKey != lastRoutesKey || mode != lastMode) ctl.showRoutesOverview()
                    } else e.destination?.let { ctl.showPoint(it.pos, 16.5) }
                    NavEngine.Mode.NAVIGATING -> ctl.recenter()
                    NavEngine.Mode.IDLE -> if (lastMode == NavEngine.Mode.NAVIGATING) {
                        // nach der Ankunft: Ziel mit markiertem Haus zeigen
                        e.destination?.let { ctl.showPoint(it.pos, 18.0) } ?: ctl.recenter()
                    }
                }
                lastRoutesKey = routesKey
            }
        }
        lastMode = mode
        root.post { updateMapPadding() }
        renderLive()
    }

    private fun renderLive() {
        val e = engine
        speed.render(e)
        if (e.mode == NavEngine.Mode.NAVIGATING) {
            maneuver.render(e)
            navBar.render(e)
        }
        val road = if (e.mode == NavEngine.Mode.NAVIGATING) {
            val st = e.state
            val r = e.activeRoute
            if (st != null && r != null) r.maneuvers.lastOrNull { it.along <= st.along + 1 }?.street else null
        } else e.currentRoad
        roadLabel.visibility = if (road.isNullOrBlank() || e.speedKmh < 3) View.GONE else View.VISIBLE
        roadLabel.text = road ?: ""
        val nh = e.nearestHazard
        val cam = e.cameraAhead
        when {
            // "Baustelle in 800 m", in der Baustelle "Baustelle noch 1,2 km"
            nh != null -> hazardBanner.show(nh.first.hazard.type, Instructions.hazardBanner(nh.first, nh.second, e.state?.along ?: 0.0))
            cam != null -> hazardBanner.show(cam.first.type, Instructions.cameraBanner(cam.first, cam.second))
            else -> hazardBanner.hide()
        }
        val ctl = mapCtl ?: return
        if (ctl.style == null) return
        ctl.updatePosition()
        if (e.mode == NavEngine.Mode.NAVIGATING) ctl.updateRoutes()
        if (nh != null || cam != null || e.mode == NavEngine.Mode.NAVIGATING) ctl.updatePoints()
        // Beim Navigieren nach 12 s ohne Fingerbewegung automatisch wieder mitfahren.
        if (!ctl.following && e.mode == NavEngine.Mode.NAVIGATING && System.currentTimeMillis() - maxOf(lastGesture, ctl.lastManualMove) > 12_000 && panels.none { it.isShown }) {
            ctl.following = true
        }
        if (ctl.following && (e.mode == NavEngine.Mode.NAVIGATING || e.mode == NavEngine.Mode.IDLE)) ctl.follow()
        updateRecenter()
        if (System.currentTimeMillis() - lastNightCheck > 60_000) checkNight()
    }

    /** Tag/Nacht gewechselt (Einstellung, Knopf im Auto, Sonnenauf-/-untergang): Kartenstil nachziehen. */
    private fun checkNight() {
        val now = System.currentTimeMillis()
        val night = engine.isNight()
        if (night != lastNight) {
            lastNight = night
            mapCtl?.applyStyle()
        }
        lastNightCheck = now
    }

    private fun updateRecenter() {
        val ctl = mapCtl
        btnRecenter.visibility = if (ctl == null || !ctl.following || engine.mode == NavEngine.Mode.PREVIEW) View.VISIBLE else View.GONE
    }

    // =======================================================================================
    // Aktionen
    // =======================================================================================

    private fun openSearch(voice: Boolean = false) {
        closePanels()
        search.open(SearchPanel.Target.DESTINATION, voice)
    }

    private fun openSteps() {
        val r = (if (engine.mode == NavEngine.Mode.NAVIGATING) engine.activeRoute else engine.selectedRoute) ?: run {
            toast("Noch keine Route")
            return
        }
        closePanels()
        steps.showRoute(r)
    }

    /** Google-Spracherkennung (Deutsch); das Ergebnis kommt in onActivityResult an. */
    override fun startVoice(prompt: String, onResult: (String) -> Unit) {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "de-DE")
            putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        voiceCallback = onResult
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, reqVoice)
        } catch (e: ActivityNotFoundException) {
            voiceCallback = null
            toast("Spracheingabe nicht verfügbar – bitte die Google-App bzw. „Spracherkennung von Google“ aktivieren")
        } catch (e: Exception) {
            voiceCallback = null
            toast("Spracheingabe nicht möglich: ${e.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqVoice) return
        val cb = voiceCallback
        voiceCallback = null
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (resultCode == RESULT_OK && !text.isNullOrBlank()) cb?.invoke(text)
    }

    private fun openFuel() {
        closePanels()
        fuel.show()
    }

    private fun openAppointments() {
        closePanels()
        hideCustomer()
        appointmentsPanel.show()
    }

    private fun toggleCustomer() {
        if (drawer.isOpen) hideCustomer() else showCustomer()
    }

    private fun showCustomer() {
        val a = engine.activeAppointment ?: return
        closePanels()
        layoutDrawer()
        drawer.show(a, engine.ownNumbers)
        updateMapPadding()
        refitPreview()
    }

    private fun hideCustomer() {
        if (!drawer.isOpen) return
        drawer.hide()
        updateMapPadding()
        refitPreview()
    }

    /** In der Vorschau die Routen neu einpassen, wenn sich der freie Kartenbereich aendert. */
    private fun refitPreview() {
        if (engine.mode == NavEngine.Mode.PREVIEW && engine.routes.isNotEmpty()) handler.postDelayed({ mapCtl?.showRoutesOverview() }, 250)
    }

    /** Nummer angetippt: "Anrufen?" – bei Ja wird direkt gewaehlt. */
    private fun confirmCall(name: String, display: String, digits: String) {
        AlertDialog.Builder(this).setTitle("Anrufen?")
            .setMessage("$name\n$display")
            .setPositiveButton("Ja") { _, _ -> placeCall(digits) }
            .setNegativeButton("Nein", null)
            .show()
    }

    private fun placeCall(digits: String) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCall = digits
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), reqCall)
            return
        }
        if (de.sascha.kssmaps.data.CallHelper.call(this, digits)) return
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", digits, null)))
        } catch (e: Exception) {
            dial(digits)
        }
    }

    /** Ohne Anruf-Berechtigung: Telefon-App mit der Nummer oeffnen (dort nur noch auf Anrufen tippen). */
    private fun dial(digits: String) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", digits, null)))
        } catch (e: Exception) {
            toast("Keine Telefon-App gefunden")
        }
    }

    override fun openSettings() {
        closePanels()
        settings.show()
    }

    override fun closePanels() {
        for (p in panels) if (p.isShown) p.hide()
    }

    override fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    private fun startNavigation() {
        if (!engine.hasLocationPermission()) {
            requestPermissionsIfNeeded()
            toast("Bitte Standort erlauben")
            return
        }
        engine.startNavigation()
        mapCtl?.recenter()
    }

    private fun confirmStop() {
        AlertDialog.Builder(this).setTitle("Navigation beenden?")
            .setPositiveButton("Beenden") { _, _ -> engine.stopNavigation() }
            .setNegativeButton("Weiterfahren", null).show()
    }

    private fun toggleSatellite() {
        prefs.mapMode = if (prefs.mapMode == 1) 0 else 1
        mapCtl?.applyStyle() // wechselt nachts auf den hellen Stil, sonst nur das Luftbild
        renderAll(false)
        toast(if (prefs.mapMode == 1) "Luftbild (Bayern: amtliche Luftbilder)" else "Karte")
    }

    private fun onSettingsChanged() {
        val ctl = mapCtl ?: return
        ctl.applyStyle()
        ctl.updateTrafficOverlay()
        renderAll(false)
    }

    /** Mobilen Blitzer / Abstandsmessung an der aktuellen Position merken (keine freie Datenquelle dafuer). */
    private fun reportDialog() {
        if (engine.position == null) {
            toast("Noch kein GPS-Standort")
            return
        }
        val items = arrayOf(
            "📷 Mobiler Blitzer hier (4 Stunden)",
            "📏 Abstandsmessung hier (4 Stunden)",
            "📷 Fester Messplatz hier (dauerhaft)",
            "📏 Abstands-Messplatz hier (dauerhaft)",
        )
        AlertDialog.Builder(this).setTitle("Melden – an deiner Position").setItems(items) { _, w ->
            val type = if (w == 1 || w == 3) HazardType.DISTANCE_CHECK else HazardType.SPEED_CAMERA
            if (engine.reportHere(type, permanent = w >= 2)) toast("Gespeichert – wird auf deinen Routen gewarnt")
        }.setNegativeButton("Abbrechen", null).show()
    }

    private fun onMapLongPress(p: LatLon) {
        val items = arrayOf("Hierhin navigieren", "Abschnitt hier meiden (Straße sperren)", "Abbrechen")
        AlertDialog.Builder(this).setTitle(p.fmt()).setItems(items) { _, which ->
            when (which) {
                0 -> engine.navigateTo(p, null)
                1 -> engine.scope.launch {
                    toast("Straße wird bestimmt …")
                    try {
                        val a = engine.addAvoidSectionAt(p)
                        if (a == null) toast("Keine Straße gefunden – näher an die Straße tippen")
                        else {
                            toast("„${a.name}“ wird ab jetzt gemieden (${a.points.size} Fahrbahn(en))")
                            mapCtl?.updateAvoid()
                            mapCtl?.updatePoints()
                        }
                    } catch (e: Exception) {
                        toast("Nicht möglich: ${e.message}")
                    }
                }
            }
        }.show()
    }

    // =======================================================================================
    // Berechtigungen, Intents, Ersteinrichtung, Update
    // =======================================================================================

    private fun requestPermissionsIfNeeded() {
        val need = ArrayList<String>()
        if (!engine.hasLocationPermission()) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION)
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (engine.appointmentSource.configured && !de.sascha.kssmaps.data.CallHelper.hasPermission(this)) {
            need.add(Manifest.permission.CALL_PHONE)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 42)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == reqCall) {
            val n = pendingCall ?: return
            pendingCall = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) placeCall(n) else dial(n)
            return
        }
        engine.startLocation()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        // Nur in Test-Builds (debuggable): Steuerung fuer die automatische Testfahrt in der CI.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            if (i.getBooleanExtra("test_start_navigation", false)) {
                engine.scope.launch {
                    repeat(90) {
                        if (engine.mode == NavEngine.Mode.PREVIEW && engine.routes.isNotEmpty() && !engine.loading) {
                            startNavigation()
                            return@launch
                        }
                        kotlinx.coroutines.delay(1000)
                    }
                }
            }
            if (i.getBooleanExtra("test_toggle_satellite", false)) toggleSatellite()
            if (i.getBooleanExtra("test_clear", false)) engine.clearDestination()
            i.getStringExtra("test_avoid")?.let { s ->
                Geocoding.parseCoordinates(s)?.let { p ->
                    engine.scope.launch {
                        val a = engine.addAvoidSectionAt(p)
                        android.util.Log.i("KSSMaps", "TEST_AVOID ${a?.name} punkte=${a?.points?.size}")
                        mapCtl?.updateAvoid()
                        mapCtl?.updatePoints()
                    }
                }
            }
            i.getStringExtra("test_report")?.let { t ->
                engine.reportHere(if (t == "distance") HazardType.DISTANCE_CHECK else HazardType.SPEED_CAMERA, permanent = false)
            }
            if (i.getBooleanExtra("test_open_fuel", false)) openFuel()
            // Startpunkt setzen ("Breite,Laenge") bzw. zuruecksetzen ("here")
            i.getStringExtra("test_origin")?.let { o ->
                android.util.Log.i("KSSMaps", "TEST_ORIGIN $o")
                if (o == "here") engine.setOrigin(null)
                else Geocoding.parseCoordinates(o)?.let { p -> engine.setOrigin(de.sascha.kssmaps.core.Place("Teststart", o, p.lat, p.lon)) }
            }
            if (i.getBooleanExtra("test_open_steps", false)) openSteps()
            if (i.getBooleanExtra("test_open_origin_search", false)) search.open(SearchPanel.Target.ORIGIN, voice = false)
            // Wie ein erkannter Sprachbefehl (ohne Mikrofon): Suche oeffnen und Text verarbeiten
            i.getStringExtra("test_voice")?.let { t ->
                android.util.Log.i("KSSMaps", "TEST_VOICE $t")
                closePanels()
                search.open(SearchPanel.Target.DESTINATION, voice = false)
                search.onVoice(t)
            }
            if (i.getBooleanExtra("test_close_panels", false)) closePanels()
            // Termine: erfundene Beispieltermine (nie echte Kunden), Liste, Termin anfahren, Kunden-Leiste
            if (i.getBooleanExtra("test_demo_appointments", false)) {
                engine.setAppointmentsForTest(de.sascha.kssmaps.data.DemoAppointments.list())
                android.util.Log.i("KSSMaps", "TEST_APPOINTMENTS ${engine.appointments.size}")
            }
            if (i.getBooleanExtra("test_open_appointments", false)) openAppointments()
            if (i.hasExtra("test_pick_appointment")) {
                engine.appointments.getOrNull(i.getIntExtra("test_pick_appointment", 0))?.let {
                    closePanels()
                    engine.startAppointment(it)
                }
            }
            if (i.getBooleanExtra("test_open_customer", false)) showCustomer()
            if (i.getBooleanExtra("test_close_customer", false)) hideCustomer()
            if (i.getBooleanExtra("test_open_settings", false)) openSettings()
            // Android-Auto-Karte auf unsichtbarer Flaeche zeichnen und als Bild sichern (Testfahrt)
            // Anruf-Erinnerung wie kurz vor der Ankunft ("Kunde anrufen?" – in der Testfahrt nur "Nein")
            if (i.getBooleanExtra("test_call_reminder", false)) engine.testCallReminder()
            try {
                if (i.getBooleanExtra("test_car_start", false)) de.sascha.kssmaps.car.CarSurfaceProbe.start(this)
                i.getStringExtra("test_car_capture")?.let { de.sascha.kssmaps.car.CarSurfaceProbe.capture(this, it) }
                // eigenen Knopf auf der Android-Auto-Karte antippen (Treffer steht als TEST_CAR_BUTTON im Protokoll)
                i.getStringExtra("test_car_click")?.let { de.sascha.kssmaps.car.CarSurfaceProbe.click(it) }
            } catch (e: Exception) {
                android.util.Log.e("KSSMaps", "TEST_CAR_SURFACE fehlgeschlagen", e)
            }
        }
        val data: Uri = i.data ?: return
        when (data.scheme) {
            "geo" -> {
                val (p, q) = Geocoding.parseGeoUri(data.toString())
                engine.navigateTo(p, q)
            }
            "google.navigation" -> {
                val ssp = data.schemeSpecificPart ?: return
                val q = ssp.split('&').firstOrNull { it.startsWith("q=") }?.removePrefix("q=") ?: ssp
                val c = Geocoding.parseCoordinates(q)
                engine.navigateTo(c, if (c == null) q else null)
            }
        }
    }

    private fun firstRunDialogs() {
        // Keine Blitzerwarnungen mehr in KSS-Maps (Blitzer.de Pro laeuft parallel) – kein Blitzer-Hinweis.
        setupHint()
    }

    private fun setupHint() {
        if (prefs.setupShown) return
        // Schluessel eingebaut (bzw. schon eingetragen): kein Einrichtungs-Hinweis noetig
        if (prefs.tomtomKey.isNotBlank() && prefs.tankerkoenigKey.isNotBlank()) {
            prefs.setupShown = true
            return
        }
        prefs.setupShown = true
        AlertDialog.Builder(this).setTitle("Fast fertig")
            .setMessage(
                "Zwei kostenlose Schlüssel machen die App komplett:\n\n" +
                    "• TomTom (developer.tomtom.com): Live-Stau auf ALLEN Straßen in der Routenberechnung. " +
                    "Ohne Schlüssel kommen Stau, Baustellen und Sperrungen von der Autobahn GmbH (nur Autobahnen).\n\n" +
                    "• Tankerkönig (onboarding.tankerkoenig.de): aktuelle Spritpreise für Diesel und Super. " +
                    "Ohne Schlüssel zeigt die App die Tankstellen auf dem Weg ohne Preise.\n\n" +
                    "Beide in den Einstellungen eintragen."
            )
            .setPositiveButton("Einstellungen") { _, _ -> openSettings() }
            .setNegativeButton("Später", null).show()
    }

    private fun checkUpdate(manual: Boolean) {
        if (!manual && System.currentTimeMillis() - prefs.lastUpdateCheck < 6 * 3600_000L) return
        prefs.lastUpdateCheck = System.currentTimeMillis()
        engine.scope.launch {
            val info = services.checkUpdate()
            if (info == null) {
                if (manual) toast("Update-Prüfung nicht möglich (kein Internet oder GitHub nicht erreichbar)")
                return@launch
            }
            if (de.sascha.kssmaps.core.UpdateCheck.compare(info.version, NaviApp.versionName) > 0) {
                // Fuer Android Auto muss die neue Version ueber KingInstaller installiert werden; mit dem
                // temporaeren Test-Schluessel (ohne eigene Signier-Secrets) vorher deinstallieren.
                val steps = buildString {
                    append("\n\nSo installierst du es:\n")
                    append("1. „Herunterladen“ tippen (lädt kss-maps.apk).\n")
                    if (BuildConfig.TEMP_SIGNING_KEY) append("2. KSS-Maps deinstallieren (diese Version hat einen anderen Signierschlüssel).\n3. ") else append("2. ")
                    append("Die heruntergeladene kss-maps.apk mit KingInstaller installieren – nur so erscheint KSS-Maps in Android Auto.")
                }
                AlertDialog.Builder(this@MainActivity).setTitle("Update verfügbar: ${info.version}")
                    .setMessage("Du hast ${NaviApp.versionName}.\n\n" + info.notes.take(6).joinToString("\n") { "• $it" } + steps)
                    .setPositiveButton("Herunterladen") { _, _ ->
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl)))
                        } catch (e: Exception) {
                            toast("Kein Browser gefunden")
                        }
                    }
                    .setNegativeButton("Später", null).show()
            } else if (manual) toast("Du hast die neueste Version (${NaviApp.versionName}).")
        }
    }

    // =======================================================================================
    // Lebenszyklus
    // =======================================================================================

    override fun onStart() {
        super.onStart()
        mapView.onStart()
        mapCtl?.active = true
        engine.acquireLocation()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        mapCtl?.applyStyle()
        renderAll(false)
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        engine.releaseLocation()
        mapCtl?.active = false
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        engine.removeListener(this)
        handler.removeCallbacksAndMessages(null)
        mapCtl?.dispose()
        mapView.onDestroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val open = panels.firstOrNull { it.isShown }
        when {
            open != null -> open.hide()
            drawer.isOpen -> hideCustomer()
            engine.mode == NavEngine.Mode.PREVIEW -> engine.cancelPreview()
            engine.mode == NavEngine.Mode.NAVIGATING -> moveTaskToBack(true)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }
}
