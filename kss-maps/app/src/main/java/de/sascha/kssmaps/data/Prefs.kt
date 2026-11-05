package de.sascha.kssmaps.data

import android.content.Context
import de.sascha.kssmaps.core.AvoidSection
import de.sascha.kssmaps.core.FuelType
import de.sascha.kssmaps.core.History
import de.sascha.kssmaps.core.LatLon
import de.sascha.kssmaps.core.Place
import de.sascha.kssmaps.core.RouteOptions
import de.sascha.kssmaps.core.Uta
import de.sascha.kssmaps.core.UserReport
import org.json.JSONObject

/** Alle Einstellungen und gespeicherten Listen (Verlauf, gesperrte Abschnitte, UTA-Korrekturen). */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("kss_maps", Context.MODE_PRIVATE)

    private fun str(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun putStr(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()

    // ---- API-Schluessel ----
    // Eigener Schluessel (Einstellungen) hat Vorrang, sonst der beim Bauen eingebaute (BuiltinKeys).
    var ownTomtomKey: String
        get() = str("tomtom_key").trim()
        set(v) = putStr("tomtom_key", v.trim())
    var ownTankerkoenigKey: String
        get() = str("tk_key").trim()
        set(v) = putStr("tk_key", v.trim())
    val tomtomKey: String get() = ownTomtomKey.ifBlank { BuiltinKeys.tomtom }
    val tankerkoenigKey: String get() = ownTankerkoenigKey.ifBlank { BuiltinKeys.tankerkoenig }

    // ---- Ton & Warnungen ----
    var voiceMuted: Boolean
        get() = bool("muted", false)
        set(v) = putBool("muted", v)
    /** Sprachausgabe-Programm (Paketname), leer = Standard des Systems. */
    var ttsEngine: String
        get() = str("tts_engine")
        set(v) = putStr("tts_engine", v)
    /** Gewaehlte Stimme (Name), leer = automatisch die beste deutsche. */
    var ttsVoice: String
        get() = str("tts_voice")
        set(v) = putStr("tts_voice", v)
    /**
     * Blitzer-, Abstands- und Ueberwachungswarnungen der App. Standard AUS – Blitzer.de Pro laeuft
     * parallel. Schalter in den Einstellungen und in Android Auto unter "Mehr".
     * (Neuer Schluessel, damit ein frueher gespeichertes "an" nicht uebernommen wird.)
     */
    var camerasEnabled: Boolean
        get() = bool("cameras_v2", false)
        set(v) = putBool("cameras_v2", v)
    var camerasAsked: Boolean
        get() = bool("cameras_asked", false)
        set(v) = putBool("cameras_asked", v)
    var overspeedWarning: Boolean
        get() = bool("overspeed", true)
        set(v) = putBool("overspeed", v)
    /** Toleranz in km/h, bevor die Geschwindigkeit rot wird / ein Ton kommt. */
    var overspeedTolerance: Int
        get() = int("overspeed_tol", 5)
        set(v) = putInt("overspeed_tol", v)

    // ---- Route ----
    var avoidTolls: Boolean
        get() = bool("avoid_tolls", false)
        set(v) = putBool("avoid_tolls", v)
    var avoidMotorways: Boolean
        get() = bool("avoid_motorways", false)
        set(v) = putBool("avoid_motorways", v)
    var avoidFerries: Boolean
        get() = bool("avoid_ferries", false)
        set(v) = putBool("avoid_ferries", v)
    /** Bei Stau automatisch nach schnellerer Route suchen und anbieten. */
    var trafficReroute: Boolean
        get() = bool("traffic_reroute", true)
        set(v) = putBool("traffic_reroute", v)

    fun routeOptions() = RouteOptions(avoidTolls, avoidMotorways, avoidFerries)

    // ---- Karte ----
    /** 0 = Karte, 1 = Luftbild/Satellit */
    var mapMode: Int
        get() = int("map_mode", 0)
        set(v) = putInt("map_mode", v)
    /** 0 = automatisch (Sonnenstand), 1 = Tag, 2 = Nacht */
    var nightMode: Int
        get() = int("night_mode", 0)
        set(v) = putInt("night_mode", v)
    var trafficOverlay: Boolean
        get() = bool("traffic_overlay", true)
        set(v) = putBool("traffic_overlay", v)
    var lastPosition: LatLon?
        get() {
            val la = sp.getFloat("last_lat", Float.NaN)
            val lo = sp.getFloat("last_lon", Float.NaN)
            return if (la.isNaN() || lo.isNaN()) null else LatLon(la.toDouble(), lo.toDouble())
        }
        set(v) {
            if (v == null) return
            sp.edit().putFloat("last_lat", v.lat.toFloat()).putFloat("last_lon", v.lon.toFloat()).apply()
        }

    // ---- Tanken ----
    var fuelType: FuelType
        get() = runCatching { FuelType.valueOf(str("fuel_type", FuelType.DIESEL.name)) }.getOrDefault(FuelType.DIESEL)
        set(v) = putStr("fuel_type", v.name)
    var utaOnly: Boolean
        get() = bool("uta_only", true)
        set(v) = putBool("uta_only", v)
    /** Wie weit voraus entlang der Route gesucht wird (km). */
    var fuelCorridorKm: Int
        get() = int("fuel_corridor", 50)
        set(v) = putInt("fuel_corridor", v)
    var utaBrands: List<String>
        get() {
            val s = sp.getString("uta_brands", null) ?: return Uta.DEFAULT_BRANDS
            return s.split('\n').map { it.trim() }.filter { it.isNotBlank() }
        }
        set(v) = putStr("uta_brands", v.joinToString("\n"))

    /** Eigene Erfahrung je Tankstelle: true = UTA geht, false = geht nicht. */
    fun utaOverride(stationId: String): Boolean? {
        val o = runCatching { JSONObject(str("uta_override", "{}")) }.getOrNull() ?: return null
        return if (o.has(stationId)) o.optBoolean(stationId) else null
    }

    fun setUtaOverride(stationId: String, accepted: Boolean?) {
        val o = runCatching { JSONObject(str("uta_override", "{}")) }.getOrNull() ?: JSONObject()
        if (accepted == null) o.remove(stationId) else o.put(stationId, accepted)
        putStr("uta_override", o.toString())
    }

    // ---- Verlauf (letzte 20 Ziele) ----
    var history: List<Place>
        get() = History.fromJson(sp.getString("history", null))
        set(v) = putStr("history", History.toJson(v.take(History.MAX)))

    fun addToHistory(p: Place) {
        history = History.add(history, p)
    }

    fun removeFromHistory(p: Place) {
        history = history.filterNot { it == p }
    }

    // ---- gesperrte Abschnitte ----
    var avoidSections: List<AvoidSection>
        get() = AvoidSection.listFromJson(sp.getString("avoid_sections", null))
        set(v) = putStr("avoid_sections", AvoidSection.listToJson(v))

    // ---- eigene Meldungen (mobiler Blitzer, Abstandsmessung) ----
    var userReports: List<UserReport>
        get() = UserReport.listFromJson(sp.getString("user_reports", null))
        set(v) = putStr("user_reports", UserReport.listToJson(v))

    // ---- Termine (Auftragssystem) ----
    /** Eigene Nummern (Buero, Firmenhandy) – im Terminzusatz nie als Kunde anzeigen. */
    var ownPhoneNumbersText: String
        get() = str("own_phone_numbers")
        set(v) = putStr("own_phone_numbers", v)
    val ownPhoneNumbers: List<String>
        get() = ownPhoneNumbersText.split(',', ';', '\n').map { it.trim() }.filter { it.isNotBlank() }
    /** Gewaehlter Techniker (COMP-Techniker-ID), 0 = noch keiner. */
    var appointmentTechnicianId: Int
        get() = int("appt_technician_id", 0)
        set(v) = putInt("appt_technician_id", v)
    /** Name des gewaehlten Technikers (Anzeige in "Termine heute"). */
    var appointmentTechnicianName: String
        get() = str("appt_technician_name")
        set(v) = putStr("appt_technician_name", v)

    // ---- Sonstiges ----
    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()
    var setupShown: Boolean
        get() = bool("setup_shown", false)
        set(v) = putBool("setup_shown", v)
}
