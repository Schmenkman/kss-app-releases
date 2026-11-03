package de.sascha.kssmaps.core

import org.json.JSONObject
import java.util.Locale

/**
 * Messstellen der bayerischen Polizei fuer Geschwindigkeitskontrollen (amtliche Listen des
 * Innenministeriums zu den Blitzmarathons, verortet ueber OpenStreetMap). Dort wird auch sonst
 * regelmaessig mobil gemessen – KSS-Maps warnt deshalb als "Mobile Messstelle".
 * Datei: assets/messstellen/bayern.json (erzeugt von tools/fetch_messstellen.py).
 */
object Messstellen {
    const val TITLE = "Mobile Messstelle"

    fun parse(json: String): List<Hazard> {
        val arr = JSONObject(json).optJSONArray("messstellen") ?: return emptyList()
        val out = ArrayList<Hazard>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val street = o.optString("street")
            val place = listOf(o.optString("plz"), o.optString("place")).filter { it.isNotBlank() }.joinToString(" ")
            val years = o.optJSONArray("years")?.let { a -> (0 until a.length()).map { a.optInt(it) }.filter { it > 0 } } ?: emptyList()
            val desc = buildString {
                append("Messstelle der Polizei")
                if (street.isNotBlank()) append(": ").append(street)
                if (place.isNotBlank()) append(", ").append(place)
                if (years.isNotEmpty()) append(" (Blitzmarathon ").append(years.joinToString(", ")).append(")")
            }
            out.add(
                Hazard(
                    id = String.format(Locale.ROOT, "ms-%.5f-%.5f", lat, lon),
                    type = HazardType.SPEED_CAMERA,
                    title = TITLE,
                    description = desc,
                    point = LatLon(lat, lon),
                    source = "stmi",
                )
            )
        }
        return out.distinctBy { it.id }
    }
}
