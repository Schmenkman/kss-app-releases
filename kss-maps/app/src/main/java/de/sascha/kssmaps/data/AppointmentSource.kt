package de.sascha.kssmaps.data

import de.sascha.kssmaps.core.Appointment

/** Woher die Termine des Tages kommen (Auftragssystem). KSS-Maps liest nur. */
interface AppointmentSource {
    /** Zugang eingerichtet? Sonst zeigt die App einen Hinweis statt einer leeren Liste. */
    val configured: Boolean
    /** Termine des aktuellen Tages fuer den gewaehlten Techniker, nach Beginn sortiert. */
    suspend fun today(): List<Appointment>
}

/** Noch kein Zugang eingerichtet. */
object NoAppointmentSource : AppointmentSource {
    override val configured = false
    override suspend fun today(): List<Appointment> = emptyList()
}

/**
 * Nur fuer Test-Builds (Testfahrt im Emulator): erfundene Termine mit erfundenen Namen und
 * Nummern – nie echte Kunden. Adressen sind oeffentliche Orte, damit die Route berechnet werden kann.
 */
object DemoAppointments {
    fun list(now: Long = System.currentTimeMillis()): List<Appointment> {
        val tz = java.util.TimeZone.getTimeZone("Europe/Berlin")
        val day = java.util.Calendar.getInstance(tz).apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        fun at(h: Int, m: Int = 0) = day + (h * 60 + m) * 60_000L
        return listOf(
            Appointment(
                id = "demo-1", label = "TEST-0001", start = at(9), end = at(10, 30),
                street = "Marienplatz 7", zip = "85354", city = "Freising", customerName = "Muster GmbH",
                note = "Termin mit Herr Muster 0871 1234567 besprochen und bestätigt. 9 Uhr. Vorher anrufen",
                condition = "terminiert",
            ),
            Appointment(
                id = "demo-2", label = "TEST-0002", start = at(11), end = at(12),
                street = "Bahnhofstraße 1", zip = "85354", city = "Freising", customerName = "Beispiel Hausverwaltung",
                note = "Termin mit Herr Beispiel 0171 2345678 vereinbart. Bitte vorher Herr Probe 30 min vorher unter 08761/72345 oder 0160-9876543 anrufen",
                condition = "terminiert",
            ),
            Appointment(
                id = "demo-3", label = "TEST-0003", start = at(14), end = at(15),
                street = "Marienplatz 1", zip = "80331", city = "München", customerName = "Testkunde",
                note = "Termin 14 Uhr bestätigt. Kunde erreichbar unter +49 89 1234567",
                condition = "terminiert",
            ),
        )
    }
}
