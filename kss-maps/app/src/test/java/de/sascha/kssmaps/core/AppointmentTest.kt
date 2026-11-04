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
    fun addressAndTimeRange() {
        val a = appt("1", 9, endH = 10).copy(end = at(10, 30))
        assertEquals("Teststraße 1, 85354 Freising", a.address)
        assertEquals("09:00–10:30", Appointments.timeRange(a, berlin))
        assertEquals("Teststraße 1", a.copy(zip = " ", city = "").address)
    }
}
