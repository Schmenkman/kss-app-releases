package de.sascha.kssmaps.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import de.sascha.kssmaps.NaviApp
import de.sascha.kssmaps.core.FuelType
import de.sascha.kssmaps.core.Uta
import de.sascha.kssmaps.ui.Ui.margins
import kotlinx.coroutines.launch

/** Einstellungen: API-Schluessel, Warnungen, Route, Karte, Tanken, Berechtigungen, Update. */
class SettingsPanel(host: PanelHost, private val onChanged: () -> Unit, private val onCheckUpdate: () -> Unit) : Panel(host, fullScreen = true) {
    private val prefs = host.engine.prefs

    init {
        titleView.text = "Einstellungen"
    }

    override fun show() {
        super.show()
        render()
    }

    override fun hide() {
        super.hide()
        onChanged()
    }

    private fun section(s: String) {
        content.addView(Ui.text(ctx, s, 15f, Ui.BLUE, bold = true).apply { setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 18), 0, Ui.dp(ctx, 4)) })
    }

    private fun note(s: String) {
        content.addView(Ui.text(ctx, s, 13f, Ui.TEXT2).apply { setPadding(Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4), Ui.dp(ctx, 6)) })
    }

    private fun toggle(label: String, value: Boolean, set: (Boolean) -> Unit) {
        val r = Ui.hbox(ctx)
        r.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4), Ui.dp(ctx, 8))
        r.addView(Ui.text(ctx, label, 16f, Ui.TEXT), Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(ctx)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, on -> set(on) }
        r.addView(sw)
        content.addView(r)
    }

    private fun choice(label: String, options: List<String>, selected: Int, set: (Int) -> Unit) {
        content.addView(Ui.text(ctx, label, 16f, Ui.TEXT).apply { setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 8), 0, Ui.dp(ctx, 4)) })
        val r = Ui.hbox(ctx)
        val views = ArrayList<TextView>()
        options.forEachIndexed { i, o ->
            val tv = Ui.text(ctx, o, 14f, if (i == selected) Color.WHITE else Ui.TEXT, bold = i == selected)
            tv.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 8), Ui.dp(ctx, 12), Ui.dp(ctx, 8))
            Ui.clickable(ctx, tv, if (i == selected) Ui.BLUE else Ui.LIGHT, 16)
            tv.setOnClickListener {
                set(i)
                views.forEachIndexed { k, v ->
                    v.setTextColor(if (k == i) Color.WHITE else Ui.TEXT)
                    Ui.clickable(ctx, v, if (k == i) Ui.BLUE else Ui.LIGHT, 16)
                }
            }
            views.add(tv)
            r.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).margins(ctx, r = 6))
        }
        content.addView(r)
    }

    private fun keyField(label: String, value: String, hint: String, test: (suspend (String) -> String)? = null, set: (String) -> Unit) {
        content.addView(Ui.text(ctx, label, 16f, Ui.TEXT).apply { setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 8), 0, Ui.dp(ctx, 4)) })
        val et = EditText(ctx)
        et.setText(value)
        et.hint = hint
        et.setSingleLine(true)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        et.background = Ui.bg(ctx, Ui.LIGHT, 12)
        et.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10))
        et.setOnFocusChangeListener { _, has -> if (!has) set(et.text.toString()) }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = set(s?.toString()?.trim() ?: "")
        })
        content.addView(et, Ui.linLp())
        // Einfuegen aus der Zwischenablage (lange Schluessel am Handy) und sofort pruefen
        val status = Ui.text(ctx, "", 14f, Ui.TEXT2)
        val row = Ui.hbox(ctx)
        row.addView(Ui.button(ctx, "Einfügen", Ui.LIGHT, Ui.TEXT) {
            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
            val clip = try {
                cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim()
            } catch (e: Exception) {
                null
            }
            if (clip.isNullOrBlank()) {
                status.text = "Die Zwischenablage ist leer – zuerst den Schlüssel kopieren."
            } else {
                et.setText(clip)
                set(clip)
                status.text = "Eingefügt (${clip.length} Zeichen)."
            }
        }, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).margins(ctx, r = 4))
        if (test != null) {
            row.addView(Ui.button(ctx, "Prüfen", Ui.BLUE) {
                val k = et.text.toString().trim()
                if (k.isBlank()) {
                    status.text = "Erst den Schlüssel eintragen."
                } else {
                    status.text = "Prüfe …"
                    host.engine.scope.launch { status.text = test(k) }
                }
            }, Ui.linLp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).margins(ctx, l = 4))
        }
        content.addView(row, Ui.linLp().margins(ctx, t = 6))
        content.addView(status, Ui.linLp().margins(ctx, t = 4))
    }

    private fun action(label: String, click: () -> Unit) {
        content.addView(Ui.button(ctx, label, Ui.LIGHT, Ui.TEXT) { click() }, Ui.linLp().margins(ctx, t = 6))
    }

    fun render() {
        content.removeAllViews()
        val e = host.engine

        section("Live-Verkehr (Stau auf allen Straßen)")
        note("Ohne Schlüssel: Routing über OpenStreetMap (Valhalla), Stau/Baustellen/Sperrungen von der Autobahn GmbH (nur Autobahnen). Mit kostenlosem TomTom-Schlüssel (developer.tomtom.com, ohne Kreditkarte): Live-Stau auf allen Straßen, in Routenwahl und Alternativen eingerechnet.")
        keyField("TomTom API-Schlüssel", prefs.tomtomKey, "optional", { k -> host.services.testTomTomKey(k) }) { prefs.tomtomKey = it }
        note("Schlüssel finden: developer.tomtom.com → Dashboard → „API & SDK keys“ → auf „My first API key“ tippen (wird kopiert) → hier „Einfügen“.")
        toggle("Verkehrsfluss auf der Karte (TomTom)", prefs.trafficOverlay) { prefs.trafficOverlay = it }
        toggle("Bei Stau schnellere Route anbieten", prefs.trafficReroute) { prefs.trafficReroute = it }

        section("Tankstellen & Preise")
        note("Preise (Diesel, Super E5/E10) kommen von der Markttransparenzstelle über Tankerkönig. Kostenloser Schlüssel: onboarding.tankerkoenig.de – ohne Schlüssel zeigt die App die Tankstellen auf dem Weg ohne Preise.")
        keyField("Tankerkönig API-Schlüssel", prefs.tankerkoenigKey, "nötig für Preise", { k -> host.services.testTankerkoenigKey(k) }) { prefs.tankerkoenigKey = it }
        note("Der Tankerkönig-Schlüssel kommt per E-Mail (nach Klick auf den Bestätigungslink), Format xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx.")
        choice("Kraftstoff (Sortierung)", FuelType.values().map { it.label }, prefs.fuelType.ordinal) { prefs.fuelType = FuelType.values()[it] }
        toggle("Nur Tankstellen mit UTA-Akzeptanz", prefs.utaOnly) { prefs.utaOnly = it }
        choice("Suchbereich entlang der Route", listOf("25 km", "50 km", "100 km"), when (prefs.fuelCorridorKm) { 25 -> 0; 100 -> 2; else -> 1 }) {
            prefs.fuelCorridorKm = when (it) { 0 -> 25; 2 -> 100; else -> 50 }
        }
        action("UTA-Marken bearbeiten") { editBrands() }

        section("Warnungen & Ton")
        toggle("Sprachansagen (Ton)", !prefs.voiceMuted) { e.setMuted(!it) }
        toggle("Blitzerwarnungen in KSS-Maps", prefs.camerasEnabled) { on ->
            e.setCamerasEnabled(on)
            render()
        }
        note(
            if (prefs.camerasEnabled) "An: feste Blitzer, Rotlicht- und Abschnittskontrollen, Abstandsmessungen (OpenStreetMap) und deine eigenen Meldungen (roter Kamera-Knopf). Hinweis: § 23 Abs. 1c StVO."
            else "Aus (Standard) – dafür Blitzer.de Pro parallel nutzen. Stau, Baustellen und Sperrungen auf der Route werden weiter angesagt. Auch in Android Auto unter „Mehr“ schaltbar."
        )
        val rc = e.reportCount
        if (prefs.camerasEnabled && rc > 0) action("Eigene Meldungen löschen ($rc)") {
            AlertDialog.Builder(ctx).setTitle("Alle eigenen Meldungen löschen?")
                .setPositiveButton("Löschen") { _, _ -> e.clearReports(); render() }
                .setNegativeButton("Abbrechen", null).show()
        }
        toggle("Warnton bei Tempoüberschreitung", prefs.overspeedWarning) { prefs.overspeedWarning = it }
        choice("Toleranz", listOf("+0", "+3", "+5", "+10 km/h"), when (prefs.overspeedTolerance) { 0 -> 0; 3 -> 1; 10 -> 3; else -> 2 }) {
            prefs.overspeedTolerance = listOf(0, 3, 5, 10)[it]
        }

        section("Route")
        toggle("Mautstraßen meiden", prefs.avoidTolls) { prefs.avoidTolls = it }
        toggle("Autobahnen meiden (komplett)", prefs.avoidMotorways) { prefs.avoidMotorways = it }
        toggle("Fähren meiden", prefs.avoidFerries) { prefs.avoidFerries = it }
        note("Einzelne Abschnitte statt ganzer Autobahnen meiden: lange auf die Straße in der Karte drücken.")

        section("Karte")
        choice("Ansicht", listOf("Karte", "Luftbild"), prefs.mapMode) { prefs.mapMode = it }
        choice("Tag/Nacht", listOf("Automatisch", "Tag", "Nacht"), prefs.nightMode) { prefs.nightMode = it }

        section("Hintergrund & Android Auto")
        note("Damit Navigation und Ansagen bei gesperrtem Handy bzw. nur über Android Auto zuverlässig laufen: Standort \"Immer zulassen\" und Akku-Optimierung für KSS-Maps ausschalten.")
        action("Standort-Berechtigung öffnen") {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
        }
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            action("Akku-Optimierung ausschalten") {
                try {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
                } catch (ex: Exception) {
                    ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
        note("Android Auto zeigt selbst installierte Apps nur, wenn Android sie als „aus dem Play Store installiert“ führt. Dafür KSS-Maps mit KingInstaller oder UnknownAuto installieren (kostenlos, siehe Anleitung im Projekt) und in den Android-Auto-Entwicklereinstellungen „Unbekannte Quellen“ aktivieren (10× auf „Version“ tippen → ⋮ → Entwicklereinstellungen).")

        section("Daten")
        action("Blitzer-Daten jetzt aktualisieren") {
            host.toast("Blitzer und Abstandsmessungen werden geladen …")
            e.scope.launch {
                val list = host.services.cameras(forceRefresh = true)
                val cams = list.count { it.type == de.sascha.kssmaps.core.HazardType.SPEED_CAMERA }
                val dist = list.count { it.type == de.sascha.kssmaps.core.HazardType.DISTANCE_CHECK }
                host.toast("$cams Blitzer und $dist Abstandsmessungen/-zonen geladen")
            }
        }
        action("Nach App-Update suchen") { onCheckUpdate() }
        note("Version ${NaviApp.versionName} (Android ${Build.VERSION.RELEASE})")
        note("Quellen: Karte © OpenStreetMap-Mitwirkende, OpenFreeMap · Luftbild © Bayerische Vermessungsverwaltung (CC BY 4.0), Esri · Routing Valhalla/FOSSGIS bzw. TomTom · Verkehr Autobahn GmbH des Bundes · Suche Photon/Nominatim · Preise Tankerkönig/MTS-K (CC BY 4.0) · Blitzer OpenStreetMap (ODbL).")
    }

    private fun editBrands() {
        val et = EditText(ctx)
        et.setText(prefs.utaBrands.joinToString("\n"))
        et.minLines = 8
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        AlertDialog.Builder(ctx).setTitle("Marken mit UTA-Akzeptanz (eine pro Zeile)").setView(et)
            .setPositiveButton("Speichern") { _, _ -> prefs.utaBrands = et.text.toString().split('\n').map { it.trim() }.filter { it.isNotBlank() } }
            .setNeutralButton("Standard") { _, _ -> prefs.utaBrands = Uta.DEFAULT_BRANDS }
            .setNegativeButton("Abbrechen", null).show()
    }
}
