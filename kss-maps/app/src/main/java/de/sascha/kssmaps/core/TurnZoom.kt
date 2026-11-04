package de.sascha.kssmaps.core

/**
 * Vor einer Abzweigung, Ausfahrt, Gabelung oder einem Kreisverkehr naeher heran: die Kamera zoomt
 * eine Stufe ein und schaut etwas steiler von oben, damit man bei mehreren Strassen kurz
 * hintereinander sieht, welche es ist. Kurz nach dem Manoever geht es sanft zurueck auf den
 * normalen Zoom (nach Tempo). Liegen zwei Manoever dicht hintereinander, bleibt es dazwischen nah.
 */
object TurnZoom {
    /** Zusaetzliche Zoomstufe (1 = alles doppelt so gross). */
    const val BOOST = 1.0
    /** So viel steiler von oben (Grad) waehrend des Heranzoomens. */
    const val TILT_LESS = 10.0
    /** Hoechster Zoom beim Abbiegen. */
    const val MAX_ZOOM = 18.3

    /** Abzweigungen, Ausfahrten, Gabelungen, Kreisverkehr, Zwischenziel und Ziel – nicht "geradeaus". */
    fun relevant(k: ManeuverKind): Boolean = when (k) {
        ManeuverKind.DEPART, ManeuverKind.STRAIGHT, ManeuverKind.CONTINUE, ManeuverKind.NAME_CHANGE,
        ManeuverKind.MERGE, ManeuverKind.MERGE_LEFT, ManeuverKind.MERGE_RIGHT, ManeuverKind.FERRY -> false
        else -> true
    }

    /** Ab hier vor dem Manoever (etwa 9 s Fahrt, mindestens 120 m, hoechstens 350 m). */
    fun leadMeters(speedMps: Double): Double = (speedMps * 9.0).coerceIn(120.0, 350.0)

    /** So lange danach noch nah (etwa 2,5 s, 30–80 m). */
    fun afterMeters(speedMps: Double): Double = (speedMps * 2.5).coerceIn(30.0, 80.0)

    /**
     * Soll gerade herangezoomt sein? [along] = Position auf der Route (m). Mit [wasActive] gilt beim
     * Verlassen 40 m Spielraum, damit der Zoom an der Grenze nicht pendelt.
     */
    fun active(r: Route, along: Double, speedMps: Double, wasActive: Boolean): Boolean {
        val lead = leadMeters(speedMps) + if (wasActive) 40.0 else 0.0
        val after = afterMeters(speedMps)
        for (m in r.maneuvers) {
            val d = m.along - along
            if (d > lead) break
            if (d >= -after && relevant(m.kind)) return true
        }
        return false
    }
}
