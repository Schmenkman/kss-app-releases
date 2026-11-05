package de.sascha.kssmaps.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Alert
import androidx.car.app.model.AlertCallback
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarText
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import de.sascha.kssmaps.NaviApp
import de.sascha.kssmaps.core.Appointment
import de.sascha.kssmaps.core.Appointments
import de.sascha.kssmaps.core.FuelType
import de.sascha.kssmaps.core.Geo
import de.sascha.kssmaps.core.HazardType
import de.sascha.kssmaps.core.Instructions
import de.sascha.kssmaps.core.ManeuverKind
import de.sascha.kssmaps.core.Place
import de.sascha.kssmaps.core.RankedStation
import de.sascha.kssmaps.core.Route
import de.sascha.kssmaps.core.Tankerkoenig
import de.sascha.kssmaps.core.UtaStatus
import de.sascha.kssmaps.nav.NavEngine
import de.sascha.kssmaps.ui.Icons
import de.sascha.kssmaps.ui.Ui
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val engine: NavEngine get() = NaviApp.instance.engine

/** Basis: meldet sich bei der Engine an, solange der Bildschirm sichtbar ist, und zeichnet neu. */
abstract class EngineScreen(carContext: CarContext) : Screen(carContext), NavEngine.Listener {
    private var lastInvalidate = 0L

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                engine.addListener(this@EngineScreen)
            }

            override fun onStop(owner: LifecycleOwner) {
                engine.removeListener(this@EngineScreen)
            }
        })
    }

    override fun onStateChanged() = invalidate()

    /** Waehrend der Fahrt hoechstens ~1x pro Sekunde neu zeichnen. */
    protected fun invalidateThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastInvalidate >= 900) {
            lastInvalidate = now
            invalidate()
        }
    }
}

// =============================================================================================
// Hauptbildschirm: freie Fahrt bzw. Navigation (Karte zeichnet CarMapRenderer)
// =============================================================================================

class CarMainScreen(carContext: CarContext, private val session: NaviSession) : EngineScreen(carContext) {
    private var lastMode: NavEngine.Mode? = null

    override fun onStateChanged() {
        val e = engine
        // Vom Handy gestartete Vorschau auch im Auto zeigen
        if (e.mode == NavEngine.Mode.PREVIEW && lastMode != NavEngine.Mode.PREVIEW && screenManager.top === this) {
            screenManager.push(CarRoutePreviewScreen(carContext))
        }
        lastMode = e.mode
        invalidate()
    }

    override fun onLocationChanged() {
        if (engine.mode == NavEngine.Mode.NAVIGATING) invalidateThrottled()
    }

    override fun onAlert(alert: NavEngine.Alert) {
        if (screenManager.top !== this) return
        // Baustellen, Gefahren, Blitzer: nur Banner auf der Karte + Ansage – die Hinweiskarte von
        // Android Auto (mit Zeitbalken) blockiert sonst das Antippen der Karte. Anruf-Erinnerungen
        // zeigt die Sitzung selbst.
        if (alert.action == null || alert.callDigits != null) return
        showCarAlert(carContext, alert)
    }

    override fun onGetTemplate(): Template {
        val e = engine
        lastMode = e.mode
        val b = NavigationTemplate.Builder()
        val strip = ActionStrip.Builder()
        val muted = e.prefs.voiceMuted
        val muteAction = CarUtil.action(if (muted) Icons.Glyph.VOLUME_OFF else Icons.Glyph.VOLUME_ON, tint = if (muted) CarColor.RED else null) { e.toggleMuted() }
        val more = CarUtil.action(Icons.Glyph.MORE) { screenManager.push(CarMoreScreen(carContext)) }
        val fuel = CarUtil.action(Icons.Glyph.FUEL) { screenManager.push(CarFuelScreen(carContext)) }
        // Kunde anrufen, solange ein Termin angefahren wird (auch nach der Ankunft); Ton und
        // Tanken gibt es dann unter "Mehr", die letzten Ziele immer dort und in der Suche.
        val customer = if (e.activeAppointment != null) CarUtil.action(Icons.Glyph.PHONE, tint = CarColor.GREEN) { screenManager.push(CarCustomerScreen(carContext)) } else null
        if (session.customButtons) {
            // Alle wichtigen Knoepfe liegen immer sichtbar auf der Karte (rechts, Zoom/Beenden unten);
            // hier nur Suche und "Mehr" (und "Ende" als Rueckfall)
            if (e.mode == NavEngine.Mode.NAVIGATING) {
                strip.addAction(more)
                strip.addAction(CarUtil.textAction("Ende") { e.stopNavigation() })
            } else {
                strip.addAction(CarUtil.action(Icons.Glyph.SEARCH) { screenManager.push(CarSearchScreen(carContext)) })
                strip.addAction(more)
            }
        } else if (e.mode == NavEngine.Mode.NAVIGATING) {
            strip.addAction(fuel)
            strip.addAction(customer ?: muteAction)
            strip.addAction(more)
            strip.addAction(CarUtil.textAction("Ende") { e.stopNavigation() })
        } else {
            strip.addAction(CarUtil.action(Icons.Glyph.SEARCH) { screenManager.push(CarSearchScreen(carContext)) })
            strip.addAction(CarUtil.action(Icons.Glyph.CALENDAR) { screenManager.push(CarAppointmentsScreen(carContext)) })
            strip.addAction(customer ?: fuel)
            strip.addAction(more)
        }
        b.setActionStrip(strip.build())
        val mapStrip = ActionStrip.Builder().addAction(Action.PAN)
        if (!session.customButtons) {
            mapStrip.addAction(CarUtil.action(Icons.Glyph.PLUS) { session.renderer.zoom(1.0) })
                .addAction(CarUtil.action(Icons.Glyph.MINUS) { session.renderer.zoom(-1.0) })
                .addAction(CarUtil.action(Icons.Glyph.RECENTER) { session.renderer.recenter() })
        }
        b.setMapActionStrip(mapStrip.build())
        b.setPanModeListener { inPan -> if (!inPan) session.renderer.recenter() }

        if (e.mode == NavEngine.Mode.NAVIGATING) {
            val r = e.activeRoute
            val st = e.state
            when {
                e.arrived -> b.setNavigationInfo(
                    MessageInfo.Builder("Ziel erreicht").setText(e.destination?.label() ?: "")
                        .setImage(CarUtil.icon(Icons.maneuver(ManeuverKind.ARRIVE, 128))).build()
                )
                e.rerouting || r == null || st == null -> b.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
                else -> {
                    val m = r.maneuvers.getOrNull(st.nextIdx)
                    if (m != null) {
                        val ri = RoutingInfo.Builder().setCurrentStep(CarUtil.step(m, e.fuelStop?.displayName()), CarUtil.distance(st.distToNext))
                        val after = r.maneuvers.getOrNull(st.afterIdx)
                        if (after != null) ri.setNextStep(CarUtil.step(after, e.fuelStop?.displayName()))
                        b.setNavigationInfo(ri.build())
                    } else {
                        b.setNavigationInfo(MessageInfo.Builder("Der Straße folgen").setText(Instructions.formatDistance(st.remainingM) + " bis zum Ziel").build())
                    }
                    b.setDestinationTravelEstimate(CarUtil.travelEstimate(st.remainingM, st.remainingSec, r.trafficDelaySec))
                }
            }
        } else if (e.speedLimit == null && e.position == null) {
            b.setNavigationInfo(MessageInfo.Builder("Warte auf GPS …").build())
        }
        return b.build()
    }
}

/** Meldungen als Android-Auto-Hinweis (ab Car-API 5), sonst als kurzer Text. */
fun showCarAlert(ctx: CarContext, a: NavEngine.Alert) {
    try {
        if (ctx.carAppApiLevel >= 5) {
            val icon = if (a.type != null) CarUtil.icon(Icons.hazard(a.type, 128)) else CarUtil.glyph(Icons.Glyph.NAVIGATE)
            val b = Alert.Builder(a.id.hashCode(), CarText.create(a.title), if (a.action != null) 15_000L else 8_000L)
                .setIcon(icon)
            if (a.text.isNotBlank()) b.setSubtitle(CarText.create(a.text))
            val act = a.action
            if (act != null && a.actionLabel != null) {
                b.addAction(Action.Builder().setTitle(a.actionLabel).setOnClickListener {
                    act()
                    ctx.getCarService(AppManager::class.java).dismissAlert(a.id.hashCode())
                }.build())
                b.addAction(Action.Builder().setTitle("Nein").setOnClickListener {
                    engine.dismissBetterRoute()
                    ctx.getCarService(AppManager::class.java).dismissAlert(a.id.hashCode())
                }.build())
            }
            b.setCallback(object : AlertCallback {
                override fun onCancel(reason: Int) {}
                override fun onDismiss() {}
            })
            ctx.getCarService(AppManager::class.java).showAlert(b.build())
        } else {
            CarToast.makeText(ctx, a.title, CarToast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        // Host kann Hinweise ablehnen (z. B. waehrend einer anderen Anzeige) – Ansage kam ja schon per Ton
    }
}

// =============================================================================================
// Suche (Tastatur nur im Stand; Spracheingabe ueber das Mikrofon der Suchleiste)
// =============================================================================================

class CarSearchScreen(carContext: CarContext) : EngineScreen(carContext) {
    private var results: List<Place> = engine.prefs.history
    private var loading = false
    private var job: Job? = null
    private var showingHistory = true

    private fun search(q: String, debounceMs: Long) {
        job?.cancel()
        if (q.trim().length < 3) {
            results = engine.prefs.history
            showingHistory = true
            loading = false
            invalidate()
            return
        }
        job = engine.scope.launch {
            delay(debounceMs)
            loading = true
            invalidate()
            try {
                results = NaviApp.instance.services.search(q.trim(), engine.position ?: engine.prefs.lastPosition)
                showingHistory = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                results = emptyList()
            } finally {
                loading = false
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val b = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = search(searchText, 700)
            override fun onSearchSubmitted(searchText: String) = search(searchText, 0)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Adresse, Ort oder Firma")
            .setShowKeyboardByDefault(false)
        if (loading) {
            b.setLoading(true)
        } else {
            val il = ItemList.Builder().setNoItemsMessage(if (showingHistory) "Noch keine Ziele" else "Nichts gefunden")
            for (p in results.take(CarUtil.listLimit(carContext, 20))) {
                val row = Row.Builder().setTitle(p.name).setOnClickListener { choose(p) }
                if (p.address.isNotBlank()) row.addText(p.address)
                il.addItem(row.build())
            }
            b.setItemList(il.build())
        }
        return b.build()
    }

    private fun choose(p: Place) {
        engine.setDestination(p)
        screenManager.push(CarRoutePreviewScreen(carContext))
    }
}

// =============================================================================================
// Letzte 20 Ziele
// =============================================================================================

class CarHistoryScreen(carContext: CarContext) : EngineScreen(carContext) {
    override fun onGetTemplate(): Template {
        val h = engine.prefs.history
        // Mit Standort: Kartenliste mit Entfernung je Ziel (Pflicht dieser Vorlage), sonst einfache Liste.
        val here = engine.position ?: engine.prefs.lastPosition
        val il = ItemList.Builder().setNoItemsMessage("Noch keine Ziele – zuerst über die Suche eins anfahren")
        for (p in h.take(CarUtil.listLimit(carContext, 20))) {
            val row = Row.Builder().setTitle(p.name).setOnClickListener {
                engine.setDestination(p)
                screenManager.push(CarRoutePreviewScreen(carContext))
            }
            if (here != null) row.addText(CarUtil.distanceText(Geo.distance(here, p.pos), p.address))
            else if (p.address.isNotBlank()) row.addText(p.address)
            il.addItem(row.build())
        }
        if (here == null) {
            return ListTemplate.Builder()
                .setTitle("Letzte Ziele")
                .setHeaderAction(Action.BACK)
                .setSingleList(il.build())
                .build()
        }
        return PlaceListNavigationTemplate.Builder()
            .setTitle("Letzte Ziele")
            .setHeaderAction(Action.BACK)
            .setItemList(il.build())
            .build()
    }
}

// =============================================================================================
// Routenauswahl (Alternativen, Stau, Blitzer, Baustellen)
// =============================================================================================

class CarRoutePreviewScreen(carContext: CarContext) : EngineScreen(carContext) {

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                // Zurueck ohne Start = Vorschau verwerfen
                if (engine.mode == NavEngine.Mode.PREVIEW) engine.cancelPreview()
            }
        })
    }

    override fun onStateChanged() {
        if (engine.mode == NavEngine.Mode.NAVIGATING) {
            screenManager.popToRoot()
            return
        }
        if (engine.mode == NavEngine.Mode.IDLE) {
            finish()
            return
        }
        invalidate()
    }

    private fun summary(r: Route): String {
        val parts = ArrayList<String>()
        parts.add(Instructions.formatDistance(r.distanceM))
        val jams = r.count(HazardType.JAM) + r.count(HazardType.SLOW_TRAFFIC)
        val delay = (r.trafficDelaySec / 60).roundToInt()
        parts.add(if (jams > 0) "Stau ${jams}×" + (if (delay > 0) " +$delay Min." else "") else "kein Stau")
        val cams = r.count(HazardType.SPEED_CAMERA)
        if (cams > 0 && engine.prefs.camerasEnabled) parts.add("Blitzer $cams")
        val works = r.count(HazardType.ROADWORKS)
        if (works > 0) parts.add("Baustellen $works")
        return parts.joinToString(" · ")
    }

    override fun onGetTemplate(): Template {
        val e = engine
        if (!e.loading && e.routes.isEmpty()) {
            return MessageTemplate.Builder(e.message ?: "Keine Route gefunden")
                .setTitle(e.destination?.name ?: "Route")
                .setHeaderAction(Action.BACK)
                .addAction(CarUtil.textAction("Erneut") { e.computePreview() })
                .build()
        }
        val b = RoutePreviewNavigationTemplate.Builder()
            .setTitle(e.destination?.name ?: "Route")
            .setHeaderAction(Action.BACK)
        if (e.loading) {
            b.setLoading(true)
        } else {
            val il = ItemList.Builder()
            for (r in e.routes) {
                // Dauer als DurationSpan: Pflicht je Zeile in der Routenauswahl von Android Auto
                il.addItem(Row.Builder().setTitle(CarUtil.durationText(r.durationSec, r.label)).addText(summary(r)).build())
            }
            il.setOnSelectedListener { idx -> e.selectRoute(idx) }
            il.setSelectedIndex(e.selectedIndex.coerceIn(0, e.routes.size - 1))
            b.setItemList(il.build())
            b.setNavigateAction(
                Action.Builder().setTitle("Los").setIcon(CarUtil.glyph(Icons.Glyph.NAVIGATE)).setOnClickListener {
                    e.startNavigation()
                    screenManager.popToRoot()
                }.build()
            )
        }
        b.setActionStrip(
            ActionStrip.Builder()
                .addAction(CarUtil.textAction("Stau umfahren") { e.avoidJamsNow() })
                .addAction(CarUtil.action(Icons.Glyph.FUEL) { screenManager.push(CarFuelScreen(carContext)) })
                .build()
        )
        return b.build()
    }
}

// =============================================================================================
// Tankstellen: auf dem Weg zuerst, dann Preis; UTA
// =============================================================================================

class CarFuelScreen(carContext: CarContext) : EngineScreen(carContext) {
    internal var items: List<RankedStation>? = null
    internal var error: String? = null
    private var job: Job? = null

    init {
        load()
    }

    override fun onStateChanged() {
        // Liste nicht bei jeder Positionsaenderung neu laden
    }

    private fun load() {
        items = null
        error = null
        job?.cancel()
        job = engine.scope.launch {
            try {
                items = engine.searchFuel()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Tankstellen nicht verfügbar"
            }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val e = engine
        val fuel = e.prefs.fuelType
        error?.let {
            return MessageTemplate.Builder(it).setTitle("Tankstellen").setHeaderAction(Action.BACK)
                .addAction(CarUtil.textAction("Erneut") { load(); invalidate() }).build()
        }
        val b = PlaceListNavigationTemplate.Builder()
            .setTitle("Tanken · ${fuel.label}" + (if (e.prefs.utaOnly) " · UTA" else "") + (if (e.services.fuelWithoutPrices && items != null) " · ohne Preise" else ""))
            .setHeaderAction(Action.BACK)
        val list = items
        if (list == null) {
            b.setLoading(true)
        } else {
            val il = ItemList.Builder().setNoItemsMessage(if (e.prefs.utaOnly) "Keine geöffnete UTA-Tankstelle gefunden" else "Keine geöffnete Tankstelle gefunden")
            for ((i, rs) in list.take(CarUtil.listLimit(carContext, 12)).withIndex()) {
                val s = rs.station
                val where = when {
                    rs.along == null -> ""
                    rs.onTheWay -> "auf dem Weg · "
                    else -> "Umweg " + Instructions.formatDistance(rs.detourM) + " · "
                }
                val uta = when (s.uta) {
                    UtaStatus.YES -> "UTA ✓"
                    UtaStatus.NO -> "kein UTA"
                    UtaStatus.UNKNOWN -> "UTA ?"
                }
                val dist = if (rs.along != null && rs.onTheWay) rs.distToStationM else rs.distFromHereM
                il.addItem(
                    Row.Builder()
                        .setTitle(s.price(fuel)?.let { "${i + 1}. ${Tankerkoenig.formatPrice(it)} · ${s.displayName()}" } ?: "${i + 1}. ${s.displayName()}")
                        // Entfernung als DistanceSpan: Pflicht je Zeile in Orts-Listen von Android Auto
                        .addText(CarUtil.distanceText(dist, "$where$uta"))
                        .setOnClickListener { choose(rs) }
                        .build()
                )
            }
            b.setItemList(il.build())
        }
        val next = FuelType.values()[(fuel.ordinal + 1) % FuelType.values().size]
        b.setActionStrip(
            ActionStrip.Builder()
                .addAction(CarUtil.textAction(next.label) {
                    e.prefs.fuelType = next
                    load()
                    invalidate()
                })
                .addAction(CarUtil.textAction(if (e.prefs.utaOnly) "Alle" else "Nur UTA") {
                    e.prefs.utaOnly = !e.prefs.utaOnly
                    load()
                    invalidate()
                })
                .build()
        )
        return b.build()
    }

    private fun choose(rs: RankedStation) {
        val e = engine
        val hadDestination = e.destination != null
        e.addFuelStop(rs.station)
        CarToast.makeText(carContext, "⛽ ${rs.station.displayName()} – danach weiter zum Ziel", CarToast.LENGTH_LONG).show()
        when {
            e.mode == NavEngine.Mode.NAVIGATING -> screenManager.popToRoot()
            hadDestination -> finish() // zurueck zur Routenvorschau, die neu rechnet
            else -> {
                finish()
                screenManager.push(CarRoutePreviewScreen(carContext))
            }
        }
    }
}

// =============================================================================================
// Hinweis / Berechtigung
// =============================================================================================

class CarPermissionScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder("KSS-Maps braucht deinen Standort. Bitte am Handy erlauben – oder hier auf \"Erlauben\" tippen.")
            .setTitle("Standort")
            .setHeaderAction(Action.APP_ICON)
            .addAction(CarUtil.textAction("Erlauben") {
                carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { granted, _ ->
                    if (granted.isNotEmpty()) {
                        engine.startLocation()
                        finish()
                    }
                }
            })
            .build()
}


// =============================================================================================
// "Mehr": Melden (mobiler Blitzer / Abstandsmessung), Ton, Stau umfahren, Verkehr pruefen
// =============================================================================================

class CarMoreScreen(carContext: CarContext) : Screen(carContext) {
    private fun toast(s: String) = CarToast.makeText(carContext, s, CarToast.LENGTH_LONG).show()

    override fun onGetTemplate(): Template {
        val e = engine
        val il = ItemList.Builder()
        fun row(title: String, text: String?, click: () -> Unit) {
            val r = Row.Builder().setTitle(title).setOnClickListener {
                click()
                finish()
            }
            if (!text.isNullOrBlank()) r.addText(text)
            il.addItem(r.build())
        }
        fun openScreen(title: String, text: String?, screen: () -> Screen) {
            val r = Row.Builder().setTitle(title).setOnClickListener {
                screenManager.pop()
                screenManager.push(screen())
            }
            if (!text.isNullOrBlank()) r.addText(text)
            il.addItem(r.build())
        }
        if (e.mode != NavEngine.Mode.NAVIGATING) openScreen("Letzte Ziele", null) { CarHistoryScreen(carContext) }
        if (e.mode != NavEngine.Mode.NAVIGATING && e.activeAppointment != null) openScreen("Tankstellen", "auf dem Weg und in der Nähe") { CarFuelScreen(carContext) }
        if (e.prefs.camerasEnabled) {
            row("Mobiler Blitzer hier", "merken und auf der Route warnen (4 Stunden)") {
                toast(if (e.reportHere(HazardType.SPEED_CAMERA, false)) "Blitzer gespeichert" else "Kein GPS-Standort")
            }
            row("Abstandsmessung hier", "merken und auf der Route warnen (4 Stunden)") {
                toast(if (e.reportHere(HazardType.DISTANCE_CHECK, false)) "Abstandsmessung gespeichert" else "Kein GPS-Standort")
            }
        }
        if (e.mode == NavEngine.Mode.NAVIGATING) {
            row("Stau umfahren", "alle Staus und Sperrungen auf der Route meiden") {
                e.avoidJamsNow()
                toast("Umfahrung wird gesucht …")
            }
            row("Verkehr jetzt prüfen", "schnellere Route suchen") {
                e.refreshTrafficNow()
                toast("Verkehr wird geprüft …")
            }
        }
        row(if (e.prefs.voiceMuted) "Ton einschalten" else "Ton ausschalten", null) { e.toggleMuted() }
        row(
            if (e.prefs.camerasEnabled) "Blitzerwarnungen ausschalten" else "Blitzerwarnungen einschalten",
            if (e.prefs.camerasEnabled) "z. B. wenn Blitzer.de parallel läuft" else "feste Blitzer und Abstandsmessungen auf der Route",
        ) {
            val on = !e.prefs.camerasEnabled
            e.setCamerasEnabled(on)
            toast(if (on) "Blitzerwarnungen an" else "Blitzerwarnungen aus")
        }
        return androidx.car.app.model.ListTemplate.Builder()
            .setTitle("Mehr")
            .setHeaderAction(Action.BACK)
            .setSingleList(il.build())
            .build()
    }
}


// =============================================================================================
// Termine heute (Auftragssystem, nur lesend) und Kunde anrufen
// =============================================================================================

class CarAppointmentsScreen(carContext: CarContext) : EngineScreen(carContext) {
    /** Termin, dessen Adresse gerade gesucht wird (dann "laedt"). */
    private var pendingId: String? = null

    init {
        val e = engine
        if (e.appointmentSource.configured && !e.appointmentsLoading) e.loadAppointments()
    }

    override fun onGetTemplate(): Template {
        val e = engine
        val b = ListTemplate.Builder().setTitle("Termine heute").setHeaderAction(Action.BACK)
        if (pendingId != null || (e.appointmentsLoading && e.appointments.isEmpty())) return b.setLoading(true).build()
        // Chef-Zugang ohne Auswahl: zuerst waehlen, wessen Termine (wird gespeichert)
        if (e.appointmentTechnician == null && e.appointmentTechnicians.size > 1) {
            val tl = ItemList.Builder()
            for (t in e.appointmentTechnicians.take(CarUtil.listLimit(carContext, 10))) {
                tl.addItem(Row.Builder().setTitle(t.name).addText("Termine von heute anzeigen").setOnClickListener { e.chooseTechnician(t) }.build())
            }
            return b.setTitle("Wessen Termine?").setSingleList(tl.build()).build()
        }
        val il = ItemList.Builder().setNoItemsMessage(
            when {
                !e.appointmentSource.configured -> "Noch kein Zugang zum Auftragssystem – in KSS-Maps am Handy einrichten"
                e.appointmentsError != null -> "Termine konnten nicht geladen werden"
                else -> "Heute keine Termine"
            }
        )
        val own = e.ownNumbers
        for (a in e.appointments.take(CarUtil.listLimit(carContext, 20))) {
            val first = a.contacts(own).contacts.firstOrNull()
            val row = Row.Builder()
                .setTitle(Appointments.timeRange(a).substringBefore('–') + "  " + a.customerName.ifBlank { first?.name ?: "Termin" })
                .addText(a.address.ifBlank { "Adresse fehlt" })
                .setOnClickListener { pick(a) }
            if (first != null) row.addText("Tel. ${first.name}" + (first.hint?.let { " · $it" } ?: ""))
            il.addItem(row.build())
        }
        b.setSingleList(il.build())
        if (e.appointmentSource.configured) {
            b.setActionStrip(ActionStrip.Builder().addAction(CarUtil.textAction("Aktualisieren") { e.loadAppointments() }).build())
        }
        return b.build()
    }

    private fun pick(a: Appointment) {
        pendingId = a.id
        invalidate()
        engine.startAppointment(a) { ok ->
            pendingId = null
            when {
                // Inzwischen zurueck zur Karte: dort oeffnet sich die Vorschau von selbst
                screenManager.top !== this -> {}
                ok -> screenManager.push(CarRoutePreviewScreen(carContext))
                else -> {
                    CarToast.makeText(carContext, engine.message ?: "Adresse nicht gefunden", CarToast.LENGTH_LONG).show()
                    invalidate()
                }
            }
        }
    }
}

/** Ansprechpartner des angefahrenen Termins (aus dem Terminzusatz), wer zuerst anzurufen ist, oben. */
class CarCustomerScreen(carContext: CarContext) : EngineScreen(carContext) {
    override fun onGetTemplate(): Template {
        val a = engine.activeAppointment
        val b = ListTemplate.Builder().setHeaderAction(Action.BACK)
        if (a == null) {
            return b.setTitle("Kunde").setSingleList(ItemList.Builder().setNoItemsMessage("Kein Termin ausgewählt").build()).build()
        }
        b.setTitle(a.customerName.ifBlank { "Kunde" })
        val contacts = a.contacts(engine.ownNumbers).contacts
        val il = ItemList.Builder()
        // eine Zeile fuer den Terminzusatz frei lassen
        val limit = (CarUtil.listLimit(carContext, 12) - 1).coerceAtLeast(1)
        var n = 0
        for (c in contacts) for (i in c.dial.indices) {
            if (n >= limit) break
            val display = c.phones.getOrElse(i) { c.dial[i] }
            val row = Row.Builder()
                .setTitle(c.name)
                .addText(display)
                .setImage(CarUtil.glyph(Icons.Glyph.PHONE, if (c.callFirst) CarColor.GREEN else null))
                .setOnClickListener { screenManager.push(CarCallConfirmScreen(carContext, c.name, display, c.dial[i])) }
            val extra = c.hint ?: if (c.callFirst) "zuerst anrufen" else null
            if (extra != null) row.addText(extra)
            il.addItem(row.build())
            n++
        }
        if (contacts.isEmpty()) il.addItem(Row.Builder().setTitle("Keine Telefonnummer im Terminzusatz").build())
        if (a.note.isNotBlank()) il.addItem(Row.Builder().setTitle("Terminzusatz").addText(a.note).build())
        return b.setSingleList(il.build()).build()
    }
}

/** "Anrufen?" – bei Ja waehlt Android Auto die Nummer. */
class CarCallConfirmScreen(
    carContext: CarContext,
    private val name: String,
    private val display: String,
    private val digits: String,
) : Screen(carContext) {
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder("$name\n$display")
            .setTitle("Anrufen?")
            .setHeaderAction(Action.BACK)
            .setIcon(CarUtil.glyph(Icons.Glyph.PHONE, CarColor.GREEN))
            .addAction(Action.Builder().setTitle("Ja").setBackgroundColor(CarColor.GREEN).setOnClickListener { call() }.build())
            .addAction(CarUtil.textAction("Nein") { finish() })
            .build()

    internal fun call(): CarCall.Result {
        val r = CarCall.place(carContext, digits)
        screenManager.popToRoot()
        return r
    }
}


// =============================================================================================
// Direkt waehlen im Auto (ohne Waehlfeld, die Navigation bleibt die grosse Ansicht)
// =============================================================================================

object CarCall {
    enum class Result { CALLED, PERMISSION_ASKED, DIALER }

    /**
     * Mit Berechtigung ueber das Telefon des Handys waehlen (Android Auto zeigt nur die kleine
     * Anrufkarte). Ohne: einmal am Handy erlauben lassen, danach direkt; abgelehnt -> Waehlfeld.
     */
    fun place(ctx: CarContext, digits: String): Result {
        if (de.sascha.kssmaps.data.CallHelper.call(ctx, digits)) return Result.CALLED
        return try {
            ctx.requestPermissions(listOf(Manifest.permission.CALL_PHONE)) { granted, _ ->
                if (Manifest.permission.CALL_PHONE !in granted || !de.sascha.kssmaps.data.CallHelper.call(ctx, digits)) dial(ctx, digits)
            }
            Result.PERMISSION_ASKED
        } catch (e: Exception) {
            dial(ctx, digits)
            Result.DIALER
        }
    }

    private fun dial(ctx: CarContext, digits: String) {
        try {
            ctx.startCarApp(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", digits, null)))
        } catch (e: Exception) {
            CarToast.makeText(ctx, "Anruf nicht möglich", CarToast.LENGTH_LONG).show()
        }
    }
}

/** "Herr Mader anrufen?" kurz vor der Ankunft (Terminzusatz "… min vorher anrufen") – Ja waehlt direkt. */
fun showCallReminder(ctx: CarContext, name: String, display: String, digits: String) {
    try {
        if (ctx.carAppApiLevel >= 5) {
            val id = ("call-" + digits).hashCode()
            val b = Alert.Builder(id, CarText.create("$name anrufen?"), 30_000L)
                .setSubtitle(CarText.create(display))
                .setIcon(CarUtil.glyph(Icons.Glyph.PHONE, CarColor.GREEN))
                .addAction(Action.Builder().setTitle("Ja").setOnClickListener {
                    ctx.getCarService(AppManager::class.java).dismissAlert(id)
                    CarCall.place(ctx, digits)
                }.build())
                .addAction(Action.Builder().setTitle("Nein").setOnClickListener {
                    ctx.getCarService(AppManager::class.java).dismissAlert(id)
                }.build())
                .setCallback(object : AlertCallback {
                    override fun onCancel(reason: Int) {}
                    override fun onDismiss() {}
                })
            ctx.getCarService(AppManager::class.java).showAlert(b.build())
        } else {
            ctx.getCarService(androidx.car.app.ScreenManager::class.java).push(CarCallConfirmScreen(ctx, name, display, digits))
        }
    } catch (e: Exception) {
        CarToast.makeText(ctx, "$name anrufen: $display", CarToast.LENGTH_LONG).show()
    }
}

/** Zeile in Routenlisten: Laenge, Stau, Baustellen (ab [from] Metern, die aktive Route ab hier). */
private fun routeSummary(r: Route, from: Double): String {
    val parts = ArrayList<String>()
    parts.add(Instructions.formatDistance(r.distanceM - from))
    val ahead = r.hazards.filter { it.endAlong >= from }
    val jams = ahead.count { it.hazard.type.isTraffic }
    val delay = (ahead.filter { it.hazard.type.isTraffic }.sumOf { it.hazard.delaySec } / 60.0).roundToInt()
    parts.add(if (jams > 0) "Stau ${jams}×" + (if (delay > 0) " +$delay Min." else "") else "kein Stau")
    val works = ahead.count { it.hazard.type == HazardType.ROADWORKS }
    if (works > 0) parts.add("Baustellen $works")
    return parts.joinToString(" · ")
}

// =============================================================================================
// Uebersicht waehrend der Fahrt: ganze Strecke ab hier, Alternativen (eine davon staufrei)
// =============================================================================================

class CarRouteOverviewScreen(carContext: CarContext, private val session: NaviSession) : EngineScreen(carContext) {
    private var fitted = -1

    init {
        engine.computeNavAlternatives()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                engine.clearNavAlternatives()
                session.renderer.recenter()
            }
        })
    }

    override fun onStateChanged() {
        if (engine.mode != NavEngine.Mode.NAVIGATING) {
            finish()
            return
        }
        val n = engine.navAlternatives.size
        if (n != fitted) {
            fitted = n
            session.renderer.showOverview()
        }
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val e = engine
        val b = RoutePreviewNavigationTemplate.Builder().setTitle("Übersicht bis zum Ziel").setHeaderAction(Action.BACK)
        val alts = e.navAlternatives
        if (alts.isEmpty() || (e.navAltLoading && alts.size <= 1)) return b.setLoading(true).build()
        val along = e.state?.along ?: 0.0
        val il = ItemList.Builder()
        for (r in alts) {
            val from = if (r === e.activeRoute) along else 0.0
            il.addItem(Row.Builder().setTitle(CarUtil.durationText(e.remainingSecOf(r), r.label)).addText(routeSummary(r, from)).build())
        }
        il.setOnSelectedListener { idx ->
            e.selectNavAlternative(idx)
            session.renderer.showOverview()
        }
        il.setSelectedIndex(e.navAltSelected.coerceIn(0, alts.size - 1))
        b.setItemList(il.build())
        b.setNavigateAction(
            Action.Builder().setTitle(if (alts.getOrNull(e.navAltSelected) === e.activeRoute) "Weiter" else "Übernehmen")
                .setIcon(CarUtil.glyph(Icons.Glyph.NAVIGATE)).setOnClickListener {
                    e.applyNavAlternative()
                    finish()
                }.build(),
        )
        return b.build()
    }
}

// =============================================================================================
// Einstellungen im Auto
// =============================================================================================

class CarSettingsScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val e = engine
        val p = e.prefs
        val il = ItemList.Builder()
        val modes = listOf("Automatisch (nach Sonnenstand)", "Tag", "Nacht")
        il.addItem(Row.Builder().setTitle("Kartenansicht").addText(modes[p.nightMode.coerceIn(0, 2)]).setOnClickListener {
            e.cycleNightMode()
            invalidate()
        }.build())
        fun toggle(title: String, text: String?, on: Boolean, set: (Boolean) -> Unit) {
            val r = Row.Builder().setTitle(title).setToggle(Toggle.Builder { checked ->
                set(checked)
                invalidate()
            }.setChecked(on).build())
            if (text != null) r.addText(text)
            il.addItem(r.build())
        }
        toggle("Sprachansagen", null, !p.voiceMuted) { want -> if (want == p.voiceMuted) e.toggleMuted() }
        toggle("Schnellere Route anbieten", "Verkehr alle 2 Minuten prüfen", p.trafficReroute) { p.trafficReroute = it }
        toggle("Blitzerwarnungen", null, p.camerasEnabled) { e.setCamerasEnabled(it) }
        toggle("Mautstraßen meiden", "gilt für neue Routen", p.avoidTolls) { p.avoidTolls = it }
        toggle("Autobahnen meiden", "gilt für neue Routen", p.avoidMotorways) { p.avoidMotorways = it }
        return ListTemplate.Builder().setTitle("Einstellungen").setHeaderAction(Action.BACK).setSingleList(il.build()).build()
    }
}
