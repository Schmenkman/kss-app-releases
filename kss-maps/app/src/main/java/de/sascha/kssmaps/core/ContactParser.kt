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
 *  - Wen zuerst anrufen: Hinweise gelten fuer die Person, die im Satz am naechsten steht.
 *    Stark: "vorher anrufen", "… min/Std. vorher", "vor Anfahrt", "melden", "Bescheid geben",
 *    "informieren" – nur so wird jemand "zuerst anrufen". Mittel: "Schluessel bei". Schwach:
 *    "unter …", "erreichbar", "Ansprechpartner", "Termin mit …".
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
        /** "30 min vorher anrufen" -> 30, "1 Std. vorher" -> 60; null = keine Zeit genannt. */
        val callBeforeMin: Int? = null,
    )

    data class Result(val contacts: List<Contact>, val time: String?)

    private val anrede = "(Herrn?|HERRN?|Hr\\.?|HR\\.?|Frau|FRAU|Fr\\.?|FR\\.?|Familie|FAMILIE|Fam\\.?|FAM\\.?)"
    /** Nachname gross geschrieben ("Huber") oder ganz in Grossbuchstaben ("HUBER"), auch Doppelname. */
    private val surname = "[A-ZÄÖÜ](?:[a-zäöüß]+|[A-ZÄÖÜ]+)"
    private val nameRx = Regex("\\b$anrede\\s+((?:Dr\\.\\s*)?$surname(?:-$surname)?)")
    private val phoneRx = Regex("(?<![\\d/])(\\+?\\(?(?:0|49|00)[\\d\\s/().\\-]{5,}\\d)")
    private val strongRx = Regex(
        "vorher|vorab|vor\\s+(?:der\\s+)?anfahrt|bescheid|anruf|melden|r(?:ü|ue)ckruf|informieren|ank(?:ü|ue)ndigen|avisieren",
        RegexOption.IGNORE_CASE,
    )
    private val keyRx = Regex("schl(?:ü|ue)ssel", RegexOption.IGNORE_CASE)
    private val weakRx = Regex("\\bunter\\b|erreichbar|ansprechpartner", RegexOption.IGNORE_CASE)
    private val minutesBefore = Regex("(\\d{1,3})\\s*(min(?:uten)?|std|stunden?)\\.?\\s*(?:vorher|vorab|vor)", RegexOption.IGNORE_CASE)
    private val terminMit = Regex("termin\\s+(?:mit|bei)", RegexOption.IGNORE_CASE)
    private val timeRx = Regex("\\b(?:ab\\s+|um\\s+|gegen\\s+)?([01]?\\d|2[0-3])(?:[:.]([0-5]\\d))?\\s*Uhr\\b|\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b", RegexOption.IGNORE_CASE)
    private val abbrev = Regex("\\b(Hr|Fr|Fam|Dr|Min|ca|Tel|Nr|Str|bzw|evtl|z\\.B|u\\.a|Std|Hnr)\\.", RegexOption.IGNORE_CASE)
    /** Worte vor einer Nummer, die kein Name sind. */
    private val notName = setOf(
        "tel", "telefon", "handy", "mobil", "festnetz", "nr", "nummer", "unter", "oder", "bzw", "bitte",
        "termin", "kunde", "kundin", "ansprechpartner", "ansprechpartnerin", "rufnummer", "und", "mit",
        "bei", "anrufen", "vorher", "min", "minuten", "uhr", "erreichbar", "rückruf", "rueckruf", "the",
        "herr", "herrn", "hr", "frau", "fr", "familie", "fam",
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

    /** Alle Rufnummern in einem Text als Waehlziffern (z. B. die eigenen Nummern eines Technikers). */
    fun numbersIn(text: String): List<String> {
        val out = ArrayList<String>()
        for (m in phoneRx.findAll(text)) for ((_, raw) in splitPhones(m.value, m.range.first)) {
            val d = normalize(raw)
            if (d.length in 7..13 && d.startsWith("0") && d !in out) out.add(d)
        }
        return out
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
            /** Ausdruecklicher Anruf-Hinweis ("vorher anrufen" …) fuer diese Person. */
            var strong = false
            var hint: String? = null
            var hintWeight = 0
            var minutes: Int? = null
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
            data class Ev(val range: IntRange, val isName: Boolean, val a: String, val b: String)
            val events = ArrayList<Ev>()
            for (m in nameRx.findAll(sentence)) events.add(Ev(m.range, true, m.groupValues[1], m.groupValues[2]))
            for (m in phoneRx.findAll(masked)) {
                // Zwei Nummern direkt hintereinander ("0171 1234567 / 0160 9876543") trennen
                for ((off, raw) in splitPhones(m.value, m.range.first)) {
                    val t = raw.trimEnd()
                    events.add(Ev(off until off + t.length, false, t, ""))
                }
            }
            events.sortBy { it.range.first }
            /** Wo im Satz welche Person steht (Name oder Nummer) – fuer die Zuordnung der Hinweise. */
            data class Span(val range: IntRange, val key: String)
            val spans = ArrayList<Span>()
            val lastBefore = lastName
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
                    val key = "$title ${properCase(ev.b)}"
                    person(key)
                    current = key
                    lastName = key
                    spans.add(Span(ev.range, key))
                } else {
                    val digits = normalize(ev.a)
                    if (digits.length !in 7..13 || !digits.startsWith("0") || digits in own) continue
                    // Steht direkt vor der Nummer ein anderer Name ("vor Ort Thomas Huber 0171 …"), gehoert
                    // sie dieser Person; sonst der zuletzt genannten.
                    val before = wordsBefore(sentence, ev.range.first)
                    val cur = current
                    val owner = when {
                        before != null && cur != null && before.lowercase().endsWith(cur.substringAfter(' ').lowercase()) -> cur
                        before != null -> properCase(before)
                        cur != null -> cur
                        else -> lastName ?: "Kontakt"
                    }
                    val p = person(owner)
                    if (p.dial.none { it == digits }) {
                        p.dial.add(digits)
                        p.phones.add(displayOf(ev.a))
                    }
                    current = owner
                    lastName = owner
                    spans.add(Span(ev.range, owner))
                }
            }
            // Hinweise gelten fuer die naechstgenannte Person im Satz; ein Satz ohne Person
            // ("… 9 Uhr. Vorher anrufen") fuer die zuletzt genannte.
            fun nearest(q: Int): String? = spans.minByOrNull { sp ->
                when {
                    q < sp.range.first -> sp.range.first - q
                    q > sp.range.last -> q - sp.range.last
                    else -> 0
                }
            }?.key ?: lastBefore
            val weight = HashMap<String, Int>()
            fun mark(rx: Regex, w: Int) {
                for (m in rx.findAll(sentence)) {
                    val k = nearest(m.range.first) ?: continue
                    if (w > (weight[k] ?: 0)) weight[k] = w
                }
            }
            mark(weakRx, 1)
            mark(keyRx, 2)
            mark(strongRx, 3)
            for ((k, w) in weight) {
                val p = people[k] ?: continue
                p.score += w
                if (w >= 3) p.strong = true
                if (w > p.hintWeight) {
                    hintFor(sentence, w)?.let { p.hint = it }
                    p.hintWeight = w
                    if (w >= 3) p.minutes = minutesBefore.find(sentence)?.let { m ->
                        val n = m.groupValues[1].toInt()
                        if (m.groupValues[2].lowercase().startsWith("st")) n * 60 else n
                    }
                }
            }
            // "Termin mit Herrn X" – leicht nach vorn
            for (m in terminMit.findAll(sentence)) {
                spans.firstOrNull { it.range.first > m.range.last }?.let { people[it.key]?.let { p -> p.score += 1 } }
            }
        }
        val ranked = people.values.filter { it.dial.isNotEmpty() }
            .sortedWith(compareBy<Person>({ if (it.strong) 0 else 1 }, { -it.score }, { it.first }))
        val top = ranked.firstOrNull()
        val contacts = ranked.map { p ->
            Contact(p.name, p.phones.toList(), p.dial.toList(), callFirst = p === top && p.strong, hint = p.hint, callBeforeMin = p.minutes)
        }
        return Result(contacts, timeOf(text))
    }

    private val strongSep = Regex("\\s*/\\s*|\\s+-\\s+|\\s{2,}")
    private val anySpace = Regex("\\s+")

    private fun validNumber(raw: String): Boolean = normalize(raw).let { it.length in 7..13 && it.startsWith("0") }

    /** Mehrere aneinanderhaengende Nummern in einzelne zerlegen: (Position, Text). */
    private fun splitPhones(raw: String, start: Int): List<Pair<Int, String>> {
        if (normalize(raw).length <= 13) return listOf(start to raw)
        for (sep in listOf(strongSep, anySpace)) {
            for (m in sep.findAll(raw)) {
                val left = raw.substring(0, m.range.first)
                val rightAt = m.range.last + 1
                val right = raw.substring(rightAt)
                if (validNumber(left) && normalize(right).startsWith("0") && normalize(right).length >= 7) {
                    return listOf(start to left) + splitPhones(right, start + rightAt)
                }
            }
        }
        return listOf(start to raw)
    }

    /** Kurzer Grund fuer die Anzeige, z. B. "30 Min. vorher anrufen". */
    private fun hintFor(sentence: String, w: Int): String? {
        if (w == 2) return "Schlüssel"
        if (w < 3) return null
        minutesBefore.find(sentence)?.let { m ->
            val unit = if (m.groupValues[2].lowercase().startsWith("st")) "Std." else "Min."
            return "${m.groupValues[1]} $unit vorher anrufen"
        }
        val s = sentence.lowercase()
        val before = Regex("vorher|vorab|vor\\s+(?:der\\s+)?anfahrt").containsMatchIn(s)
        return when {
            "informieren" in s -> if (before) "vorher informieren" else "informieren"
            "bescheid" in s -> if (before) "vorher Bescheid geben" else "Bescheid geben"
            Regex("ank(?:ü|ue)ndigen|avisieren").containsMatchIn(s) -> "vorher ankündigen"
            before -> "vorher anrufen"
            Regex("r(?:ü|ue)ckruf").containsMatchIn(s) -> "Rückruf"
            "melden" in s -> "melden"
            else -> "anrufen"
        }
    }

    /** "KOCH" -> "Koch", "MÜLLER-LÜDENSCHEIDT" -> "Müller-Lüdenscheidt"; normale Schreibweise bleibt. */
    private fun properCase(name: String): String = name.split(' ').joinToString(" ") { w ->
        w.split('-').joinToString("-") { part ->
            if (part.length > 1 && part.any { it.isLetter() } && part.none { it.isLowerCase() }) part.lowercase().replaceFirstChar { it.titlecase() } else part
        }
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
