package de.sascha.kssmaps.core

/**
 * Gesprochene Suche aufbereiten: "Navigiere zum Marienplatz 7 in Freising" -> "Marienplatz 7 Freising".
 * command = true, wenn ausdruecklich ein Fahrbefehl gesprochen wurde (dann direkt das beste
 * Ergebnis nehmen statt einer Auswahlliste).
 */
object VoiceQuery {
    data class Result(val query: String, val command: Boolean)

    // Laengere Woerter zuerst ("zum" vor "zu"), sonst bliebe vom "zum" ein "m" stehen.
    private const val TO = "(zur|zum|zu|nach|in die|in den|ins|an die|an den|bis zur|bis zum|bis)"

    private val prefixes = listOf(
        Regex("^(hey|hallo|ok|okay) google[,\\s]*", RegexOption.IGNORE_CASE),
        Regex("^(bitte\\s+)?(starte\\s+)?(die\\s+)?navigation\\s+$TO\\s+", RegexOption.IGNORE_CASE),
        Regex("^(bitte\\s+)?navigiere?\\s+(mich\\s+)?(bitte\\s+)?($TO\\s+)?", RegexOption.IGNORE_CASE),
        Regex("^(bitte\\s+)?(fahr|fahre|fahren|bring|bringe)\\s+(mich\\s+)?(bitte\\s+)?$TO\\s+", RegexOption.IGNORE_CASE),
        Regex("^(die\\s+)?route\\s+$TO\\s+", RegexOption.IGNORE_CASE),
        Regex("^(zeig|zeige)\\s+(mir\\s+)?(den\\s+)?weg\\s+$TO\\s+", RegexOption.IGNORE_CASE),
        Regex("^wie\\s+komme\\s+ich\\s+$TO\\s+", RegexOption.IGNORE_CASE),
        Regex("^ich\\s+(will|möchte|moechte|muss)\\s+$TO\\s+", RegexOption.IGNORE_CASE),
    )

    fun clean(spoken: String): Result {
        var q = spoken.trim()
        var command = false
        var changed = true
        while (changed) {
            changed = false
            for (r in prefixes) {
                val m = r.find(q) ?: continue
                if (m.value.isEmpty()) continue
                q = q.substring(m.value.length).trim()
                if (!r.pattern.contains("google")) command = true
                changed = true
            }
        }
        q = q.replace(Regex("[,\\s]+bitte[.!]?$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^bitte\\s+", RegexOption.IGNORE_CASE), "")
            // "Hauptstraße 5 in Freising" -> "Hauptstraße 5 Freising" (Suche findet beides, ohne "in" sicherer)
            .replace(Regex("(\\d+[a-zA-Z]?)\\s+in\\s+", RegexOption.IGNORE_CASE), "$1 ")
            .trim()
            .trimEnd('.', '!', '?')
            .trim()
        return Result(if (q.isBlank()) spoken.trim() else q, command)
    }
}
