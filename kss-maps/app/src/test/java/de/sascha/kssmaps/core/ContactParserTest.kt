package de.sascha.kssmaps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Terminzusatz -> Ansprechpartner. Alle Nummern sind erfunden. */
class ContactParserTest {

    private fun p(t: String, own: List<String> = emptyList()) = ContactParser.parse(t, own)

    @Test
    fun singleContactWithCallBefore() {
        val r = p("Termin mit Herr Bauer  0871 1234567 besprochen und bestätigt. 9 Uhr. Vorher anrufen")
        assertEquals(1, r.contacts.size)
        val c = r.contacts[0]
        assertEquals("Herr Bauer", c.name)
        assertEquals(listOf("08711234567"), c.dial)
        assertTrue(c.callFirst)
        assertEquals("vorher anrufen", c.hint)
        assertEquals("09:00", r.time)
    }

    @Test
    fun personToCallBeforeComesFirstWithBothNumbers() {
        val r = p("Termin mit Herr Müller 0171 2345678 vereinbart. Bitte vorher Herr Mader 30 min vorher unter 08761/72345 oder 0160-9876543 anrufen")
        assertEquals(listOf("Herr Mader", "Herr Müller"), r.contacts.map { it.name })
        assertEquals(listOf("0876172345", "01609876543"), r.contacts[0].dial)
        assertEquals(listOf("08761/72345", "0160-9876543"), r.contacts[0].phones)
        assertTrue(r.contacts[0].callFirst)
        assertEquals("30 Min. vorher anrufen", r.contacts[0].hint)
        assertFalse(r.contacts[1].callFirst)
        assertEquals(listOf("01712345678"), r.contacts[1].dial)
    }

    @Test
    fun abbreviationsDoNotBreakSentences() {
        val r = p("Termin mit Frau Huber vereinbart, ab 10:30. Bitte 30 Min. vorher bei Hr. Huber Junior anrufen 0152 33445566")
        assertEquals("Herr Huber", r.contacts[0].name)
        assertEquals(listOf("015233445566"), r.contacts[0].dial)
        assertEquals("10:30", r.time)
    }

    @Test
    fun numberWithoutNameAndInternationalFormat() {
        val r = p("Termin 13 Uhr bestätigt. Kunde erreichbar unter +49 (0)8161 123456")
        assertEquals("Kontakt", r.contacts[0].name)
        assertEquals(listOf("08161123456"), r.contacts[0].dial)
        assertEquals("13:00", r.time)
    }

    @Test
    fun nameRightBeforeNumberWithoutTitle() {
        val r = p("Termin mit Herr Müller vereinbart. Ansprechpartner vor Ort Thomas Huber 0171 2223334, Schlüssel bei ihm")
        assertEquals("Thomas Huber", r.contacts[0].name)
        assertEquals(listOf("01712223334"), r.contacts[0].dial)
    }

    @Test
    fun orderNumbersDatesTimesAndOwnNumbersAreNotPhones() {
        val r = p(
            "Auftrag 009-1234567/26 am 05.11.2026 um 08:30. Termin mit Frau Kaya 0911 445566. Rückfragen Büro 08131 999888",
            own = listOf("08131 999888"),
        )
        assertEquals(1, r.contacts.size)
        assertEquals("Frau Kaya", r.contacts[0].name)
        assertEquals(listOf("0911445566"), r.contacts[0].dial)
    }

    @Test
    fun noNumbersNoContacts() {
        val r = p("Termin bestätigt, Kunde öffnet selbst.")
        assertTrue(r.contacts.isEmpty())
        assertNull(r.time)
    }
}
