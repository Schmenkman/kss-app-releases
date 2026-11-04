package de.sascha.kssmaps.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import de.sascha.kssmaps.core.Appointment
import de.sascha.kssmaps.core.ContactParser
import de.sascha.kssmaps.ui.Ui.margins

/**
 * Seitlich ausrollende Kunden-Leiste waehrend der Fahrt zu einem Termin: wen anrufen (aus dem
 * Terminzusatz erkannt, "zuerst anrufen" oben), alle Nummern als Knoepfe, darunter der volle
 * Terminzusatz. Die Karte rueckt zur Seite (siehe [widthPx]), damit Pfeil und Fahrweg frei bleiben.
 */
class CustomerDrawer(
    private val ctx: Context,
    /** Nummer angetippt: (Name, Anzeige, Waehlziffern) – der Besitzer fragt "Anrufen?". */
    private val onCall: (String, String, String) -> Unit,
    private val onClose: () -> Unit,
) {
    val view: FrameLayout = FrameLayout(ctx)
    private val card = Ui.vbox(ctx)
    private val content = Ui.vbox(ctx)
    private val title = Ui.text(ctx, "Kunde", 20f, Ui.TEXT, bold = true, maxLines = 2)
    private val subtitle = Ui.text(ctx, "", 14f, Ui.TEXT2, maxLines = 3)
    var isOpen = false
        private set
    /** Breite der Leiste in px (fuer den Kartenrand), 0 wenn zu. */
    val widthPx: Int get() = if (isOpen) (view.layoutParams?.width ?: 0).coerceAtLeast(0) else 0

    init {
        view.visibility = View.GONE
        card.background = Ui.bg(ctx, Color.WHITE, 20)
        card.elevation = Ui.dpf(ctx, 10f)
        card.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 10))
        val head = Ui.hbox(ctx)
        val hc = Ui.vbox(ctx)
        hc.addView(title)
        hc.addView(subtitle)
        head.addView(hc, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.iconButton(ctx, Icons.glyph(Icons.Glyph.CLOSE, Ui.dp(ctx, 20), Ui.TEXT), Ui.LIGHT, 40, "Kunden-Leiste schließen") { onClose() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = 0
            elevation = 0f
        })
        card.addView(head)
        val scroll = ScrollView(ctx)
        scroll.addView(content)
        card.addView(scroll, Ui.linLp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).margins(ctx, t = 8))
        view.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.isClickable = true
    }

    fun show(a: Appointment, ownNumbers: Collection<String>) {
        render(a, ownNumbers)
        if (!isOpen) {
            isOpen = true
            view.visibility = View.VISIBLE
            view.bringToFront()
            val w = (view.layoutParams?.width ?: Ui.dp(ctx, 300)).toFloat()
            view.translationX = w
            view.animate().translationX(0f).setDuration(220).start()
        }
    }

    fun hide() {
        if (!isOpen) return
        isOpen = false
        val w = (view.layoutParams?.width ?: Ui.dp(ctx, 300)).toFloat()
        view.animate().translationX(w).setDuration(200).withEndAction { if (!isOpen) view.visibility = View.GONE }.start()
    }

    private fun render(a: Appointment, ownNumbers: Collection<String>) {
        content.removeAllViews()
        val r = a.contacts(ownNumbers)
        title.text = a.customerName.ifBlank { "Kunde" }
        subtitle.text = listOfNotNull(
            de.sascha.kssmaps.core.Appointments.timeRange(a),
            a.address.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (r.contacts.isEmpty()) {
            content.addView(Ui.text(ctx, "Im Terminzusatz steht keine Telefonnummer.", 15f, Ui.TEXT2).apply {
                setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6))
            })
        }
        for (c in r.contacts) content.addView(contactCard(c), Ui.linLp().margins(ctx, b = 8))
        // Voller Terminzusatz – damit man selbst entscheiden kann, wen man anruft
        content.addView(Ui.text(ctx, "Terminzusatz", 14f, Ui.TEXT2, bold = true).apply {
            setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 10), 0, Ui.dp(ctx, 4))
        })
        content.addView(Ui.text(ctx, a.note.ifBlank { "–" }, 16f, Ui.TEXT).apply {
            setTextIsSelectable(true)
            setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8))
            background = Ui.bg(ctx, Ui.LIGHT, 12)
        })
        if (a.label.isNotBlank()) content.addView(Ui.text(ctx, "Auftrag ${a.label}", 13f, Ui.TEXT2).apply {
            setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 8), 0, 0)
        })
    }

    private fun contactCard(c: ContactParser.Contact): View {
        val box = Ui.vbox(ctx)
        box.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 10))
        box.background = Ui.bg(ctx, if (c.callFirst) Color.parseColor("#E8F5E9") else Ui.LIGHT, 14, if (c.callFirst) Ui.GREEN_DARK else null, if (c.callFirst) 2 else 0)
        if (c.callFirst) box.addView(Ui.text(ctx, "ZUERST ANRUFEN", 12f, Ui.GREEN_DARK, bold = true))
        box.addView(Ui.text(ctx, c.name, 18f, Ui.TEXT, bold = true, maxLines = 2))
        c.hint?.let { box.addView(Ui.text(ctx, it, 14f, Ui.ORANGE, bold = true)) }
        for (i in c.dial.indices) {
            val display = c.phones.getOrElse(i) { c.dial[i] }
            val btn = Ui.text(ctx, "☎  $display", 17f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 10), Ui.dp(ctx, 14), Ui.dp(ctx, 10))
                Ui.clickable(ctx, this, Ui.GREEN_DARK, 22)
                contentDescription = "${c.name} anrufen: $display"
                setOnClickListener { onCall(c.name, display, c.dial[i]) }
            }
            box.addView(btn, Ui.linLp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(ctx, t = 6))
        }
        return box
    }
}
