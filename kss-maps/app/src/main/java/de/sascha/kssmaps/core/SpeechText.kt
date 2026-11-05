package de.sascha.kssmaps.core

/**
 * Ansagetext fuer die eingebaute Stimme (Piper/espeak-ng) aufbereiten. Google & Co. lesen
 * Uhrzeiten, Kommazahlen und Abkuerzungen selbst richtig – espeak-ng nicht immer: "14:35 Uhr"
 * waere sonst "vierzehn fuenfunddreissig Uhr", "1,5 km" "eins fuenf ka em".
 */
object SpeechText {
    private val time = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)(\s*Uhr)?\b""")
    private val decimal = Regex("""(\d+),(\d+)""")
    private val kmh = Regex("""(\d)\s*km/h\b""")
    private val km = Regex("""(\d)\s*km\b""")
    private val meters = Regex("""(\d)\s*m\b(?![²³])""")
    private val words = listOf(
        Regex("""\bkm/h\b""") to "Kilometer pro Stunde",
        Regex("""\bMin\.""") to "Minuten",
        Regex("""\bStd\.""") to "Stunden",
        Regex("""\bca\.""") to "circa",
        Regex("""\bNr\.""") to "Nummer",
        Regex("""\bStr\.""") to "Straße",
        Regex("""(\p{L})str\.""") to "$1straße",
        // Autobahn-Abkuerzungen aus den Meldungen ("AS Freising-Süd", "AK Neufahrn")
        Regex("""\bAS\b""") to "Anschlussstelle",
        Regex("""\bAK\b""") to "Autobahnkreuz",
        Regex("""\bAD\b""") to "Autobahndreieck",
    )

    fun normalize(text: String): String {
        var s = text
        // Uhrzeit: "14:35 Uhr" -> "14 Uhr 35", "9:05" -> "9 Uhr 5", "14:00 Uhr" -> "14 Uhr"
        s = time.replace(s) { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (min == 0) "$h Uhr" else "$h Uhr $min"
        }
        s = kmh.replace(s, "$1 Kilometer pro Stunde")
        s = km.replace(s, "$1 Kilometer")
        s = meters.replace(s, "$1 Meter")
        // "1,5" -> "1 Komma 5" (espeak-ng liest das Komma sonst als Pause)
        s = decimal.replace(s, "$1 Komma $2")
        for ((rx, rep) in words) s = rx.replace(s, rep)
        // Trennzeichen aus Banner-Texten als kurze Pause
        s = s.replace(Regex("""\s*[·|]\s*"""), ", ").replace(Regex("""\s+[–-]\s+"""), ", ")
        return s.replace(Regex("""\s{2,}"""), " ").trim()
    }
}
