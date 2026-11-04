package de.sascha.kssmaps.data

import de.sascha.kssmaps.core.AppointmentDay
import de.sascha.kssmaps.core.Appointments
import de.sascha.kssmaps.core.Comp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Termine des Tages aus COMP mit dem eigenen Zugang des Technikers. Sieht der Zugang mehrere
 * Techniker (Chef), gilt die gespeicherte Auswahl; ohne Auswahl kommt nur die Technikerliste
 * zurueck. Die Termine bleiben nur im Speicher (nichts auf der Platte, nichts im Log).
 */
class CompAppointmentSource(private val prefs: Prefs, private val secrets: SecretStore) : AppointmentSource {
    private val lock = Mutex()
    @Volatile private var client: CompClient? = null

    override val configured: Boolean get() = secrets.has(KEY_USER) && secrets.has(KEY_PASSWORD)

    val user: String get() = secrets.get(KEY_USER)
    val hasPassword: Boolean get() = secrets.has(KEY_PASSWORD)

    fun setUser(u: String) {
        secrets.put(KEY_USER, u.trim())
        client = null
    }

    fun setPassword(p: String) {
        secrets.put(KEY_PASSWORD, p)
        client = null
    }

    /** Zugang und Technikerauswahl loeschen. */
    fun signOut() {
        secrets.remove(KEY_USER, KEY_PASSWORD)
        prefs.appointmentTechnicianId = 0
        prefs.appointmentTechnicianName = ""
        client = null
    }

    private fun clientFor(): CompClient {
        val u = secrets.get(KEY_USER)
        val p = secrets.get(KEY_PASSWORD)
        if (u.isBlank() || p.isBlank()) throw CompClient.CompError("Kein COMP-Zugang eingetragen (Einstellungen)", badLogin = true)
        client?.takeIf { it.username == u && it.samePassword(p) }?.let { return it }
        return CompClient(u, p).also { client = it }
    }

    override suspend fun today(): AppointmentDay = lock.withLock {
        withContext(Dispatchers.IO) {
            val c = clientFor()
            val day = LocalDate.now(Comp.BERLIN)
            val techs = step("Techniker-Liste") { c.technicians(day) }
            val chosen = techs.firstOrNull { it.id == prefs.appointmentTechnicianId } ?: techs.singleOrNull()
            if (chosen == null) {
                AppointmentDay(emptyList(), techs, null) // mehrere Techniker: erst auswaehlen
            } else {
                if (prefs.appointmentTechnicianName != chosen.name) prefs.appointmentTechnicianName = chosen.name
                val list = step("Termine") { c.appointments(day, listOf(chosen.id)) }
                AppointmentDay(Appointments.forTechnician(list, chosen.id), techs, chosen)
            }
        }
    }

    /** Fehler mit dem Schritt benennen ("Termine: COMP antwortet mit Fehler 500") – fuer die Fehlersuche. */
    private inline fun <T> step(name: String, block: () -> T): T = try {
        block()
    } catch (e: CompClient.CompError) {
        if (e.badLogin) throw e else throw CompClient.CompError("$name: ${e.message}")
    }

    companion object {
        private const val KEY_USER = "comp_user"
        private const val KEY_PASSWORD = "comp_password"
    }
}
