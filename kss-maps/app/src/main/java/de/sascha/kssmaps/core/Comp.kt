package de.sascha.kssmaps.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * COMP4/betterbits (Auftragssystem): Anmeldeseite und Antworten auswerten – ohne Netz, auf der
 * JVM getestet. Vorbild ist der Client der KSS-Dispo-App (gegen das echte COMP gemessen).
 *
 * KSS-Maps liest nur: keine Auftragsbearbeitung ("editorder" sperrt den Auftrag), nichts
 * terminieren oder aendern. Zugangsdaten und Kundendaten stehen nie im Code und nie im Log.
 */
object Comp {
    const val BASE = "https://cableway-r09.betterbits.de"
    const val LOCALE = "de"
    /** Schicht CS-MUC-N4-R7 und Firma KSS-Solutions (Vorgaben wie in der KSS-Dispo-App). */
    const val SHIFT_ID = 58
    const val CONTRACTOR_ID = 2274
    const val COMPANY_ID = 22
    val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

    data class Form(val action: String, val hidden: Map<String, String>)

    /** Anmeldeformular (id="form"): Ziel und alle versteckten Felder (u. a. t:formdata). */
    fun loginForm(html: String): Form? {
        val tag = Regex("<form[^>]*\\bid=\"form\"[^>]*>", RegexOption.IGNORE_CASE).find(html) ?: return null
        val end = html.indexOf("</form>", tag.range.last, ignoreCase = true).let { if (it < 0) html.length else it }
        val inner = html.substring(tag.range.last + 1, end)
        val action = Regex("\\baction=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(tag.value)?.groupValues?.get(1)?.let { unescape(it) } ?: ""
        val hidden = LinkedHashMap<String, String>()
        for (inp in Regex("<input[^>]*>", RegexOption.IGNORE_CASE).findAll(inner)) {
            val t = inp.value
            if (!Regex("\\btype=\"hidden\"", RegexOption.IGNORE_CASE).containsMatchIn(t)) continue
            val n = Regex("\\bname=\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: continue
            val v = Regex("\\bvalue=\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: ""
            hidden[unescape(n)] = unescape(v)
        }
        return Form(action, hidden)
    }

    /**
     * COMP-Benutzer-ID aus der Planer-Seite ("userId":1234) – Pflicht-Header fuer die REST-Aufrufe.
     * Auf derselben Seite steht ein Messaging-Kennwort: die Seite wird nur hiernach durchsucht,
     * nie gespeichert oder protokolliert.
     */
    fun userId(html: String): String? = Regex("\"userId\"\\s*:\\s*\"?(\\d+)").find(html)?.groupValues?.get(1)

    /** Landet eine Anfrage auf der Anmeldung, ist die Sitzung abgelaufen. */
    fun isLoginPage(url: String): Boolean = url.contains("/login")

    /** Techniker der Schicht (GET /rest/internal/scheduling/technicians). */
    fun technicians(json: String): List<Technician> {
        val arr = rootArray(json, "technicians") ?: return emptyList()
        val out = ArrayList<Technician>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optInt("id", -1)
            if (id <= 0 || out.any { it.id == id }) continue
            out.add(Technician(id, str(o, "name").ifBlank { "Techniker $id" }, phones(o.opt("allPhoneNumbers"))))
        }
        return out
    }

    /**
     * Termine aus den Planer-Ereignissen (POST …/scheduler/events/unit/{Tag}/{Tag}): nur Auftraege
     * (type "order", keine Blocker/Abwesenheiten), nach Beginn sortiert.
     */
    fun appointments(json: String, zone: ZoneId = BERLIN): List<Appointment> {
        val arr = rootArray(json, "events") ?: return emptyList()
        val out = ArrayList<Appointment>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!str(o, "type").equals("order", ignoreCase = true)) continue
            val start = time(str(o, "start_date"), zone) ?: continue
            val end = time(str(o, "end_date"), zone) ?: start
            val ad = o.optJSONObject("additionalData") ?: JSONObject()
            out.add(
                Appointment(
                    id = str(o, "id").ifBlank { "t$start" },
                    label = str(o, "label"),
                    start = start,
                    end = end,
                    street = str(ad, "street"),
                    zip = str(ad, "zip"),
                    city = str(ad, "city"),
                    customerName = plain(str(ad, "customerName")),
                    // Terminzusatz voll: "text", sonst additionalData.terminaddition (wie die Dispo-App)
                    note = plain(str(o, "text").ifBlank { str(ad, "terminaddition") }),
                    condition = str(ad, "condition"),
                    technicianIds = ids(o.opt("section_id")),
                ),
            )
        }
        return out.sortedWith(compareBy<Appointment>({ it.start }, { it.label }))
    }

    private fun rootArray(json: String, key: String): JSONArray? {
        val t = json.trim()
        if (t.startsWith("[")) return JSONArray(t)
        if (t.startsWith("{")) {
            val o = JSONObject(t)
            for (k in listOf(key, "data", "items", "result")) o.optJSONArray(k)?.let { return it }
        }
        return null
    }

    /** Text eines Feldes; fehlend oder JSON-null = "". */
    private fun str(o: JSONObject, k: String): String {
        val v = o.opt(k)
        return if (v == null || v == JSONObject.NULL) "" else v.toString().trim()
    }

    private fun ids(v: Any?): List<Int> = when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { v.opt(it)?.toString()?.trim()?.toIntOrNull() }
        is JSONObject -> v.keys().asSequence().mapNotNull { v.opt(it)?.toString()?.trim()?.toIntOrNull() }.toList()
        is Number -> listOf(v.toInt())
        is String -> v.split(',', ';', ' ').mapNotNull { it.trim().toIntOrNull() }
        else -> emptyList()
    }

    private fun phones(v: Any?): List<String> = when (v) {
        is JSONArray -> (0 until v.length()).flatMap { phones(v.opt(it)) }.distinct()
        is JSONObject -> v.keys().asSequence().flatMap { phones(v.opt(it)).asSequence() }.distinct().toList()
        is String -> ContactParser.numbersIn(v)
        is Number -> ContactParser.numbersIn("0" + v.toString())
        else -> emptyList()
    }

    private fun time(s: String, zone: ZoneId): Long? {
        if (s.isBlank()) return null
        s.toLongOrNull()?.let { return it }
        return try {
            OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }

    /** Falls COMP Zeilenumbrueche als HTML liefert: als Text. */
    private fun plain(s: String): String {
        if (!s.contains('<') && !s.contains('&')) return s
        return unescape(s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")).trim()
    }

    fun unescape(s: String): String {
        if (!s.contains('&')) return s
        return Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp);").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                e == "amp" -> "&"
                e == "lt" -> "<"
                e == "gt" -> ">"
                e == "quot" -> "\""
                e == "apos" -> "'"
                else -> " "
            }
        }
    }
}
