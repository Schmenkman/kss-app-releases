package de.sascha.kssmaps.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GermanNumbersTest {
    @Test
    fun cardinals() {
        val w = GermanNumbers::words
        assertEquals("null", w(0))
        assertEquals("eins", w(1))
        assertEquals("sechzehn", w(16))
        assertEquals("einundzwanzig", w(21))
        assertEquals("dreißig", w(30))
        assertEquals("fünfunddreißig", w(35))
        assertEquals("hundert", w(100))
        assertEquals("hunderteins", w(101))
        assertEquals("dreihundert", w(300))
        assertEquals("tausendfünfhundert", w(1500))
        assertEquals("zweitausendvierundachtzig", w(2084))
        assertEquals("hunderteintausend", w(101_000))
        assertEquals("fünfundachtzigtausenddreihundertvierundfünfzig", w(85354))
        assertEquals("eine Million zweihunderttausend", w(1_200_000))
    }

    @Test
    fun ordinals() {
        assertEquals("erste", GermanNumbers.ordinal(1))
        assertEquals("dritte", GermanNumbers.ordinal(3))
        assertEquals("siebte", GermanNumbers.ordinal(7))
        assertEquals("neunte", GermanNumbers.ordinal(9))
        assertEquals("zwanzigste", GermanNumbers.ordinal(20))
        assertEquals("einundzwanzigste", GermanNumbers.ordinal(21))
    }

    @Test
    fun spellNumbersInAnnouncements() {
        fun sp(t: String) = SpeechText.spellNumbers(SpeechText.normalize(t))
        assertEquals("In dreihundert Metern rechts abbiegen auf B dreizehn.", sp("In 300 Metern rechts abbiegen auf B 13."))
        assertEquals("Route gestartet. zwölf Komma drei Kilometer, Ankunft gegen vierzehn Uhr fünfunddreißig.", sp("Route gestartet. 12,3 Kilometer, Ankunft gegen 14:35 Uhr."))
        assertEquals("Ankunft gegen ein Uhr fünf.", sp("Ankunft gegen 01:05 Uhr."))
        assertEquals("Stau in zwei Komma fünf Kilometern, etwa acht Minuten Verzögerung.", sp("Stau in 2,5 Kilometern, etwa 8 Minuten Verzögerung."))
        assertEquals("Jetzt im Kreisverkehr die neunte Ausfahrt nehmen.", sp("Jetzt im Kreisverkehr die 9. Ausfahrt nehmen."))
        assertEquals("Die Ausfahrt neunundsechzig nehmen. Danach links.", sp("Die Ausfahrt 69 nehmen. Danach links."))
        assertEquals("Ziel: Hauptstraße zwölf a", sp("Ziel: Hauptstraße 12a"))
        assertEquals("Auf A neun Richtung Nürnberg", sp("Auf A9 Richtung Nürnberg"))
    }
}
