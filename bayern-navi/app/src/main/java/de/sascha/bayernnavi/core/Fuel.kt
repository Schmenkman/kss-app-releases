package de.sascha.bayernnavi.core

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * UTA-Akzeptanz. Es gibt keine offene Liste der UTA-Akzeptanzstellen; laut UTA und
 * Vergleichsportalen wird die Karte in Deutschland an praktisch allen Markentankstellen
 * angenommen (Aral, Shell, Esso, TotalEnergies, Jet, Avia, HEM …). Daher:
 *  - Marke in der Liste         -> JA
 *  - OSM-Tag payment:uta=yes/no -> uebersteuert die Marke
 *  - eigene Erfahrung (Nutzer)  -> uebersteuert alles
 *  - sonst                      -> UNBEKANNT
 * Die Markenliste ist in den Einstellungen aenderbar.
 */
object Uta {
    val DEFAULT_BRANDS: List<String> = listOf(
        "Aral", "BP", "Shell", "Esso", "TotalEnergies", "Total", "JET", "Agip", "Eni", "Avia", "OMV",
        "Orlen", "Star", "HEM", "Tamoil", "Westfalen", "Q1", "Team", "Sprint", "RAN", "Calpam",
        "Classic", "Hoyer", "Turmöl", "bft", "Raiffeisen", "OIL!", "Score", "Nordoel", "Gulf",
        "BayWa", "Allguth", "Jet Tankstelle", "AVIA Xpress", "Tankpool24", "Pinoil", "Markant",
    )

    fun normalize(s: String): String = s.lowercase(Locale.GERMANY)
        .replace("ö", "oe").replace("ü", "ue").replace("ä", "ae").replace("ß", "ss")
        .replace(Regex("[^a-z0-9! ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Wortgenauer Vergleich, damit "Star" nicht in "Start" und "BP" nicht in "BPX" passt. */
    fun brandAccepted(brand: String, name: String, accepted: Collection<String>): Boolean {
        val words = (normalize(brand) + " " + normalize(name)).split(' ').filter { it.isNotBlank() }
        val joined = " " + words.joinToString(" ") + " "
        for (a in accepted) {
            val n = normalize(a)
            if (n.isBlank()) continue
            if (joined.contains(" $n ")) return true
        }
        return false
    }

    fun status(brand: String, name: String, accepted: Collection<String>, osmTag: Boolean?, userOverride: Boolean?): UtaStatus = when {
        userOverride != null -> if (userOverride) UtaStatus.YES else UtaStatus.NO
        osmTag != null -> if (osmTag) UtaStatus.YES else UtaStatus.NO
        brandAccepted(brand, name, accepted) -> UtaStatus.YES
        else -> UtaStatus.UNKNOWN
    }
}

data class RankedStation(
    val station: FuelStation,
    /** Lage entlang der Route (null ohne Route). */
    val along: Double?,
    /** Geschaetzter Umweg in Metern (hin und zurueck). */
    val detourM: Double,
    val onTheWay: Boolean,
    /** Luftlinie von hier. */
    val distFromHereM: Double,
    /** Strecke bis zur Tankstelle (entlang der Route, sonst Luftlinie * 1,3). */
    val distToStationM: Double,
    val price: Double?,
)

object FuelRanking {
    /** Bis zu diesem geschaetzten Umweg gilt eine Tankstelle als "auf dem Weg". */
    const val ON_WAY_DETOUR_M = 1800.0

    /**
     * Reihenfolge wie gewuenscht: zuerst die Tankstellen auf dem Weg zum Ziel, dann der Preis.
     * Innerhalb "auf dem Weg" entscheidet der Preis, bei gleichem Preis die naehere.
     */
    fun rank(
        stations: List<FuelStation>,
        route: Polyline?,
        currentAlong: Double,
        here: LatLon,
        fuel: FuelType,
        utaOnly: Boolean,
        maxAheadM: Double = 60_000.0,
    ): List<RankedStation> {
        val seen = HashSet<String>()
        val out = ArrayList<RankedStation>()
        for (s in stations) {
            if (!seen.add(s.id)) continue
            if (!s.isOpen) continue
            if (utaOnly && s.uta != UtaStatus.YES) continue
            val price = s.price(fuel)?.takeIf { it > 0.3 }
            val fromHere = Geo.distance(here, s.pos)
            if (route != null && route.size >= 2) {
                val pr = route.projectNear(s.pos, 3000.0) ?: route.project(s.pos) ?: continue
                val ahead = pr.along >= currentAlong - 80.0
                if (ahead && pr.along - currentAlong > maxAheadM) continue
                // Weg von der Route zur Tankstelle und zurueck, Strassen sind ~30 % laenger als Luftlinie.
                val detour = if (ahead) pr.offset * 2.0 * 1.3 + 250.0 else (currentAlong - pr.along) * 2.0 + pr.offset * 2.6
                out.add(
                    RankedStation(
                        station = s, along = pr.along, detourM = detour,
                        onTheWay = ahead && detour <= ON_WAY_DETOUR_M,
                        distFromHereM = fromHere,
                        distToStationM = if (ahead) (pr.along - currentAlong) + pr.offset * 1.3 else fromHere * 1.3,
                        price = price,
                    )
                )
            } else {
                out.add(RankedStation(s, null, fromHere * 1.3, false, fromHere, fromHere * 1.3, price))
            }
        }
        return out.sortedWith(
            compareBy<RankedStation>(
                { !it.onTheWay },
                { it.price == null },
                { it.price ?: 99.0 },
                { if (it.onTheWay) it.distToStationM else it.detourM },
            )
        )
    }
}

/** Tankerkoenig-API (Daten der Markttransparenzstelle, CC BY 4.0). Kostenloser API-Key noetig. */
object Tankerkoenig {
    const val BASE = "https://creativecommons.tankerkoenig.de/json"

    fun listUrl(p: LatLon, radiusKm: Double, apiKey: String): String = String.format(
        Locale.ROOT, "%s/list.php?lat=%.5f&lng=%.5f&rad=%.1f&sort=dist&type=all&apikey=%s",
        BASE, p.lat, p.lon, radiusKm.coerceIn(1.0, 25.0), URLEncoder.encode(apiKey.trim(), "UTF-8")
    )

    class ApiError(msg: String) : Exception(msg)

    fun parseList(json: String): List<FuelStation> {
        val root = JSONObject(json)
        if (!root.optBoolean("ok", false)) throw ApiError(root.optString("message", "Tankerkönig-Fehler"))
        val arr = root.optJSONArray("stations") ?: return emptyList()
        val out = ArrayList<FuelStation>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optNum("lat") ?: continue
            val lon = o.optNum("lng") ?: continue
            out.add(
                FuelStation(
                    id = o.optString("id"),
                    name = o.optString("name").trim(),
                    brand = o.optString("brand").trim(),
                    street = titleCase(o.optString("street")),
                    houseNumber = o.optStr("houseNumber") ?: "",
                    postCode = o.opt("postCode")?.toString()?.let { if (it == "null") "" else it.padStart(5, '0') } ?: "",
                    place = titleCase(o.optString("place")),
                    lat = lat, lon = lon,
                    diesel = price(o, "diesel"), e5 = price(o, "e5"), e10 = price(o, "e10"),
                    isOpen = o.optBoolean("isOpen", true),
                )
            )
        }
        return out
    }

    private fun price(o: JSONObject, key: String): Double? {
        val v = o.opt(key)
        return if (v is Number && v.toDouble() > 0.0) v.toDouble() else null
    }

    /** Tankerkoenig liefert Strassen/Orte oft in GROSSBUCHSTABEN. */
    fun titleCase(s: String): String {
        if (s.isBlank()) return ""
        if (s != s.uppercase(Locale.GERMANY)) return s.trim()
        return s.trim().lowercase(Locale.GERMANY).split(' ').joinToString(" ") { w ->
            w.split('-').joinToString("-") { p -> p.replaceFirstChar { it.titlecase(Locale.GERMANY) } }
        }
    }

    /**
     * Suchpunkte entlang der restlichen Route: alle stepM ein Kreis. Tankerkoenig kann nur
     * Umkreissuche (max. 25 km), eine Korridorsuche wird daraus zusammengesetzt.
     */
    fun corridorSamples(route: Polyline, fromAlong: Double, lengthM: Double, stepM: Double): List<LatLon> {
        val out = ArrayList<LatLon>()
        val end = minOf(route.length, fromAlong + lengthM)
        var a = fromAlong.coerceAtLeast(0.0)
        while (a <= end) {
            out.add(route.pointAt(a))
            a += stepM
        }
        if (out.isEmpty() || Geo.distance(out.last(), route.pointAt(end)) > stepM / 4) out.add(route.pointAt(end))
        return out
    }

    fun formatPrice(p: Double?): String = if (p == null) "–" else String.format(Locale.GERMANY, "%.3f €", p)
}
