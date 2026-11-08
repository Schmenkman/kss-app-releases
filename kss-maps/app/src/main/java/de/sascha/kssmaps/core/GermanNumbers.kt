package de.sascha.kssmaps.core

/**
 * Zahlen als deutsche Woerter – fuer Stimmen, die Ziffern nicht zuverlaessig deutsch sprechen
 * (Supertonic liest manche Ziffern englisch: "35" wurde "thirty-five").
 * "300" -> "dreihundert", "21" -> "einundzwanzig", "1500" -> "tausendfünfhundert".
 */
object GermanNumbers {
    private val ones = arrayOf(
        "null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun",
        "zehn", "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn",
    )
    private val tens = arrayOf("", "zehn", "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig", "siebzig", "achtzig", "neunzig")

    /** Grundzahl, z. B. 1 -> "eins", 101 -> "hunderteins". */
    fun words(n: Long): String = when {
        n < 0 -> "minus " + words(-n)
        n < 20 -> ones[n.toInt()]
        n < 100 -> {
            val u = (n % 10).toInt()
            val t = (n / 10).toInt()
            if (u == 0) tens[t] else (if (u == 1) "ein" else ones[u]) + "und" + tens[t]
        }
        n < 1000 -> {
            val h = (n / 100).toInt()
            val r = n % 100
            (if (h == 1) "" else ones[h]) + "hundert" + (if (r > 0) words(r) else "")
        }
        n < 1_000_000 -> {
            val t = n / 1000
            val r = n % 1000
            (if (t == 1L) "" else prefix(t)) + "tausend" + (if (r > 0) words(r) else "")
        }
        n < 1_000_000_000 -> {
            val mil = n / 1_000_000
            val r = n % 1_000_000
            (if (mil == 1L) "eine Million" else words(mil) + " Millionen") + (if (r > 0) " " + words(r) else "")
        }
        else -> n.toString()
    }

    /** Vor "hundert"/"tausend": "eins" -> "ein" (hunderteintausend). */
    private fun prefix(n: Long): String = words(n).let { if (it.endsWith("eins")) it.dropLast(1) else it }

    /** Ordnungszahl in der Form "die dritte" (1 -> "erste", 7 -> "siebte", 20 -> "zwanzigste"). */
    fun ordinal(n: Long): String = when (n) {
        1L -> "erste"
        3L -> "dritte"
        7L -> "siebte"
        8L -> "achte"
        else -> {
            val w = words(n)
            if (n < 20) w + "te" else w + "ste"
        }
    }
}
