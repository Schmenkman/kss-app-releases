package de.sascha.kssmaps.core

/**
 * Ein Termin des Tages (Auftrag eines Technikers). Kommt aus dem Auftragssystem; KSS-Maps liest
 * nur und aendert nichts. Kundendaten bleiben auf dem Geraet (nur der aktuelle Tag, kein Log).
 */
data class Appointment(
    val id: String,
    /** Auftragsnummer zur Anzeige (z. B. Delphi-Nr.). */
    val label: String,
    /** Beginn/Ende in Millisekunden (epoch). */
    val start: Long,
    val end: Long,
    val street: String,
    val zip: String,
    val city: String,
    val customerName: String,
    /** Terminzusatz: Freitext mit Ansprechpartnern und Hinweisen. */
    val note: String,
    val condition: String = "",
    val technicianIds: List<Int> = emptyList(),
) {
    val address: String
        get() = listOf(street.trim(), "${zip.trim()} ${city.trim()}".trim()).filter { it.isNotBlank() }.joinToString(", ")

    /** Ansprechpartner aus dem Terminzusatz (eigene Nummern ausgenommen). */
    fun contacts(ownNumbers: Collection<String>): ContactParser.Result = ContactParser.parse(note, ownNumbers)
}

/** Techniker der Schicht (aus dem Auftragssystem); [phones] als Waehlziffern. */
data class Technician(val id: Int, val name: String, val phones: List<String> = emptyList())

/** Ergebnis einer Abfrage: die Termine des Tages fuer [technician]. */
data class AppointmentDay(
    val appointments: List<Appointment>,
    /** Alle Techniker, die der Zugang sieht (mehr als einer: Auswahl anbieten). */
    val technicians: List<Technician> = emptyList(),
    /** Fuer wen die Termine gelten; null = bei mehreren noch nicht gewaehlt. */
    val technician: Technician? = null,
)

object Appointments {
    /** Nach Beginn sortiert, nur die des gewuenschten Technikers (null = alle). */
    fun forTechnician(list: List<Appointment>, technicianId: Int?): List<Appointment> =
        list.filter { technicianId == null || it.technicianIds.isEmpty() || technicianId in it.technicianIds }
            .sortedWith(compareBy<Appointment>({ it.start }, { it.label }))

    /** "09:00–10:30" in deutscher Ortszeit. */
    fun timeRange(a: Appointment, zone: java.util.TimeZone = java.util.TimeZone.getTimeZone("Europe/Berlin")): String {
        val f = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMANY).apply { timeZone = zone }
        val s = f.format(java.util.Date(a.start))
        return if (a.end > a.start) "$s–${f.format(java.util.Date(a.end))}" else s
    }
}
