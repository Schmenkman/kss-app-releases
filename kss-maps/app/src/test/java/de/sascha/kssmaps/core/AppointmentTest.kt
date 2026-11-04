package de.sascha.kssmaps.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Termine: Reihenfolge, Techniker-Filter, Anzeige. Alle Daten sind erfunden. */
class AppointmentTest {

    private val berlin = java.util.TimeZone.getTimeZone("Europe/Berlin")

    private fun at(h: Int, m: Int = 0): Long = java.util.Calendar.getInstance(berlin).apply {
        set(2026, java.util.Calendar.NOVEMBER, 5, h, m, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun appt(id: String, h: Int, tech: List<Int> = emptyList(), endH: Int = h + 1) =
        Appointment(id, "TEST-$id", at(h), at(endH), "Teststraße $id", "85354", "Freising", "Kunde $id", "", technicianIds = tech)

    @Test
    fun sortedByStartAndFilteredByTechnician() {
        val list = listOf(appt("c", 14, listOf(2)), appt("a", 9, listOf(1)), appt("b", 11, listOf(1, 2)))
        assertEquals(listOf("a", "b", "c"), Appointments.forTechnician(list, null).map { it.id })
        assertEquals(listOf("a", "b"), Appointments.forTechnician(list, 1).map { it.id })
        assertEquals(listOf("b", "c"), Appointments.forTechnician(list, 2).map { it.id })
    }

    @Test
    fun bestSearchResultIsTheAddressItself() {
        val a = appt("x", 9).copy(street = "Bahnhofstraße 1", zip = "85354", city = "Freising")
        val firm = Place("Testfirma GmbH", "Bahnhofstraße 1, 85354 Freising", 48.40, 11.74)
        val other = Place("Bahnhofstraße 1", "85356 Freising", 48.41, 11.75, street = "Bahnhofstraße", houseNumber = "1", postcode = "85356")
        val exact = Place("Bahnhofstraße 1", "85354 Freising", 48.40, 11.74, street = "Bahnhofstraße", houseNumber = "1", postcode = "85354")
        assertEquals(exact, Appointments.bestMatch(a, listOf(firm, other, exact)))
        // ohne Strasse entscheidet die PLZ
        assertEquals(firm, Appointments.bestMatch(a.copy(street = ""), listOf(other, firm)))
        assertEquals(null, Appointments.bestMatch(a, emptyList()))
        val b = appt("y", 9).copy(street = "Hauptstr. 12a", zip = "80331", city = "München")
        val hit = Place("Hauptstraße 12a", "80331 München", 48.1, 11.5, street = "Hauptstraße", houseNumber = "12a", postcode = "80331")
        assertEquals(hit, Appointments.bestMatch(b, listOf(Place("Hauptstraße 12", "80331 München", 48.1, 11.5, street = "Hauptstraße", houseNumber = "12", postcode = "80331"), hit)))
    }

    @Test
    fun addressAndTimeRange() {
        val a = appt("1", 9, endH = 10).copy(end = at(10, 30))
        assertEquals("Teststraße 1, 85354 Freising", a.address)
        assertEquals("09:00–10:30", Appointments.timeRange(a, berlin))
        assertEquals("Teststraße 1", a.copy(zip = " ", city = "").address)
    }
}
