package de.sascha.kssmaps.core

import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------------------------
// Orte / Ziele
// ---------------------------------------------------------------------------------------------

data class Place(
    /** Erste Zeile, z. B. "Hauptstraße 12" oder "Aral Tankstelle". */
    val name: String,
    /** Zweite Zeile, z. B. "85354 Freising". */
    val address: String,
    val lat: Double,
    val lon: Double,
    val street: String? = null,
    val houseNumber: String? = null,
    val postcode: String? = null,
    val city: String? = null,
    val osmType: String? = null,
    val osmId: Long? = null,
    val category: String? = null,
) {
    val pos: LatLon get() = LatLon(lat, lon)

    fun label(): String = listOf(name, address).filter { it.isNotBlank() }.joinToString(", ")

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("address", address).put("lat", lat).put("lon", lon)
        .putOpt("street", street).putOpt("houseNumber", houseNumber).putOpt("postcode", postcode)
        .putOpt("city", city).putOpt("osmType", osmType).putOpt("osmId", osmId).putOpt("category", category)

    companion object {
        fun fromJson(o: JSONObject) = Place(
            name = o.optString("name"),
            address = o.optString("address"),
            lat = o.optDouble("lat"),
            lon = o.optDouble("lon"),
            street = o.optStr("street"),
            houseNumber = o.optStr("houseNumber"),
            postcode = o.optStr("postcode"),
            city = o.optStr("city"),
            osmType = o.optStr("osmType"),
            osmId = if (o.has("osmId")) o.optLong("osmId") else null,
            category = o.optStr("category"),
        )
    }
}

/** Die letzten Ziele, neuestes zuerst, ohne Dubletten (gleiches Ziel = gleiche Bezeichnung oder < 30 m). */
object History {
    const val MAX = 20

    fun add(list: List<Place>, p: Place): List<Place> {
        val rest = list.filterNot { it.label() == p.label() || Geo.distance(it.pos, p.pos) < 30.0 }
        return (listOf(p) + rest).take(MAX)
    }

    fun toJson(list: List<Place>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

    fun fromJson(s: String?): List<Place> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Place::fromJson) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Routen
// ---------------------------------------------------------------------------------------------

enum class ManeuverKind {
    DEPART, ARRIVE, ARRIVE_LEFT, ARRIVE_RIGHT, WAYPOINT,
    STRAIGHT, CONTINUE, NAME_CHANGE,
    SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, UTURN,
    KEEP_RIGHT, KEEP_LEFT, RAMP_RIGHT, RAMP_LEFT, RAMP_STRAIGHT, EXIT_RIGHT, EXIT_LEFT,
    MERGE, MERGE_LEFT, MERGE_RIGHT, ROUNDABOUT, ROUNDABOUT_EXIT, FERRY;

    /** Manoever, die angesagt und im Banner als "naechster Schritt" gezeigt werden. */
    val significant: Boolean
        get() = this != DEPART && this != CONTINUE && this != NAME_CHANGE && this != ROUNDABOUT_EXIT

    val isArrival: Boolean get() = this == ARRIVE || this == ARRIVE_LEFT || this == ARRIVE_RIGHT
}

/** Fahrspur (Valhalla-Bitmaske: 1 none, 2 through, 4 sharp left, 8 left, 16 slight left, 32 slight right, 64 right, 128 sharp right, 256 reverse). */
data class Lane(val directions: Int, val active: Int, val valid: Int)

data class Maneuver(
    val kind: ManeuverKind,
    /** Meter vom Routenanfang bis zum Manoever. */
    val along: Double,
    val point: LatLon,
    /** Zielstrasse (Name oder Nummer, z. B. "Hauptstraße" oder "A 9"). */
    val street: String? = null,
    val exitNumber: String? = null,
    val signBranch: String? = null,
    val signToward: String? = null,
    val roundaboutExit: Int = 0,
    val providerText: String? = null,
    val legIndex: Int = 0,
    val lanes: List<Lane> = emptyList(),
    /** Abbiegewinkel in Grad (-180..180, positiv rechts), falls bekannt – fuer Pfeilsymbole. */
    val turnAngle: Double? = null,
)

data class SpeedLimitRange(val from: Double, val to: Double, val kmh: Int) {
    companion object {
        const val UNLIMITED = -1
    }
}

/** Was das Tempolimit-Schild zeigt: Zahl (oder [SpeedLimitRange.UNLIMITED]); [variable] = Wechselverkehrszeichen-Strecke. */
data class SpeedSign(val kmh: Int?, val variable: Boolean = false)

enum class RouteSource { VALHALLA, TOMTOM }

class Route(
    val source: RouteSource,
    val geometry: Polyline,
    val distanceM: Double,
    val maneuvers: List<Maneuver>,
    /** along am Ende jedes Abschnitts (Zwischenziele erzeugen mehrere Abschnitte). */
    val legEnds: List<Double>,
    /** (along, Sekunden seit Start) – stueckweise linear, steigend; enthaelt bei TomTom den Verkehr schon. */
    val timeProfile: List<Pair<Double, Double>>,
    val roadRefs: Set<String>,
    val hasToll: Boolean,
    val hasHighway: Boolean,
    val hasFerry: Boolean,
    /** Kurzbeschreibung "über A 9, B 13". */
    val via: String,
    /** Verkehrsmeldungen, die der Router selbst geliefert hat (TomTom). */
    val providerHazards: List<RouteHazard> = emptyList(),
    /** Tempolimits laut Router (TomTom), fuellen Luecken der OpenStreetMap-Daten. */
    val providerSpeedLimits: List<SpeedLimitRange> = emptyList(),
    /** Verzoegerung laut Router (TomTom), schon in timeProfile enthalten. */
    val providerDelaySec: Double = 0.0,
) {
    /** Alle auf der Route liegenden Meldungen (Blitzer, Stau, Baustellen …), nach along sortiert. */
    var hazards: List<RouteHazard> = providerHazards
    var speedLimits: List<SpeedLimitRange> = emptyList()
    var label: String = ""
    /** Zusaetzliche Stauzeit, die NICHT in timeProfile steckt (Valhalla + Autobahn-API). */
    var extraDelays: List<Pair<Double, Double>> = emptyList()

    val baseDurationSec: Double get() = timeProfile.lastOrNull()?.second ?: 0.0
    val durationSec: Double get() = baseDurationSec + extraDelays.sumOf { it.second }
    val trafficDelaySec: Double get() = providerDelaySec + extraDelays.sumOf { it.second }

    fun timeAt(along: Double): Double {
        val tp = timeProfile
        if (tp.isEmpty()) return 0.0
        if (along <= tp[0].first) return tp[0].second
        for (i in 1 until tp.size) {
            val (a1, t1) = tp[i]
            if (along <= a1) {
                val (a0, t0) = tp[i - 1]
                val f = if (a1 > a0) (along - a0) / (a1 - a0) else 1.0
                return t0 + f * (t1 - t0)
            }
        }
        return tp.last().second
    }

    fun remainingSec(along: Double): Double =
        (baseDurationSec - timeAt(along)).coerceAtLeast(0.0) + extraDelays.filter { it.first >= along }.sumOf { it.second }

    /** Tempolimit an der Stelle along; null = unbekannt, -1 = unbegrenzt. */
    /** Tempolimit an der Stelle: OpenStreetMap (beschildert), sonst TomTom (auch unbeschildert, z. B. 100 ausserorts). */
    fun speedLimitAt(along: Double): Int? = limitIn(speedLimits, along) ?: limitIn(providerSpeedLimits, along)

    private fun limitIn(l: List<SpeedLimitRange>, along: Double): Int? {
        if (l.isEmpty()) return null
        var lo = 0
        var hi = l.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = l[mid]
            when {
                along < r.from -> hi = mid - 1
                along > r.to -> lo = mid + 1
                else -> return if (r.kmh == 0) null else r.kmh
            }
        }
        return null
    }

    fun legIndexAt(along: Double): Int {
        for ((i, e) in legEnds.withIndex()) if (along < e - 1.0) return i
        return (legEnds.size - 1).coerceAtLeast(0)
    }

    fun count(type: HazardType): Int = hazards.count { it.hazard.type == type }
}

data class RouteOptions(
    val avoidTolls: Boolean = false,
    val avoidMotorways: Boolean = false,
    val avoidFerries: Boolean = false,
)

// ---------------------------------------------------------------------------------------------
// Meldungen auf der Strecke
// ---------------------------------------------------------------------------------------------

enum class HazardType(val label: String) {
    SPEED_CAMERA("Blitzer"),
    /** Abstandsmessung / Abstandskontrolle (inkl. Mindestabstand-Zonen). */
    DISTANCE_CHECK("Abstandsmessung"),
    JAM("Stau"),
    SLOW_TRAFFIC("Stockender Verkehr"),
    ROADWORKS("Baustelle"),
    CLOSURE("Sperrung"),
    ACCIDENT("Unfall"),
    DANGER("Gefahr"),
    OTHER("Hinweis");

    val isTraffic: Boolean get() = this == JAM || this == SLOW_TRAFFIC

    /** Verkehrsueberwachung (Blitzer, Abstand) – laesst sich in den Einstellungen abschalten. */
    val isEnforcement: Boolean get() = this == SPEED_CAMERA || this == DISTANCE_CHECK
}

data class Hazard(
    val id: String,
    val type: HazardType,
    val title: String,
    val description: String,
    val point: LatLon,
    val geometry: List<LatLon> = emptyList(),
    /** Verzoegerung in Sekunden (Stau). */
    val delaySec: Int = 0,
    /** Durchschnittsgeschwindigkeit im Stau. */
    val speedKmh: Int? = null,
    /** Erlaubte Hoechstgeschwindigkeit am Blitzer, falls bekannt. */
    val maxSpeed: Int? = null,
    val source: String,
    val roadRef: String? = null,
    /** Fahrtrichtung, fuer die die Meldung gilt (Grad), falls bekannt. */
    val directionDeg: Double? = null,
)

data class RouteHazard(val hazard: Hazard, val startAlong: Double, val endAlong: Double)

/** Ein vom Nutzer gesperrter Abschnitt. points = Sperrpunkte (je Fahrbahn einer), shapes = Anzeige. */
data class AvoidSection(
    val id: String,
    val name: String,
    val points: List<LatLon>,
    val shapes: List<List<LatLon>>,
    val enabled: Boolean = true,
    val created: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("enabled", enabled).put("created", created)
        .put("points", JSONArray().apply { points.forEach { put(JSONArray().put(it.lat).put(it.lon)) } })
        .put("shapes", JSONArray().apply { shapes.forEach { put(Geo.encodePolyline(it, 6)) } })

    companion object {
        fun fromJson(o: JSONObject): AvoidSection {
            val pts = o.optJSONArray("points")
            val shapes = o.optJSONArray("shapes")
            return AvoidSection(
                id = o.optString("id"),
                name = o.optString("name"),
                points = (0 until (pts?.length() ?: 0)).mapNotNull { i ->
                    pts?.optJSONArray(i)?.let { LatLon(it.optDouble(0), it.optDouble(1)) }
                },
                shapes = (0 until (shapes?.length() ?: 0)).map { i -> Geo.decodePolyline(shapes!!.optString(i), 6) },
                enabled = o.optBoolean("enabled", true),
                created = o.optLong("created"),
            )
        }

        fun listToJson(l: List<AvoidSection>): String = JSONArray().apply { l.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(s: String?): List<AvoidSection> {
            if (s.isNullOrBlank()) return emptyList()
            return try {
                val a = JSONArray(s)
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fromJson) }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Tankstellen
// ---------------------------------------------------------------------------------------------

enum class FuelType(val label: String) { DIESEL("Diesel"), E5("Super E5"), E10("Super E10") }

enum class UtaStatus { YES, NO, UNKNOWN }

data class FuelStation(
    val id: String,
    val name: String,
    val brand: String,
    val street: String,
    val houseNumber: String,
    val postCode: String,
    val place: String,
    val lat: Double,
    val lon: Double,
    val diesel: Double?,
    val e5: Double?,
    val e10: Double?,
    val isOpen: Boolean,
    val uta: UtaStatus = UtaStatus.UNKNOWN,
) {
    val pos: LatLon get() = LatLon(lat, lon)

    fun price(t: FuelType): Double? = when (t) {
        FuelType.DIESEL -> diesel
        FuelType.E5 -> e5
        FuelType.E10 -> e10
    }

    fun displayName(): String = brand.ifBlank { name }.trim().ifBlank { "Tankstelle" }

    fun addressLine(): String = listOf("$street $houseNumber".trim(), "$postCode $place".trim()).filter { it.isNotBlank() }.joinToString(", ")

    fun toPlace(): Place = Place(
        name = displayName(), address = addressLine(), lat = lat, lon = lon,
        street = street.ifBlank { null }, houseNumber = houseNumber.ifBlank { null },
        postcode = postCode.ifBlank { null }, city = place.ifBlank { null }, category = "fuel",
    )
}

// ---------------------------------------------------------------------------------------------
// kleine JSON-Helfer (org.json gibt es auf Android eingebaut und auf der JVM als Bibliothek)
// ---------------------------------------------------------------------------------------------

fun JSONObject.optStr(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val s = optString(key, "")
    return s.ifBlank { null }
}

fun JSONObject.optNum(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toDouble()
        is String -> v.replace(',', '.').toDoubleOrNull()
        else -> null
    }
}

fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>()
    for (i in 0 until length()) {
        val v = opt(i)
        if (v is String && v.isNotBlank()) out.add(v)
    }
    return out
}


// ---------------------------------------------------------------------------------------------
// Eigene Meldungen (mobiler Blitzer, Abstandsmessung) – es gibt dafuer keine freie Datenquelle
// ---------------------------------------------------------------------------------------------

data class UserReport(
    val id: String,
    val type: HazardType,
    val lat: Double,
    val lon: Double,
    /** Fahrtrichtung beim Melden (Grad), damit nur in dieser Richtung gewarnt wird. */
    val heading: Double?,
    val created: Long,
    /** 0 = dauerhaft (z. B. bekannter Messplatz), sonst Ablaufzeit in ms seit 1970. */
    val expires: Long,
    val note: String = "",
) {
    val permanent: Boolean get() = expires == 0L

    fun isActive(now: Long): Boolean = permanent || now < expires

    fun toHazard(): Hazard = Hazard(
        id = "user-$id",
        type = type,
        title = if (type == HazardType.DISTANCE_CHECK) "Abstandsmessung" else "Blitzer",
        description = (if (permanent) "Eigener Messplatz" else if (type == HazardType.DISTANCE_CHECK) "Gemeldete Abstandsmessung" else "Gemeldeter mobiler Blitzer") +
            (if (note.isNotBlank()) " · $note" else ""),
        point = LatLon(lat, lon),
        source = "user",
        directionDeg = heading,
    )

    fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type.name).put("lat", lat).put("lon", lon)
        .putOpt("heading", heading).put("created", created).put("expires", expires).put("note", note)

    companion object {
        /** Mobile Messungen stehen selten laenger als ein paar Stunden. */
        const val TEMP_DURATION_MS = 4L * 3600 * 1000

        fun fromJson(o: JSONObject): UserReport? {
            val type = runCatching { HazardType.valueOf(o.optString("type")) }.getOrNull() ?: return null
            return UserReport(
                id = o.optString("id"), type = type, lat = o.optDouble("lat"), lon = o.optDouble("lon"),
                heading = o.optNum("heading"), created = o.optLong("created"), expires = o.optLong("expires"),
                note = o.optString("note", ""),
            )
        }

        fun listToJson(l: List<UserReport>): String = JSONArray().apply { l.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(s: String?): List<UserReport> {
            if (s.isNullOrBlank()) return emptyList()
            return try {
                val a = JSONArray(s)
                (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::fromJson) }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
