package de.sascha.kssmaps.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import de.sascha.kssmaps.core.FuelType
import de.sascha.kssmaps.core.Instructions
import de.sascha.kssmaps.core.Place
import de.sascha.kssmaps.core.RankedStation
import de.sascha.kssmaps.core.Tankerkoenig
import de.sascha.kssmaps.core.UtaStatus
import de.sascha.kssmaps.data.Services
import de.sascha.kssmaps.nav.NavEngine
import de.sascha.kssmaps.ui.Ui.margins
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Was die Panels vom Bildschirm brauchen. */
interface PanelHost {
    val context: Context
    val engine: NavEngine
    val services: Services
    val mapCtl: MapController?
    fun toast(s: String)
    fun openSettings()
    fun closePanels()
    /** Spracheingabe (Google-Spracherkennung); [onResult] bekommt den erkannten Text. */
    fun startVoice(prompt: String, onResult: (String) -> Unit)
}

/** Gemeinsamer Rahmen: Karte mit Kopfzeile, Inhalt scrollbar. */
abstract class Panel(protected val host: PanelHost, private val fullScreen: Boolean) {
    protected val ctx: Context = host.context
    val view: FrameLayout = FrameLayout(ctx)
    protected val card: LinearLayout = Ui.vbox(ctx)
    protected val titleView = Ui.text(ctx, "", 20f, Ui.TEXT, bold = true)
    protected val content: LinearLayout = Ui.vbox(ctx)
    protected val scroll = ScrollView(ctx)
    /**
     * Platz zwischen Kopfzeile und Liste. Unterklassen fuellen ihn in IHREM init-Block –
     * nicht hier per offener Methode: die liefe vor der Initialisierung der Unterklasse.
     */
    protected val extras: LinearLayout = Ui.vbox(ctx)

    init {
        view.visibility = View.GONE
        view.isClickable = true
        if (fullScreen) {
            view.setBackgroundColor(Color.WHITE)
            card.setBackgroundColor(Color.WHITE)
            view.addView(card, frameMatch())
        } else {
            view.setBackgroundColor(Color.argb(70, 0, 0, 0))
            view.setOnClickListener { hide() }
            card.background = Ui.bg(ctx, Color.WHITE, 22)
            card.isClickable = true
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.66).toInt(), Gravity.BOTTOM)
            view.addView(card, lp)
        }
        card.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 14), Ui.dp(ctx, 16), Ui.dp(ctx, 8))
        val head = Ui.hbox(ctx)
        head.addView(titleView, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.iconButton(ctx, Icons.glyph(Icons.Glyph.CLOSE, Ui.dp(ctx, 22), Ui.TEXT), Ui.LIGHT, 42, "Schließen") { hide() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = 0
            elevation = 0f
        })
        card.addView(head)
        card.addView(extras)
        scroll.addView(content)
        card.addView(scroll, Ui.linLp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).margins(ctx, t = 8))
    }

    val isShown: Boolean get() = view.visibility == View.VISIBLE

    /** Hochkant: Blatt von unten (2/3 Hoehe). Quer: Leiste links ueber die volle Hoehe. */
    fun applyLayout(landscape: Boolean, sideWidthPx: Int) {
        if (fullScreen) return
        val lp = card.layoutParams as? FrameLayout.LayoutParams ?: return
        if (landscape) {
            lp.width = sideWidthPx
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.START or Gravity.BOTTOM
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = (ctx.resources.displayMetrics.heightPixels * 0.66).toInt()
            lp.gravity = Gravity.BOTTOM
        }
        card.layoutParams = lp
    }

    open fun show() {
        view.visibility = View.VISIBLE
        view.bringToFront()
    }

    open fun hide() {
        view.visibility = View.GONE
    }

    protected fun row(title: String, subtitle: String? = null, leading: String? = null, onClick: (() -> Unit)? = null, onLong: (() -> Unit)? = null): LinearLayout {
        val r = Ui.hbox(ctx)
        r.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12))
        Ui.clickable(ctx, r, Color.WHITE, 12)
        if (leading != null) r.addView(Ui.text(ctx, leading, 22f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(Ui.dp(ctx, 40), ViewGroup.LayoutParams.WRAP_CONTENT))
        val col = Ui.vbox(ctx)
        col.addView(Ui.text(ctx, title, 17f, Ui.TEXT, bold = true, maxLines = 2))
        if (!subtitle.isNullOrBlank()) col.addView(Ui.text(ctx, subtitle, 14f, Ui.TEXT2, maxLines = 3))
        r.addView(col, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).margins(ctx, l = 6))
        if (onClick != null) r.setOnClickListener { onClick() }
        if (onLong != null) r.setOnLongClickListener { onLong(); true }
        return r
    }

    protected fun info(s: String, color: Int = Ui.TEXT2): TextView = Ui.text(ctx, s, 15f, color).apply {
        setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10))
    }
}

// =============================================================================================
// Suche + Verlauf (letzte 20 Ziele)
// =============================================================================================

class SearchPanel(host: PanelHost) : Panel(host, fullScreen = true) {
    enum class Target { DESTINATION, ORIGIN }

    private val input = EditText(ctx)
    private var job: Job? = null
    private var target = Target.DESTINATION
    /** Gesprochener Fahrbefehl ("Navigiere zu …"): bestes Ergebnis direkt nehmen. */
    private var autoPick = false

    init {
        titleView.text = "Wohin?"
        input.hint = "Adresse, Ort oder Firma"
        input.setSingleLine(true)
        input.imeOptions = EditorInfo.IME_ACTION_SEARCH
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
        input.textSize = 18f
        input.background = Ui.bg(ctx, Ui.LIGHT, 14)
        input.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 12))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = onQuery(s?.toString() ?: "", 450)
        })
        input.setOnEditorActionListener { _, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                onQuery(input.text.toString(), 0)
                true
            } else false
        }
        val row = Ui.hbox(ctx)
        row.addView(input, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.iconButton(ctx, Icons.glyph(Icons.Glyph.MIC, Ui.dp(ctx, 26), Color.WHITE), Ui.BLUE, 50, "Spracheingabe") { listen() }.apply {
            (layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; leftMargin = Ui.dp(ctx, 8) }
        })
        extras.addView(row, Ui.linLp().margins(ctx, t = 10))
    }

    override fun show() = open(Target.DESTINATION, voice = false)

    /** Suche fuer das Ziel oder den Startpunkt oeffnen; voice = sofort zuhoeren. */
    fun open(t: Target, voice: Boolean) {
        super.show()
        target = t
        autoPick = false
        titleView.text = if (t == Target.ORIGIN) "Von wo?" else "Wohin?"
        input.hint = if (t == Target.ORIGIN) "Startadresse, Ort oder Firma" else "Adresse, Ort oder Firma"
        input.setText("")
        showHistory()
        if (voice) {
            listen()
        } else {
            input.requestFocus()
            input.postDelayed({
                ctx.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }, 150)
        }
    }

    private fun listen() {
        ctx.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        host.startVoice(if (target == Target.ORIGIN) "Von wo soll es losgehen?" else "Wohin soll es gehen?") { spoken -> onVoice(spoken) }
    }

    /** Erkannter Text: Befehlswoerter weg, suchen; bei "Navigiere zu …" direkt das beste Ergebnis. */
    fun onVoice(spoken: String) {
        if (!isShown) super.show()
        val r = de.sascha.kssmaps.core.VoiceQuery.clean(spoken)
        autoPick = r.command
        input.setText(r.query)
        input.setSelection(input.text.length)
        onQuery(r.query, 0)
    }

    override fun hide() {
        ctx.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
        job?.cancel()
        super.hide()
    }

    private fun showHistory() {
        content.removeAllViews()
        if (target == Target.ORIGIN) {
            content.addView(row("Mein Standort", "Route ab der aktuellen Position", "📍", onClick = {
                hide()
                host.engine.setOrigin(null)
            }))
        }
        val h = host.engine.prefs.history
        if (h.isEmpty()) {
            if (target == Target.DESTINATION) content.addView(info("Noch keine Ziele. Tipp: Lange auf die Karte drücken, um einen Punkt direkt anzufahren."))
            return
        }
        content.addView(Ui.text(ctx, "Zuletzt (${h.size} von 20)", 14f, Ui.TEXT2, bold = true).apply { setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4)) })
        for (p in h) {
            content.addView(row(p.name, p.address, "🕘", onClick = { choose(p) }, onLong = {
                AlertDialog.Builder(ctx).setTitle(p.name).setMessage("Aus dem Verlauf entfernen?")
                    .setPositiveButton("Entfernen") { _, _ -> host.engine.prefs.removeFromHistory(p); showHistory() }
                    .setNegativeButton("Abbrechen", null).show()
            }))
        }
    }

    private fun onQuery(q: String, delayMs: Long) {
        job?.cancel()
        if (q.trim().length < 3) {
            if (q.isBlank()) showHistory()
            return
        }
        val pick = autoPick
        job = host.engine.scope.launch {
            delay(delayMs)
            content.removeAllViews()
            content.addView(info("Suche …"))
            try {
                val res = host.services.search(q.trim(), host.engine.position ?: host.engine.prefs.lastPosition)
                content.removeAllViews()
                if (res.isEmpty()) content.addView(info("Nichts gefunden."))
                if (pick && res.isNotEmpty()) {
                    autoPick = false
                    host.toast("${res[0].name}${if (res[0].address.isNotBlank()) ", " + res[0].address else ""}")
                    choose(res[0])
                    return@launch
                }
                for (p in res) content.addView(row(p.name, p.address, if (p.houseNumber != null) "🏠" else "📍", onClick = { choose(p) }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                content.removeAllViews()
                content.addView(info("Suche nicht möglich (Internet?)", Ui.RED))
            }
        }
    }

    private fun choose(p: Place) {
        hide()
        if (target == Target.ORIGIN) host.engine.setOrigin(p) else host.engine.setDestination(p)
    }
}

// =============================================================================================
// Wegbeschreibung: alle Abbiegungen der Route mit Entfernungen (auch bei eigenem Startpunkt)
// =============================================================================================

class StepsPanel(host: PanelHost) : Panel(host, fullScreen = false) {
    private val summary = Ui.text(ctx, "", 15f, Ui.TEXT2, maxLines = 2)

    init {
        titleView.text = "Wegbeschreibung"
        extras.addView(summary, Ui.linLp().margins(ctx, t = 2))
    }

    fun showRoute(r: de.sascha.kssmaps.core.Route) {
        super.show()
        val e = host.engine
        val from = e.origin?.name ?: "Mein Standort"
        val to = e.destination?.name ?: "Ziel"
        summary.text = "$from → $to\n${Instructions.formatDistance(r.distanceM)} · ${Ui.minutes(r.durationSec)}" +
            (if (r.trafficDelaySec >= 60) " (inkl. ${Ui.minutes(r.trafficDelaySec)} Stau)" else "")
        content.removeAllViews()
        val from0 = if (e.mode == NavEngine.Mode.NAVIGATING && e.activeRoute === r) e.state?.along ?: 0.0 else 0.0
        // Abbiegungen und Meldungen (Stau, Baustelle, Sperrung) in Fahrtreihenfolge
        val items = ArrayList<Pair<Double, () -> View>>()
        val shown = r.maneuvers.filter { (it.kind.significant || it.kind == de.sascha.kssmaps.core.ManeuverKind.DEPART) && it.along >= from0 - 1 }
        for ((i, m) in shown.withIndex()) {
            val next = shown.getOrNull(i + 1)
            items.add(m.along to { stepRow(m, next?.let { it.along - m.along }) })
        }
        for (rh in r.hazards) {
            val t = rh.hazard.type
            if (!(t.isTraffic || t == de.sascha.kssmaps.core.HazardType.ROADWORKS || t == de.sascha.kssmaps.core.HazardType.CLOSURE)) continue
            if (rh.endAlong < from0) continue
            items.add(rh.startAlong + 0.5 to {
                Ui.text(ctx, Icons.hazardEmoji(t) + "  " + Instructions.hazardShort(rh.hazard) + " · bei km " + km(rh.startAlong), 14f, Icons.hazardColor(t), bold = true).apply {
                    setPadding(Ui.dp(ctx, 52), Ui.dp(ctx, 2), Ui.dp(ctx, 8), Ui.dp(ctx, 6))
                }
            })
        }
        items.sortBy { it.first }
        for (it in items) content.addView(it.second())
        if (items.isEmpty()) content.addView(info("Keine Abbiegungen – der Straße folgen."))
    }

    private fun km(m: Double): String = String.format(java.util.Locale.GERMANY, "%.1f", m / 1000.0)

    private fun stepRow(m: de.sascha.kssmaps.core.Maneuver, toNext: Double?): View {
        val r = Ui.hbox(ctx)
        r.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 8), Ui.dp(ctx, 8))
        val iv = android.widget.ImageView(ctx)
        iv.setImageBitmap(Icons.maneuver(m.kind, Ui.dp(ctx, 30), Ui.TEXT, roundaboutExit = m.roundaboutExit, angleHint = m.turnAngle))
        r.addView(iv, LinearLayout.LayoutParams(Ui.dp(ctx, 34), Ui.dp(ctx, 34)))
        val col = Ui.vbox(ctx)
        col.addView(Ui.text(ctx, if (m.kind == de.sascha.kssmaps.core.ManeuverKind.DEPART) "Start" + (m.street?.let { " auf $it" } ?: "") else Instructions.text(m), 16f, Ui.TEXT, bold = true, maxLines = 2))
        val sub = buildString {
            append("km ").append(km(m.along))
            if (toNext != null && toNext > 1) append(" · dann ").append(Instructions.formatDistance(toNext))
        }
        col.addView(Ui.text(ctx, sub, 13f, Ui.TEXT2, maxLines = 1))
        r.addView(col, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).margins(ctx, l = 12))
        return r
    }
}

// =============================================================================================
// Tankstellen: auf dem Weg zuerst, dann Preis; UTA-Akzeptanz
// =============================================================================================

class FuelPanel(host: PanelHost) : Panel(host, fullScreen = false) {
    private val chips = Ui.hbox(ctx)
    private var results: List<RankedStation> = emptyList()
    private var job: Job? = null

    init {
        titleView.text = "Tankstellen"
        extras.addView(chips, Ui.linLp().margins(ctx, t = 6))
    }

    private fun renderChips() {
        chips.removeAllViews()
        val prefs = host.engine.prefs
        for (t in FuelType.values()) {
            chips.addView(chip(t.label, prefs.fuelType == t) {
                prefs.fuelType = t
                load()
            })
        }
        chips.addView(chip("nur UTA", prefs.utaOnly) {
            prefs.utaOnly = !prefs.utaOnly
            load()
        })
    }

    private fun chip(label: String, on: Boolean, click: () -> Unit): TextView = Ui.text(ctx, label, 14f, if (on) Color.WHITE else Ui.TEXT, bold = on).apply {
        setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 7), Ui.dp(ctx, 12), Ui.dp(ctx, 7))
        Ui.clickable(ctx, this, if (on) Ui.BLUE else Ui.LIGHT, 16)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).margins(ctx, r = 6)
        setOnClickListener { click() }
    }

    override fun show() {
        super.show()
        load()
    }

    override fun hide() {
        job?.cancel()
        host.mapCtl?.setFuelResults(emptyList())
        super.hide()
    }

    fun load() {
        renderChips()
        val e = host.engine
        titleView.text = when (e.mode) {
            NavEngine.Mode.IDLE -> "Tankstellen in der Nähe"
            else -> "Tankstellen auf dem Weg"
        }
        content.removeAllViews()
        content.addView(info("Suche Tankstellen und Preise …"))
        job?.cancel()
        job = e.scope.launch {
            try {
                results = e.searchFuel()
                render()
                host.mapCtl?.setFuelResults(results)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Services.MissingKey) {
                content.removeAllViews()
                content.addView(info(ex.message ?: "", Ui.RED))
                content.addView(Ui.button(ctx, "Schlüssel eintragen", Ui.BLUE) { hide(); host.openSettings() }, Ui.linLp().margins(ctx, t = 8))
            } catch (ex: Exception) {
                content.removeAllViews()
                content.addView(info("Tankstellen konnten nicht geladen werden: ${ex.message}", Ui.RED))
            }
        }
    }

    private fun render() {
        content.removeAllViews()
        val e = host.engine
        val fuel = e.prefs.fuelType
        if (results.isEmpty()) {
            content.addView(info(if (e.prefs.utaOnly) "Keine geöffnete UTA-Tankstelle gefunden. Tipp: 'nur UTA' ausschalten." else "Keine geöffnete Tankstelle gefunden."))
            return
        }
        val noPrices = e.services.fuelWithoutPrices
        if (noPrices) {
            content.addView(info("Ohne Preise: Für Diesel- und Super-Preise einmalig den kostenlosen Tankerkönig-Schlüssel eintragen.", Ui.ORANGE))
            content.addView(Ui.button(ctx, "Schlüssel eintragen", Ui.BLUE) { hide(); host.openSettings() }, Ui.linLp().margins(ctx, b = 6))
        }
        var shownHeaderOther = false
        val onWayCount = results.count { it.onTheWay }
        if (onWayCount > 0) content.addView(Ui.text(ctx, "Auf dem Weg zum Ziel ($onWayCount)", 14f, Ui.GREEN_DARK, bold = true).apply { setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 6), 0, Ui.dp(ctx, 2)) })
        for ((i, rs) in results.withIndex()) {
            if (!rs.onTheWay && !shownHeaderOther && e.mode != NavEngine.Mode.IDLE) {
                shownHeaderOther = true
                content.addView(Ui.text(ctx, "Mit Umweg", 14f, Ui.ORANGE, bold = true).apply { setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 10), 0, Ui.dp(ctx, 2)) })
            }
            content.addView(stationRow(i, rs, fuel))
        }
        content.addView(info(
            if (noPrices) "Tankstellen: © OpenStreetMap-Mitwirkende (ohne Preise). UTA-Angabe: Marke/OSM bzw. deine eigene Korrektur (lange drücken)."
            else "Preise: Markttransparenzstelle für Kraftstoffe via Tankerkönig (CC BY 4.0). UTA-Angabe: Marke/OSM bzw. deine eigene Korrektur (lange drücken)."
        ))
    }

    private fun stationRow(i: Int, rs: RankedStation, fuel: FuelType): View {
        val s = rs.station
        val r = Ui.hbox(ctx)
        r.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 10), Ui.dp(ctx, 10))
        Ui.clickable(ctx, r, if (i == 0) Color.parseColor("#E8F5E9") else Color.WHITE, 12)
        val priceCol = Ui.vbox(ctx)
        // Nur die gewaehlte Sorte: frueher stand darunter die jeweils andere Sorte ("E5 …" bei
        // Diesel), das las sich wie ein falscher Preis.
        priceCol.addView(Ui.text(ctx, Tankerkoenig.formatPrice(s.price(fuel)), 20f, Ui.GREEN_DARK, bold = true))
        priceCol.addView(Ui.text(ctx, if (s.price(fuel) == null) "${fuel.label}: kein Preis" else fuel.label, 13f, Ui.TEXT2))
        // Ohne Tankerkoenig-Schluessel gibt es keine Preise – dann keine leere Preisspalte
        if (!host.engine.services.fuelWithoutPrices) {
            r.addView(priceCol, LinearLayout.LayoutParams(Ui.dp(ctx, 112), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val col = Ui.vbox(ctx)
        val uta = when (s.uta) {
            UtaStatus.YES -> "✅ UTA"
            UtaStatus.NO -> "❌ kein UTA"
            UtaStatus.UNKNOWN -> "❔ UTA unbekannt"
        }
        col.addView(Ui.text(ctx, "${i + 1}. ${s.displayName()}  ·  $uta", 16f, Ui.TEXT, bold = true, maxLines = 1))
        col.addView(Ui.text(ctx, s.addressLine(), 13f, Ui.TEXT2, maxLines = 1))
        val where = when {
            rs.along == null -> "${Instructions.formatDistance(rs.distFromHereM)} entfernt"
            rs.onTheWay -> "auf dem Weg · in ${Instructions.formatDistance(rs.distToStationM)}"
            else -> "Umweg ca. ${Instructions.formatDistance(rs.detourM)}"
        }
        col.addView(Ui.text(ctx, where, 13f, if (rs.onTheWay) Ui.GREEN_DARK else Ui.ORANGE, bold = true))
        r.addView(col, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).margins(ctx, l = 8))
        r.setOnClickListener { confirm(rs) }
        r.setOnLongClickListener {
            utaDialog(rs)
            true
        }
        return r
    }

    private fun confirm(rs: RankedStation) {
        val e = host.engine
        val s = rs.station
        val msg = if (e.destination != null && e.destination?.category != "fuel")
            "${s.displayName()}, ${s.addressLine()}\n\nDie Route führt zuerst zur Tankstelle und danach automatisch weiter zum Ziel – ohne neue Eingabe."
        else "${s.displayName()}, ${s.addressLine()}"
        AlertDialog.Builder(ctx).setTitle("Hier tanken?").setMessage(msg)
            .setPositiveButton("Anfahren") { _, _ ->
                hide()
                e.addFuelStop(s)
            }
            .setNeutralButton("Auf Karte") { _, _ -> host.mapCtl?.showPoint(s.pos, 16.0) }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun utaDialog(rs: RankedStation) {
        val prefs = host.engine.prefs
        val id = rs.station.id
        AlertDialog.Builder(ctx).setTitle("UTA bei ${rs.station.displayName()}")
            .setItems(arrayOf("UTA wird hier angenommen", "UTA wird hier NICHT angenommen", "Zurücksetzen (Marke entscheidet)")) { _, w ->
                prefs.setUtaOverride(id, when (w) { 0 -> true; 1 -> false; else -> null })
                load()
            }.show()
    }
}

// =============================================================================================
// Gesperrte Abschnitte
// =============================================================================================

class AvoidPanel(host: PanelHost) : Panel(host, fullScreen = false) {
    init {
        titleView.text = "Gemiedene Abschnitte"
    }

    override fun show() {
        super.show()
        render()
    }

    fun render() {
        content.removeAllViews()
        content.addView(info("So geht's: Lange auf eine Straße in der Karte drücken → „Abschnitt meiden“. Bei Autobahnen werden beide Fahrtrichtungen gesperrt – nur dieses Stück, nicht die ganze Autobahn."))
        val list = host.engine.avoidSections
        if (list.isEmpty()) {
            content.addView(info("Noch keine Abschnitte gesperrt."))
            return
        }
        for (a in list) {
            val r = Ui.hbox(ctx)
            r.setPadding(Ui.dp(ctx, 6), Ui.dp(ctx, 8), Ui.dp(ctx, 6), Ui.dp(ctx, 8))
            val sw = Switch(ctx)
            sw.isChecked = a.enabled
            sw.setOnCheckedChangeListener { _, on -> host.engine.setAvoidEnabled(a.id, on); host.mapCtl?.updateAvoid(); host.mapCtl?.updatePoints() }
            r.addView(sw)
            val t = Ui.text(ctx, a.name, 16f, if (a.enabled) Ui.TEXT else Ui.TEXT2, bold = true, maxLines = 2)
            t.setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8), 0)
            t.setOnClickListener {
                val pts = a.shapes.flatten().ifEmpty { a.points }
                host.closePanels()
                host.mapCtl?.fitPoints(pts)
            }
            t.setOnLongClickListener {
                val et = EditText(ctx).apply { setText(a.name) }
                AlertDialog.Builder(ctx).setTitle("Umbenennen").setView(et)
                    .setPositiveButton("OK") { _, _ -> host.engine.renameAvoid(a.id, et.text.toString().ifBlank { a.name }); render() }
                    .setNegativeButton("Abbrechen", null).show()
                true
            }
            r.addView(t, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(Ui.iconButton(ctx, Icons.glyph(Icons.Glyph.CLOSE, Ui.dp(ctx, 20), Ui.RED), Ui.LIGHT, 40) {
                AlertDialog.Builder(ctx).setTitle("Löschen?").setMessage(a.name)
                    .setPositiveButton("Löschen") { _, _ ->
                        host.engine.deleteAvoid(a.id)
                        host.mapCtl?.updateAvoid()
                        host.mapCtl?.updatePoints()
                        render()
                    }.setNegativeButton("Abbrechen", null).show()
            }.apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = 0
                elevation = 0f
            })
            content.addView(r)
            content.addView(Ui.divider(ctx))
        }
    }
}

// =============================================================================================
// Meldungen auf der Route (Liste)
// =============================================================================================

class HazardListPanel(host: PanelHost) : Panel(host, fullScreen = false) {
    init {
        titleView.text = "Auf der Route"
    }

    override fun show() {
        super.show()
        render()
    }

    fun render() {
        content.removeAllViews()
        val e = host.engine
        val r = (if (e.mode == NavEngine.Mode.NAVIGATING) e.activeRoute else e.selectedRoute)
        if (r == null) {
            content.addView(info("Keine Route aktiv."))
            return
        }
        val along = e.state?.along ?: 0.0
        val list = r.hazards.filter { it.endAlong >= along - 20 && (e.prefs.camerasEnabled || !it.hazard.type.isEnforcement) }
        content.addView(info("Nur Meldungen, die direkt auf deiner Strecke liegen. Quelle: " + (if (r.source == de.sascha.kssmaps.core.RouteSource.TOMTOM) "TomTom-Verkehr, " else "") + "Autobahn GmbH, OpenStreetMap (Blitzer, Abstandsmessungen), eigene Meldungen."))
        if (list.isEmpty()) {
            content.addView(info("Keine Meldungen – freie Fahrt. 👍", Ui.GREEN_DARK))
        }
        for (rh in list) {
            val h = rh.hazard
            val d = rh.startAlong - along
            val dist = if (d <= 0) "jetzt" else "in " + Instructions.formatDistance(d)
            val sub = listOf(h.description.replace('\n', ' ').take(160), if (h.delaySec >= 60) "+${(h.delaySec / 60.0).roundToInt()} Min." else "").filter { it.isNotBlank() }.joinToString(" · ")
            content.addView(row("${Instructions.hazardShort(h)} – $dist", sub, Icons.hazardEmoji(h.type), onClick = {
                host.closePanels()
                host.mapCtl?.showPoint(if (h.type.isEnforcement && h.geometry.size < 2) h.point else r.geometry.pointAt(rh.startAlong), 15.5)
            }))
        }
        if (e.mode == NavEngine.Mode.NAVIGATING) {
            content.addView(Ui.button(ctx, "Verkehr jetzt prüfen", Ui.BLUE) { e.refreshTrafficNow(); host.toast("Verkehr wird geprüft …") }, Ui.linLp().margins(ctx, t = 10))
            content.addView(Ui.button(ctx, "Stau umfahren", Ui.LIGHT, Ui.TEXT) { host.closePanels(); e.avoidJamsNow() }, Ui.linLp().margins(ctx, t = 8, b = 10))
        }
    }
}
