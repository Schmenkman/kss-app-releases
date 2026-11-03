package de.sascha.kssmaps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceQueryTest {

    private fun q(s: String) = VoiceQuery.clean(s)

    @Test
    fun stripsDrivingCommands() {
        assertEquals("Marienplatz 7 Freising", q("Navigiere zum Marienplatz 7 in Freising").query)
        assertTrue(q("Navigiere zum Marienplatz 7 in Freising").command)
        assertEquals("Hauptstraße 5", q("Hey Google navigiere zur Hauptstraße 5").query)
        assertEquals("Nürnberg", q("Fahr mich bitte nach Nürnberg").query)
        assertEquals("Hauptbahnhof München", q("Bring mich zum Hauptbahnhof München").query)
        assertEquals("Augsburg", q("Route nach Augsburg bitte").query)
        assertEquals("Allianz Arena", q("Zeig mir den Weg zur Allianz Arena").query)
        assertEquals("Flughafen München", q("Starte die Navigation zum Flughafen München").query)
        assertEquals("Ikea Eching", q("Ich will zu Ikea Eching").query)
    }

    @Test
    fun plainAddressesStayAndAreNoCommand() {
        val r = q("Marienplatz Freising")
        assertEquals("Marienplatz Freising", r.query)
        assertFalse(r.command)
        assertEquals("Nachtigallenweg 3 Landshut", q("Nachtigallenweg 3 Landshut").query)
        assertEquals("Nachtigallenweg 3", q("Navigiere Nachtigallenweg 3").query)
        // nur ein Befehl ohne Ziel: Originaltext behalten statt leer suchen
        assertEquals("navigiere", q("navigiere").query)
    }
}
