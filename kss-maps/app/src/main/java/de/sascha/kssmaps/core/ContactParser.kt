package de.sascha.kssmaps.core

/**
 * Ansprechpartner aus dem Terminzusatz (Freitext, den das Buero beim Terminieren schreibt),
 * z. B. "Termin mit Herr Bauer 0871 1234567 besprochen und bestaetigt. 9 Uhr. Vorher anrufen"
 * oder "Termin mit Herr Mueller … vereinbart. Bitte vorher Herr Mader 30 min vorher unter …
 * oder … anrufen".
 *
 *  - Rufnummern: deutsche Festnetz-/Handynummern mit Leerzeichen, "/", "-", "+49", "(0)".
 *    Vorher fallen Auftrags-/Delphi-Nummern, Datum und Uhrzeiten heraus (wie in der Dispo-App).
 *  - Namen: "Herr|Frau|Hr.|Fr.|Familie <Name>"; sonst die Worte direkt vor der Nummer.
 *    Mehrere Nummern hinter einem Namen ("unter … oder …") gehoeren dieser Person.
 *  - Wen zuerst anrufen: wer in einem Satz mit "vorher anrufen", "… min vorher", "vor Anfahrt",
 *    "melden bei", "Bescheid geben", "Schluessel bei", "unter …", "erreichbar" steht, kommt
 *    nach vorn; "Termin mit …" zaehlt weniger.
 *  - Eigene Nummern (Firma, Techniker) werden nie als Kunde vorgeschlagen.
 * Der volle Text wird in der App immer zusaetzlich angezeigt – die Erkennung ist eine Hilfe.
 */
object ContactParser {

    data class Contact(
        val name: String,
        /** Nummern in Anzeige-Schreibweise (wie im Text, Trenner vereinheitlicht). */
        val phones: List<String>,
        /** Dieselben Nummern als reine Waehlziffern (fuer tel:). */
        val dial: List<String>,
        /** Ausdruecklich "vorher anrufen" o. Ae. – oben und hervorgehoben anzeigen. */
        val callFirst: Boolean,
        /** Kurzer Grund, z. B. "30 Min. vorher anrufen". */
        val hint: String?,
    )

    data class Result(val contacts: List<Contact>, val time: String?)

    private val anrede = "(Herrn?|Hr\\.?|Frau|Fr\\.?|Familie|Fam\\.?)"
    private val nameRx = Regex("\\b$anrede\\s+((?:Dr\\.\\s*)?[A-ZÄÖÜ][a-zäöüß]+(?:-[A-ZÄÖÜ][a-zäöüß]+)?)")
    private val phoneRx = Regex("(?<![\\d/])(\\+?\\(?(?:0|49|00)[\\d\\s/().\\-]{5,}\\d)")
    private val callWords = Regex(
        "anruf|anrufen|melden|vorher|vorab|vor\\s+anfahrt|bescheid|schl(?:ü|ue)ssel|\\bunter\\b|erreichbar|r(?:ü|ue)ckruf|ansprechpartner",
        RegexOption.IGNORE_CASE,
    )
    private val minutesBefore = Regex("(\\d{1,3})\\s*(?:min(?:uten)?|std|stunden?)\\.?\\s*(?:vorher|vorab|vor)", RegexOption.IGNORE_CASE)
    private val terminMit = Regex("termin\\s+(?:mit|bei)", RegexOption.IGNORE_CASE)
    private val timeRx = Regex("\\b(?:ab\\s+|um\\s+|gegen\\s+)?([01]?\\d|2[0-3])(?:[:.]([0-5]\\d))?\\s*Uhr\\b|\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b", RegexOption.IGNORE_CASE)
    private val abbrev = Regex("\\b(Hr|Fr|Fam|Dr|Min|ca|Tel|Nr|Str|bzw|evtl|z\\.B|u\\.a|Std|Hnr)\\.", RegexOption.IGNORE_CASE)
    /** Worte vor einer Nummer, die kein Name sind. */
    private val notName = setOf(
        "tel", "telefon", "handy", "mobil", "festnetz", "nr", "nummer", "unter", "oder", "bzw", "bitte",
        "termin", "kunde", "kundin", "ansprechpartner", "ansprechpartnerin", "rufnummer", "und", "mit",
        "bei", "anrufen", "vorher", "min", "minuten", "uhr", "erreichbar", "rückruf", "rueckruf", "the",
    )

    /** Rufnummer -> reine Waehlziffern im Nationalformat (+49/0049 -> 0, "(0)" beachtet). */
    fun normalize(num: String): String {
        var s = num.trim()
        // "(0)" hinter einer Laendervorwahl faellt weg, sonst wird es zur fuehrenden 0
        s = Regex("\\(\\s*0\\s*\\)").replace(s) { m ->
            val before = s.substring(0, m.range.first)
            val digits = before.filter { it.isDigit() }
            if (before.contains('+') || digits.startsWith("00") || digits.startsWith("49")) "" else "0"
        }
        var d = s.filter { it.isDigit() }
        if (s.startsWith("+49")) d = "0" + d.removePrefix("49")
        else if (d.startsWith("0049")) d = "0" + d.substring(4)
        else if (d.startsWith("49") && d.length >= 11) d = "0" + d.substring(2)
        return d
    }

    private fun displayOf(raw: String): String =
        raw.trim(' ', '-', '/', '.').replace(Regex("\\s{2,}"), " ")

    fun parse(text: String, ownNumbers: Collection<String> = emptyList()): Result {
        val own = ownNumbers.map { normalize(it) }.filter { it.length >= 6 }.toSet()
        // Abkuerzungen schuetzen, damit "Hr." oder "Min." keinen Satz beenden
        val protectedText = abbrev.replace(text.trim()) { it.groupValues[1] + "§" }
        val sentences = protectedText.split(Regex("(?<=[.!?;])\\s+|\\n+")).map { it.replace("§", ".") }.filter { it.isNotBlank() }

        class Person(val name: String, val first: Int) {
            val phones = ArrayList<String>()
            val dial = ArrayList<String>()
            var score = 0
            var hint: String? = null
        }
        val people = LinkedHashMap<String, Person>()
        var lastName: String? = null
        var order = 0

        fun person(key: String): Person = people.getOrPut(key) { Person(key, order++) }

        for (sentence in sentences) {
            // Auftrags-/Delphi-Nummern, Datum und Uhrzeiten fuer die Nummernsuche ausblenden
            val masked = sentence
                .replace(Regex("\\d{3}-\\d{5,}/\\d{2}")) { " ".repeat(it.value.length) }
                .replace(Regex("\\b\\d{1,2}\\.\\d{1,2}\\.\\d{2,4}\\b")) { " ".repeat(it.value.length) }
                .replace(Regex("\\b\\d{1,2}:\\d{2}\\b")) { " ".repeat(it.value.length) }
            data class Ev(val pos: Int, val isName: Boolean, val a: String, val b: String)
            val events = ArrayList<Ev>()
            for (m in nameRx.findAll(sentence)) events.add(Ev(m.range.first, true, m.groupValues[1], m.groupValues[2]))
            for (m in phoneRx.findAll(masked)) events.add(Ev(m.range.first, false, m.groupValues[1], ""))
            events.sortBy { it.pos }
            val named = ArrayList<String>()
            // zuletzt genannte Person in diesem Satz: ihr gehoeren folgende Nummern ("unter … oder …")
            var current: String? = null
            for (ev in events) {
                if (ev.isName) {
                    val a = ev.a.lowercase()
                    val title = when {
                        a.startsWith("fr") -> "Frau"
                        a.startsWith("fam") -> "Familie"
                        else -> "Herr"
                    }
                    val key = "$title ${ev.b}"
                    person(key)
                    current = key
                    lastName = key
                    if (key !in named) named.add(key)
                } else {
                    val digits = normalize(ev.a)
                    if (digits.length !in 7..13 || !digits.startsWith("0") || digits in own) continue
                    // Steht direkt vor der Nummer ein anderer Name ("vor Ort Thomas Huber 0171 …"), gehoert
                    // sie dieser Person; sonst der zuletzt genannten.
                    val before = wordsBefore(sentence, ev.pos)
                    val cur = current
                    val owner = when {
                        before != null && cur != null && before.endsWith(cur.substringAfter(' ')) -> cur
                        before != null && !before.startsWith("Herr ") && !before.startsWith("Frau ") -> before
                        cur != null -> cur
                        else -> before ?: lastName ?: "Kontakt"
                    }
                    val p = person(owner)
                    if (p.dial.none { it == digits }) {
                        p.dial.add(digits)
                        p.phones.add(displayOf(ev.a))
                    }
                    if (owner !in named) named.add(owner)
                    current = owner
                    lastName = owner
                }
            }
            // Wer soll angerufen werden? Saetze mit Anruf-Hinweis zaehlen fuer die dort genannten
            // Personen, sonst fuer die zuletzt genannte ("… 9 Uhr. Vorher anrufen").
            val targets = if (named.isNotEmpty()) named else listOfNotNull(lastName)
            val isCall = callWords.containsMatchIn(sentence)
            val minutes = minutesBefore.find(sentence)
            for (k in targets) {
                val p = people[k] ?: continue
                if (isCall) {
                    p.score += 2
                    if (p.hint == null) p.hint = when {
                        minutes != null -> "${minutes.groupValues[1]} Min. vorher anrufen"
                        Regex("vorher|vorab|vor\\s+anfahrt", RegexOption.IGNORE_CASE).containsMatchIn(sentence) -> "vorher anrufen"
                        Regex("schl(?:ü|ue)ssel", RegexOption.IGNORE_CASE).containsMatchIn(sentence) -> "Schlüssel"
                        else -> null
                    }
                }
                if (terminMit.containsMatchIn(sentence) && k in named) p.score += 1
            }
        }
        val ranked = people.values.filter { it.dial.isNotEmpty() }
            .sortedWith(compareBy<Person>({ -it.score }, { it.first }))
        val top = ranked.firstOrNull()
        val contacts = ranked.map { p ->
            Contact(p.name, p.phones.toList(), p.dial.toList(), callFirst = p === top && p.score >= 2, hint = p.hint)
        }
        return Result(contacts, timeOf(text))
    }

    /** Ein bis zwei grossgeschriebene Worte direkt vor der Nummer als Name ("Thomas Huber 0171 …"). */
    private fun wordsBefore(sentence: String, pos: Int): String? {
        val words = sentence.substring(0, pos).trim().split(Regex("[\\s,:;()]+")).filter { it.isNotBlank() }.takeLast(2)
        val good = words.filter { w ->
            w.first().isUpperCase() && w.lowercase().trimEnd('.') !in notName && w.none { it.isDigit() } && w.length > 1
        }
        if (good.isEmpty() || good.size < words.size && words.last() != good.lastOrNull()) return null
        return good.joinToString(" ")
    }

    fun timeOf(text: String): String? {
        val t = timeRx.find(text) ?: return null
        val h = t.groupValues[1].ifEmpty { t.groupValues[3] }
        val m = t.groupValues[2].ifEmpty { t.groupValues[4] }.ifEmpty { "00" }
        return String.format(java.util.Locale.ROOT, "%02d:%s", h.toInt(), m)
    }
}
