package de.sascha.kssmaps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeocodingTomTomTest {
    @Test
    fun houseNumberFromQuery() {
        assertEquals("12", Geocoding.houseNumberIn("Am Anger 12, Freising"))
        assertEquals("12a", Geocoding.houseNumberIn("Am Anger 12a"))
        assertEquals("12b", Geocoding.houseNumberIn("Am Anger 12 b 85354 Freising"))
        assertEquals("7", Geocoding.houseNumberIn("85354 Freising, Lindenweg 7"))
        assertNull(Geocoding.houseNumberIn("Lindenweg Freising"))
        assertNull(Geocoding.houseNumberIn("85354 Freising"))
        // "Am Anger 3 Freising": der Buchstabe gehoert zum naechsten Wort, nicht zur Nummer
        assertEquals("3", Geocoding.houseNumberIn("Am Anger 3 Freising"))
    }

    private val json = """
        {"results":[
          {"type":"Point Address","address":{"streetName":"Lindenweg","streetNumber":"7","postalCode":"85354","municipality":"Freising","countryCode":"DE","country":"Deutschland"},
           "position":{"lat":48.4001,"lon":11.7402},
           "entryPoints":[{"type":"main","position":{"lat":48.4003,"lon":11.7405}}]},
          {"type":"Street","address":{"streetName":"Lindenweg","postalCode":"85354","municipality":"Freising","countryCode":"DE"},
           "position":{"lat":48.4010,"lon":11.7410}}
        ]}
    """.trimIndent()

    @Test
    fun parsesTomTomAndUsesEntryPoint() {
        val r = Geocoding.parseTomTomGeocode(json)
        assertEquals(2, r.size)
        assertEquals("Lindenweg 7", r[0].name)
        assertEquals("85354 Freising", r[0].address)
        assertEquals(48.4003, r[0].lat, 1e-9)
        assertEquals("7", r[0].houseNumber)
        assertEquals("tomtom:Point Address", r[0].category)
        assertEquals("Lindenweg", r[1].name)
    }

    @Test
    fun newHouseNumberFromTomTomComesFirst() {
        val osmStreet = Place("Lindenweg", "85354 Freising", 48.4011, 11.7411, street = "Lindenweg", city = "Freising")
        val osmOther = Place("Lindenweg 3", "85354 Freising", 48.3990, 11.7390, street = "Lindenweg", houseNumber = "3")
        val tt = Geocoding.parseTomTomGeocode(json)
        val m = Geocoding.merge("Lindenweg 7 Freising", listOf(osmStreet, osmOther), tt)
        assertEquals("Lindenweg 7", m[0].name)
        // OSM kennt die Nummer schon: OSM bleibt vorne, TomTom-Doppel faellt weg
        val osmExact = Place("Lindenweg 7", "85354 Freising", 48.40025, 11.74045, street = "Lindenweg", houseNumber = "7")
        val m2 = Geocoding.merge("Lindenweg 7 Freising", listOf(osmExact, osmStreet), tt)
        assertEquals(osmExact, m2[0])
        assertEquals(1, m2.count { it.houseNumber == "7" })
    }
}
