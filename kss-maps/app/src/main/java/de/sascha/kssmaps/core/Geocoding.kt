package de.sascha.kssmaps.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** Adresssuche ueber Photon (komoot, OSM-Daten) mit Nominatim als Rueckfallebene. */
object Geocoding {
    const val PHOTON = "https://photon.komoot.io"
    const val NOMINATIM = "https://nominatim.openstreetmap.org"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun photonSearchUrl(q: String, bias: LatLon?, limit: Int = 10): String {
        val sb = StringBuilder("$PHOTON/api/?q=").append(enc(q)).append("&lang=de&limit=").append(limit)
        if (bias != null) sb.append(String.format(Locale.ROOT, "&lat=%.5f&lon=%.5f&location_bias_scale=0.4", bias.lat, bias.lon))
        return sb.toString()
    }

    fun photonReverseUrl(p: LatLon): String = String.format(Locale.ROOT, "%s/reverse?lat=%.6f&lon=%.6f&lang=de&limit=1", PHOTON, p.lat, p.lon)

    fun nominatimSearchUrl(q: String): String =
        "$NOMINATIM/search?q=${enc(q)}&format=jsonv2&addressdetails=1&limit=10&accept-language=de&countrycodes=de,at,ch,cz"

    fun parsePhoton(json: String): List<Place> {
        val feats = JSONObject(json).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until feats.length()) {
            val f = feats.optJSONObject(i) ?: continue
            val c = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val lon = c.optDouble(0, Double.NaN)
            val lat = c.optDouble(1, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val p = f.optJSONObject("properties") ?: JSONObject()
            val street = p.optStr("street")
            val hn = p.optStr("housenumber")
            val name = p.optStr("name")
            val city = p.optStr("city") ?: p.optStr("town") ?: p.optStr("village") ?: p.optStr("locality") ?: p.optStr("district")
            val postcode = p.optStr("postcode")
            val type = p.optStr("type") ?: p.optStr("osm_value")
            val osmType = when (p.optStr("osm_type")) { "N" -> "node"; "W" -> "way"; "R" -> "relation"; else -> null }
            val title: String
            val sub = ArrayList<String>()
            if (name != null && (street == null || name != street)) {
                title = name
                if (street != null) sub.add(listOfNotNull(street, hn).joinToString(" "))
            } else if (street != null) {
                title = listOfNotNull(street, hn).joinToString(" ")
            } else {
                title = name ?: city ?: "Ort"
            }
            val cityLine = listOfNotNull(postcode, city).joinToString(" ")
            if (cityLine.isNotBlank() && cityLine != title) sub.add(cityLine)
            if (city == null) p.optStr("state")?.let { sub.add(it) }
            val country = p.optStr("countrycode")
            if (country != null && country != "DE") p.optStr("country")?.let { sub.add(it) }
            out.add(
                Place(
                    name = title, address = sub.joinToString(", "), lat = lat, lon = lon,
                    street = street, houseNumber = hn, postcode = postcode, city = city,
                    osmType = osmType, osmId = if (p.has("osm_id")) p.optLong("osm_id") else null, category = type,
                )
            )
        }
        return out
    }

    fun parseNominatim(json: String): List<Place> {
        val arr = JSONArray(json)
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optNum("lat") ?: continue
            val lon = o.optNum("lon") ?: continue
            val a = o.optJSONObject("address") ?: JSONObject()
            val street = a.optStr("road") ?: a.optStr("pedestrian") ?: a.optStr("footway")
            val hn = a.optStr("house_number")
            val city = a.optStr("city") ?: a.optStr("town") ?: a.optStr("village") ?: a.optStr("municipality")
            val postcode = a.optStr("postcode")
            val name = o.optStr("name")
            val title = when {
                name != null && name != street -> name
                street != null -> listOfNotNull(street, hn).joinToString(" ")
                else -> o.optString("display_name").substringBefore(",")
            }
            val sub = ArrayList<String>()
            if (name != null && name != street && street != null) sub.add(listOfNotNull(street, hn).joinToString(" "))
            listOfNotNull(postcode, city).joinToString(" ").takeIf { it.isNotBlank() }?.let { sub.add(it) }
            out.add(
                Place(
                    name = title, address = sub.joinToString(", "), lat = lat, lon = lon,
                    street = street, houseNumber = hn, postcode = postcode, city = city,
                    osmType = o.optStr("osm_type"), osmId = if (o.has("osm_id")) o.optLong("osm_id") else null,
                    category = o.optStr("type"),
                )
            )
        }
        return out
    }

    /**
     * Liest Koordinaten aus Texten wie "48.137, 11.575" oder geo:-Links.
     * So kann man auch Koordinaten direkt eingeben.
     */
    fun parseCoordinates(s: String): LatLon? {
        val m = Regex("(-?\\d{1,2}[.,]\\d+)\\s*[,; ]\\s*(-?\\d{1,3}[.,]\\d+)").find(s) ?: return null
        val lat = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return LatLon(lat, lon)
    }

    /** geo:48.1,11.5?q=Adresse  ->  Koordinate und/oder Suchtext. */
    fun parseGeoUri(uri: String): Pair<LatLon?, String?> {
        val body = uri.removePrefix("geo:")
        val coordPart = body.substringBefore("?")
        val query = body.substringAfter("?", "").split("&").firstOrNull { it.startsWith("q=") }?.removePrefix("q=")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        var p = parseCoordinates(coordPart.replace(",", ", "))
        if (p != null && p.lat == 0.0 && p.lon == 0.0) p = null
        val qCoord = query?.let { parseCoordinates(it.substringBefore("(")) }
        return Pair(p ?: qCoord, query?.takeIf { qCoord == null })
    }
}
