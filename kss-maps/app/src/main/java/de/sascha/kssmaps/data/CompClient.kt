package de.sascha.kssmaps.data

import de.sascha.kssmaps.core.Appointment
import de.sascha.kssmaps.core.Comp
import de.sascha.kssmaps.core.Technician
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Anmeldung bei COMP4/betterbits und die zwei Leseabfragen (Techniker, Termine des Tages) –
 * genau wie der Client der KSS-Dispo-App. Eigener Cookie-Speicher je Zugang, Umleitungen werden
 * selbst verfolgt (damit kein Sitzungs-Cookie verloren geht). Laeuft die Sitzung ab (401/403
 * oder Umleitung auf /login), wird neu angemeldet und die Abfrage einmal wiederholt.
 *
 * Nur lesend. Antworten, Zugangsdaten und Kundendaten werden nie protokolliert.
 */
class CompClient(val username: String, private val password: String, private val base: String = Comp.BASE) {

    /** Fehler mit Text fuer die Anzeige; [badLogin] = Benutzer/Passwort falsch. */
    class CompError(message: String, val badLogin: Boolean = false) : IOException(message)

    private class Response(val code: Int, val url: String, val body: String)

    private val cookies = LinkedHashMap<String, String>()
    /** COMP-Benutzer-ID (Header "userId" fuer alle REST-Aufrufe), nach der Anmeldung gesetzt. */
    var userId: String? = null
        private set

    fun samePassword(p: String) = p == password

    // ---------------------------------------------------------------------------------------
    // Anmeldung
    // ---------------------------------------------------------------------------------------

    @Synchronized
    fun login() {
        cookies.clear()
        userId = null
        val loginUrl = "$base/${Comp.LOCALE}/login"
        val page = exchange("GET", loginUrl, accept = HTML)
        if (page.code !in 200..299) throw CompError("COMP nicht erreichbar (Fehler ${page.code})")
        val form = Comp.loginForm(page.body) ?: throw CompError("Die Anmeldeseite von COMP hat sich geändert")
        val action = URL(URL(page.url), form.action.ifBlank { page.url }).toString()
        val fields = LinkedHashMap(form.hidden)
        fields["usernameField"] = username
        fields["passwordField"] = password
        val body = fields.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        val post = exchange("POST", action, body, "application/x-www-form-urlencoded", mapOf("Referer" to loginUrl), accept = HTML)
        if (post.code >= 500) throw CompError("COMP antwortet mit Fehler ${post.code}")
        // Erfolg: die Startseite leitet nicht mehr auf die Anmeldung um
        val home = exchange("GET", "$base/${Comp.LOCALE}/home", accept = HTML)
        if (Comp.isLoginPage(home.url) || home.code == 401 || home.code == 403) {
            throw CompError("Anmeldung bei COMP fehlgeschlagen – Benutzer oder Passwort prüfen", badLogin = true)
        }
        // Benutzer-ID aus dem Planer, sonst von der Startseite (Seiten werden nur danach durchsucht
        // und sofort verworfen – dort steht auch ein Messaging-Kennwort)
        userId = Comp.userId(exchange("GET", "$base/${Comp.LOCALE}/unitscheduler", accept = HTML).body)
            ?: Comp.userId(home.body)
            ?: throw CompError("COMP-Benutzer-ID nicht gefunden – Seitenaufbau geändert?")
    }

    // ---------------------------------------------------------------------------------------
    // Leseabfragen
    // ---------------------------------------------------------------------------------------

    /** Techniker der Schicht, die dieser Zugang sieht (Chef: alle, Mitarbeiter: sich selbst). */
    fun technicians(day: LocalDate): List<Technician> {
        val d = day.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        val json = rest(
            "GET", "/rest/internal/scheduling/technicians",
            mapOf(
                "startDate" to d, "endDate" to d, "contractorId" to "${Comp.CONTRACTOR_ID}",
                "shiftId" to "${Comp.SHIFT_ID}", "mode" to "shift", "companyId" to "${Comp.COMPANY_ID}",
            ),
        )
        return Comp.technicians(json)
    }

    /** Termine (nur Auftraege) des Tages fuer die Techniker – eine Abfrage, sperrt keinen Auftrag. */
    fun appointments(day: LocalDate, technicianIds: List<Int>): List<Appointment> {
        val iso = day.toString()
        val body = JSONObject().put("employeeIds", JSONArray(technicianIds)).put("cssFilter", JSONObject()).toString()
        val json = rest(
            "POST", "/rest/internal/timeline/scheduler/events/unit/$iso/$iso",
            mapOf(
                "auditing" to "false", "showRealOrderDuration" to "false", "shiftId" to "${Comp.SHIFT_ID}",
                "employeeWithArchived" to "false", "driveTime" to "false",
            ),
            body,
        )
        return Comp.appointments(json)
    }

    @Synchronized
    private fun rest(method: String, path: String, query: Map<String, String>, body: String? = null): String {
        if (userId == null) login()
        val url = base + path + "?" + query.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
        fun once(): Response = exchange(
            method, url, body, if (body != null) "application/json" else null,
            mapOf("userId" to (userId ?: "")), accept = JSON,
        )
        var r = once()
        if (r.code == 401 || r.code == 403 || Comp.isLoginPage(r.url)) {
            login() // Sitzung abgelaufen: neu anmelden, genau einmal wiederholen
            r = once()
        }
        if (r.code == 401 || r.code == 403 || Comp.isLoginPage(r.url)) throw CompError("COMP verweigert den Zugriff (Fehler ${r.code})")
        if (r.code !in 200..299) throw CompError("COMP antwortet mit Fehler ${r.code}")
        return r.body.ifBlank { "[]" }
    }

    // ---------------------------------------------------------------------------------------
    // HTTP mit eigenem Cookie-Speicher
    // ---------------------------------------------------------------------------------------

    private fun exchange(
        method: String,
        url: String,
        body: String? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
        accept: String,
    ): Response {
        var u = url
        var m = method
        var b = body
        var ct = contentType
        for (hop in 0 until 8) {
            val c = try {
                URL(u).openConnection() as HttpURLConnection
            } catch (e: Exception) {
                throw CompError("Keine Verbindung zu COMP (Internet?)")
            }
            try {
                c.requestMethod = m
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept", accept)
                c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9")
                if (cookies.isNotEmpty()) c.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                for ((k, v) in headers) c.setRequestProperty(k, v)
                if (b != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", (ct ?: "application/json") + "; charset=utf-8")
                    c.outputStream.use { it.write(b.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                storeCookies(c)
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return Response(code, u, "")
                    u = URL(URL(u), loc).toString()
                    if (code != 307 && code != 308) {
                        m = "GET"
                        b = null
                        ct = null
                    }
                    continue
                }
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                return Response(code, u, text)
            } catch (e: CompError) {
                throw e
            } catch (e: IOException) {
                throw CompError("Keine Verbindung zu COMP (Internet?)")
            } finally {
                c.disconnect()
            }
        }
        throw CompError("COMP leitet endlos um")
    }

    private fun storeCookies(c: HttpURLConnection) {
        for ((k, values) in c.headerFields) {
            if (k == null || !k.equals("Set-Cookie", ignoreCase = true)) continue
            for (v in values) {
                val pair = v.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                if (name.isEmpty() || !pair.contains('=')) continue
                val value = pair.substringAfter('=').trim()
                val expired = Regex("max-age\\s*=\\s*0\\b", RegexOption.IGNORE_CASE).containsMatchIn(v)
                if (value.isEmpty() || expired) cookies.remove(name) else cookies[name] = value
            }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) KSS-Maps"
        private const val HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        private const val JSON = "application/json"
    }
}
