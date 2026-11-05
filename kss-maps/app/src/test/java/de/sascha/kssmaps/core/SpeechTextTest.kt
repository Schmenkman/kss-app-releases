package de.sascha.kssmaps.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {
    private fun n(s: String) = SpeechText.normalize(s)

    @Test
    fun timesAndDecimals() {
        assertEquals("Route gestartet. 12 Komma 3 Kilometer, Ankunft gegen 14 Uhr 35.", n("Route gestartet. 12,3 Kilometer, Ankunft gegen 14:35 Uhr."))
        assertEquals("Ankunft gegen 9 Uhr 5.", n("Ankunft gegen 09:05 Uhr."))
        assertEquals("Ankunft gegen 14 Uhr.", n("Ankunft gegen 14:00 Uhr."))
        assertEquals("In 1 Komma 5 Kilometern rechts abbiegen.", n("In 1,5 Kilometern rechts abbiegen."))
    }

    @Test
    fun unitsAndAbbreviations() {
        assertEquals("Blitzer, 80 Kilometer pro Stunde", n("Blitzer · 80 km/h"))
        assertEquals("Stau in 2 Komma 5 Kilometer, +8 Minuten", n("Stau in 2,5 km · +8 Min."))
        assertEquals("Baustelle in 800 Meter", n("Baustelle in 800 m"))
        assertEquals("Links abbiegen auf die Leopoldstraße", n("Links abbiegen auf die Leopoldstr."))
        assertEquals("A9, Anschlussstelle Allershausen, Anschlussstelle Freising-Süd", n("A9 | AS Allershausen - AS Freising-Süd"))
        assertEquals("Autobahnkreuz Neufahrn", n("AK Neufahrn"))
    }

    @Test
    fun ordinaryTextUnchanged() {
        val s = "In 300 Metern rechts abbiegen auf die Hauptstraße. Danach links halten."
        assertEquals(s, n(s))
        assertEquals("Jetzt im Kreisverkehr die dritte Ausfahrt nehmen auf B 13.", n("Jetzt im Kreisverkehr die dritte Ausfahrt nehmen auf B 13."))
        assertEquals("Etwa 5 min", n("Etwa 5 min"))
    }
}
