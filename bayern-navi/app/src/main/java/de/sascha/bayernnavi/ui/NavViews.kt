package de.sascha.bayernnavi.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import de.sascha.bayernnavi.core.HazardType
import de.sascha.bayernnavi.core.Instructions
import de.sascha.bayernnavi.core.Lane
import de.sascha.bayernnavi.core.ManeuverKind
import de.sascha.bayernnavi.core.Route
import de.sascha.bayernnavi.core.SpeedLimitRange
import de.sascha.bayernnavi.nav.NavEngine
import de.sascha.bayernnavi.ui.Ui.margins
import kotlin.math.roundToInt

/** Gruenes Banner oben: naechstes Manoever, Entfernung, Strasse, Spuren, "Danach …". */
class ManeuverBanner(private val ctx: Context) {
    val view: LinearLayout = Ui.vbox(ctx)
    private val icon = ImageView(ctx)
    private val dist = Ui.text(ctx, "", 32f, Color.WHITE, bold = true)
    private val street = Ui.text(ctx, "", 19f, Color.WHITE, bold = true, maxLines = 2)
    private val lanes = Ui.hbox(ctx)
    private val thenRow = Ui.hbox(ctx)
    private val thenIcon = ImageView(ctx)
    private val thenText = Ui.text(ctx, "", 15f, Color.WHITE, maxLines = 1)
    private var lastKey = ""

    init {
        view.background = Ui.bg(ctx, Ui.NAV_GREEN, 18)
        view.elevation = Ui.dpf(ctx, 6f)
        view.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 12))
        val top = Ui.hbox(ctx)
        top.addView(icon, LinearLayout.LayoutParams(Ui.dp(ctx, 72), Ui.dp(ctx, 72)))
        val col = Ui.vbox(ctx)
        col.addView(dist)
        col.addView(street)
        top.addView(col, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).margins(ctx, l = 12))
        view.addView(top)
        lanes.gravity = Gravity.CENTER
        view.addView(lanes, Ui.linLp().margins(ctx, t = 6))
        thenRow.addView(Ui.text(ctx, "Danach", 14f, Color.argb(200, 255, 255, 255)))
        thenRow.addView(thenIcon, LinearLayout.LayoutParams(Ui.dp(ctx, 26), Ui.dp(ctx, 26)).margins(ctx, l = 8, r = 6))
        thenRow.addView(thenText, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        thenRow.background = Ui.bg(ctx, Color.argb(60, 0, 0, 0), 10)
        thenRow.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 4), Ui.dp(ctx, 10), Ui.dp(ctx, 4))
        view.addView(thenRow, Ui.linLp().margins(ctx, t = 8))
    }

    fun render(e: NavEngine) {
        val r = e.activeRoute
        val st = e.state
        if (e.rerouting) {
            dist.text = "Neue Route …"
            street.text = "wird berechnet"
            icon.setImageBitmap(Icons.glyph(Icons.Glyph.REFRESH, Ui.dp(ctx, 72)))
            lanes.visibility = View.GONE
            thenRow.visibility = View.GONE
            lastKey = ""
            return
        }
        if (r == null || st == null) {
            dist.text = "Warte auf GPS …"
            street.text = e.destination?.name ?: ""
            icon.setImageBitmap(Icons.glyph(Icons.Glyph.NAVIGATE, Ui.dp(ctx, 72)))
            lanes.visibility = View.GONE
            thenRow.visibility = View.GONE
            return
        }
        if (e.arrived) {
            dist.text = "Ziel erreicht"
            street.text = e.destination?.label() ?: ""
            icon.setImageBitmap(Icons.maneuver(ManeuverKind.ARRIVE, Ui.dp(ctx, 72)))
            lanes.visibility = View.GONE
            thenRow.visibility = View.GONE
            return
        }
        dist.text = Instructions.formatDistance(st.distToNext)
        val m = r.maneuvers.getOrNull(st.nextIdx)
        if (m == null) {
            street.text = "Der Straße folgen"
            icon.setImageBitmap(Icons.maneuver(ManeuverKind.STRAIGHT, Ui.dp(ctx, 72)))
            thenRow.visibility = View.GONE
            lanes.visibility = View.GONE
            return
        }
        val key = "${st.nextIdx}/${r.hashCode()}"
        if (key != lastKey) {
            lastKey = key
            icon.setImageBitmap(Icons.maneuver(m.kind, Ui.dp(ctx, 72), roundaboutExit = m.roundaboutExit, angleHint = m.turnAngle))
            street.text = if (m.kind == ManeuverKind.WAYPOINT && e.fuelStop != null) "Tankstelle: ${e.fuelStop?.displayName()}" else Instructions.text(m)
            renderLanes(m.lanes)
            val after = r.maneuvers.getOrNull(st.afterIdx)
            if (after != null && after.along - m.along < 600) {
                thenRow.visibility = View.VISIBLE
                thenIcon.setImageBitmap(Icons.maneuver(after.kind, Ui.dp(ctx, 26), roundaboutExit = after.roundaboutExit, angleHint = after.turnAngle))
                thenText.text = Instructions.text(after)
            } else thenRow.visibility = View.GONE
        }
        lanes.visibility = if (lanes.childCount > 0 && st.distToNext < 1500) View.VISIBLE else View.GONE
    }

    private fun renderLanes(list: List<Lane>) {
        lanes.removeAllViews()
        if (list.size < 2 || list.size > 8) return
        for (l in list) {
            val kind = laneKind(if (l.active != 0) l.active else if (l.valid != 0) l.valid else l.directions)
            val good = l.active != 0 || l.valid != 0
            val color = if (good) Color.WHITE else Color.argb(90, 255, 255, 255)
            val iv = ImageView(ctx)
            iv.setImageBitmap(Icons.maneuver(kind, Ui.dp(ctx, 30), color))
            iv.background = Ui.bg(ctx, if (l.active != 0) Color.argb(70, 255, 255, 255) else Color.TRANSPARENT, 6)
            lanes.addView(iv, LinearLayout.LayoutParams(Ui.dp(ctx, 34), Ui.dp(ctx, 34)).margins(ctx, l = 2, r = 2))
        }
    }

    private fun laneKind(mask: Int): ManeuverKind = when {
        mask and 2 != 0 -> ManeuverKind.STRAIGHT
        mask and 16 != 0 -> ManeuverKind.SLIGHT_LEFT
        mask and 32 != 0 -> ManeuverKind.SLIGHT_RIGHT
        mask and 8 != 0 -> ManeuverKind.LEFT
        mask and 64 != 0 -> ManeuverKind.RIGHT
        mask and 4 != 0 -> ManeuverKind.SHARP_LEFT
        mask and 128 != 0 -> ManeuverKind.SHARP_RIGHT
        mask and 256 != 0 -> ManeuverKind.UTURN
        else -> ManeuverKind.STRAIGHT
    }
}

/** Unten links: Tempolimit-Schild und aktuelle Geschwindigkeit (rot bei Ueberschreitung). */
class SpeedWidget(private val ctx: Context) {
    val view: LinearLayout = Ui.hbox(ctx)
    private val sign = ImageView(ctx)
    private val speedBox = Ui.vbox(ctx)
    private val speed = Ui.text(ctx, "0", 28f, Ui.TEXT, bold = true)
    private val unit = Ui.text(ctx, "km/h", 11f, Ui.TEXT2)
    private var lastLimit: Int? = Int.MIN_VALUE

    init {
        speedBox.gravity = Gravity.CENTER
        speedBox.addView(speed)
        speedBox.addView(unit)
        speed.gravity = Gravity.CENTER
        unit.gravity = Gravity.CENTER
        speedBox.background = Ui.bg(ctx, Color.WHITE, 40, Color.parseColor("#DADCE0"), 1)
        speedBox.elevation = Ui.dpf(ctx, 4f)
        view.addView(speedBox, LinearLayout.LayoutParams(Ui.dp(ctx, 70), Ui.dp(ctx, 70)))
        sign.elevation = Ui.dpf(ctx, 4f)
        view.addView(sign, LinearLayout.LayoutParams(Ui.dp(ctx, 62), Ui.dp(ctx, 62)).margins(ctx, l = 8))
    }

    fun render(e: NavEngine) {
        val kmh = e.speedKmh.roundToInt()
        speed.text = kmh.toString()
        val over = e.overspeed
        speed.setTextColor(if (over) Color.WHITE else Ui.TEXT)
        unit.setTextColor(if (over) Color.WHITE else Ui.TEXT2)
        speedBox.background = Ui.bg(ctx, if (over) Ui.RED else Color.WHITE, 40, Color.parseColor("#DADCE0"), 1)
        val lim = e.speedLimit
        if (lim != lastLimit) {
            lastLimit = lim
            if (lim == null || lim == 0) sign.visibility = View.GONE
            else {
                sign.visibility = View.VISIBLE
                sign.setImageBitmap(Icons.speedSign(if (lim == SpeedLimitRange.UNLIMITED) -1 else lim, Ui.dp(ctx, 62)))
            }
        }
    }
}

/** Unten waehrend der Fahrt: Ankunft, Restzeit, Reststrecke + Knoepfe. */
class NavBar(private val ctx: Context, onStop: () -> Unit, onFuel: () -> Unit, onList: () -> Unit) {
    val view: LinearLayout = Ui.vbox(ctx)
    private val eta = Ui.text(ctx, "", 24f, Ui.GREEN_DARK, bold = true)
    private val remain = Ui.text(ctx, "", 15f, Ui.TEXT2)
    private val delay = Ui.text(ctx, "", 14f, Ui.RED, bold = true)
    private val fuelChip = Ui.text(ctx, "", 14f, Ui.GREEN_DARK, bold = true)
    var onRemoveFuel: (() -> Unit)? = null

    init {
        view.background = Ui.bg(ctx, Color.WHITE, 22)
        view.elevation = Ui.dpf(ctx, 8f)
        view.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 12), Ui.dp(ctx, 12), Ui.dp(ctx, 12))
        val row = Ui.hbox(ctx)
        val col = Ui.vbox(ctx)
        col.addView(eta)
        col.addView(remain)
        col.addView(delay)
        row.addView(col, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.iconButton(ctx, Icons.glyph(Icons.Glyph.LIST, Ui.dp(ctx, 26), Ui.TEXT), Ui.LIGHT, 48) { onList() }.apply {
            (layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; rightMargin = Ui.dp(ctx, 8) }
            elevation = 0f
        })
        row.addView(Ui.iconButton(ctx, Icons.glyph(Icons.Glyph.FUEL, Ui.dp(ctx, 26), Ui.TEXT), Ui.LIGHT, 48) { onFuel() }.apply {
            (layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; rightMargin = Ui.dp(ctx, 8) }
            elevation = 0f
        })
        row.addView(Ui.button(ctx, "Beenden", Ui.RED) { onStop() })
        view.addView(row)
        fuelChip.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 6), Ui.dp(ctx, 10), Ui.dp(ctx, 6))
        Ui.clickable(ctx, fuelChip, Color.parseColor("#E8F5E9"), 12)
        fuelChip.setOnClickListener { onRemoveFuel?.invoke() }
        view.addView(fuelChip, Ui.linLp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(ctx, t = 8))
    }

    fun render(e: NavEngine) {
        val st = e.state
        val r = e.activeRoute
        if (st == null || r == null) {
            eta.text = "Navigation"
            remain.text = ""
            delay.visibility = View.GONE
        } else {
            eta.text = "Ankunft ${e.etaText(st.remainingSec)}"
            remain.text = "${Ui.minutes(st.remainingSec)} · ${Instructions.formatDistance(st.remainingM)}"
            val d = r.trafficDelaySec
            val jams = r.hazards.count { it.hazard.type.isTraffic && it.endAlong > st.along }
            if (d >= 60 || jams > 0) {
                delay.visibility = View.VISIBLE
                delay.text = buildString {
                    if (jams > 0) append("$jams× Stau voraus")
                    if (d >= 60) {
                        if (isNotEmpty()) append(" · ")
                        append("+${(d / 60).roundToInt()} Min.")
                    }
                }
            } else delay.visibility = View.GONE
        }
        val fs = e.fuelStop
        if (fs != null) {
            fuelChip.visibility = View.VISIBLE
            fuelChip.text = "⛽ Zwischenstopp: ${fs.displayName()} – antippen zum Entfernen"
        } else fuelChip.visibility = View.GONE
    }
}

/** Unten in der Routenvorschau: Alternativen mit Zeit, Stau, Blitzer, Baustellen. */
class RoutePanel(
    private val ctx: Context,
    private val onSelect: (Int) -> Unit,
    private val onStart: () -> Unit,
    private val onAvoidJams: () -> Unit,
    private val onFuel: () -> Unit,
    private val onClose: () -> Unit,
) {
    val view: LinearLayout = Ui.vbox(ctx)
    private val title = Ui.text(ctx, "", 19f, Ui.TEXT, bold = true, maxLines = 1)
    private val subtitle = Ui.text(ctx, "", 14f, Ui.TEXT2, maxLines = 1)
    private val list = Ui.vbox(ctx)
    private val status = Ui.text(ctx, "", 15f, Ui.TEXT2)
    private val startBtn: TextView

    init {
        view.background = Ui.bg(ctx, Color.WHITE, 22)
        view.elevation = Ui.dpf(ctx, 8f)
        view.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 14))
        val head = Ui.hbox(ctx)
        val hc = Ui.vbox(ctx)
        hc.addView(title)
        hc.addView(subtitle)
        head.addView(hc, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.iconButton(ctx, Icons.glyph(Icons.Glyph.CLOSE, Ui.dp(ctx, 22), Ui.TEXT), Ui.LIGHT, 40) { onClose() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = 0
            elevation = 0f
        })
        view.addView(head)
        view.addView(status, Ui.linLp().margins(ctx, t = 6))
        view.addView(list, Ui.linLp().margins(ctx, t = 6))
        val buttons = Ui.hbox(ctx)
        buttons.addView(Ui.button(ctx, "Stau umfahren", Ui.LIGHT, Ui.TEXT) { onAvoidJams() }, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).margins(ctx, r = 6))
        buttons.addView(Ui.button(ctx, "⛽ Tanken", Ui.LIGHT, Ui.TEXT) { onFuel() }, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f).margins(ctx, r = 6))
        startBtn = Ui.button(ctx, "Los", Ui.BLUE) { onStart() }
        buttons.addView(startBtn, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f))
        view.addView(buttons, Ui.linLp().margins(ctx, t = 10))
    }

    fun render(e: NavEngine) {
        val d = e.destination
        title.text = d?.name ?: "Ziel"
        subtitle.text = listOfNotNull(d?.address?.takeIf { it.isNotBlank() }, e.fuelStop?.let { "über ⛽ ${it.displayName()}" }).joinToString(" · ")
        list.removeAllViews()
        when {
            e.loading -> {
                status.visibility = View.VISIBLE
                status.text = "Route wird berechnet …"
            }
            e.routes.isEmpty() -> {
                status.visibility = View.VISIBLE
                status.text = e.message ?: "Keine Route"
            }
            else -> {
                status.visibility = if (e.message != null) View.VISIBLE else View.GONE
                status.text = e.message ?: ""
                e.routes.forEachIndexed { i, r -> list.addView(routeRow(r, i, i == e.selectedIndex, e.routes.minOf { it.durationSec }), Ui.linLp().margins(ctx, b = 6)) }
            }
        }
        startBtn.alpha = if (e.routes.isEmpty()) 0.4f else 1f
        startBtn.isEnabled = e.routes.isNotEmpty()
    }

    private fun routeRow(r: Route, i: Int, selected: Boolean, fastest: Double): View {
        val row = Ui.hbox(ctx)
        row.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10))
        Ui.clickable(ctx, row, if (selected) Color.parseColor("#E3F0FD") else Ui.LIGHT, 14, if (selected) Ui.BLUE else null, 2)
        row.setOnClickListener { onSelect(i) }
        val left = Ui.vbox(ctx)
        val time = Ui.text(ctx, Ui.minutes(r.durationSec), 20f, if (r.durationSec <= fastest + 30) Ui.GREEN_DARK else Ui.TEXT, bold = true)
        left.addView(time)
        left.addView(Ui.text(ctx, Instructions.formatDistance(r.distanceM) + (if (r.via.isNotBlank()) " · " + r.via else ""), 14f, Ui.TEXT2, maxLines = 1))
        val facts = ArrayList<String>()
        val jams = r.count(HazardType.JAM) + r.count(HazardType.SLOW_TRAFFIC)
        val delay = (r.trafficDelaySec / 60).roundToInt()
        facts.add(if (jams > 0) "🚗 $jams× Stau" + (if (delay > 0) " +$delay Min." else "") else "✅ kein Stau")
        val works = r.count(HazardType.ROADWORKS)
        if (works > 0) facts.add("🚧 $works")
        val cams = r.count(HazardType.SPEED_CAMERA)
        if (cams > 0) facts.add("📷 $cams")
        val closures = r.count(HazardType.CLOSURE)
        if (closures > 0) facts.add("⛔ $closures")
        if (r.hasToll) facts.add("Maut")
        left.addView(Ui.text(ctx, facts.joinToString("   "), 14f, if (jams > 0) Ui.RED else Ui.GREEN_DARK, maxLines = 1))
        row.addView(left, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Ui.text(ctx, r.label, 13f, Ui.BLUE_DARK, bold = true).apply {
            gravity = Gravity.END
        }, Ui.linLp(ViewGroup.LayoutParams.WRAP_CONTENT))
        return row
    }
}

/** Farbiges Warnbanner (Blitzer, Stau, Baustelle …). */
class HazardBanner(private val ctx: Context) {
    val view: TextView = Ui.text(ctx, "", 17f, Color.WHITE, bold = true, maxLines = 2)

    init {
        view.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 10), Ui.dp(ctx, 14), Ui.dp(ctx, 10))
        view.elevation = Ui.dpf(ctx, 6f)
        view.visibility = View.GONE
    }

    fun show(type: HazardType, text: String) {
        view.visibility = View.VISIBLE
        view.background = Ui.bg(ctx, Icons.hazardColor(type), 16)
        view.text = Icons.hazardEmoji(type) + "  " + text
    }

    fun hide() {
        view.visibility = View.GONE
    }
}

fun frameMatch(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
