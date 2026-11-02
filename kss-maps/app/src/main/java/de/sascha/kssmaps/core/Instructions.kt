package de.sascha.kssmaps.core

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Deutsche Anzeige- und Ansagetexte. Bewusst selbst erzeugt statt die Texte des Routers zu
 * uebernehmen: so klingen Valhalla- und TomTom-Routen gleich, und die Entfernung laesst sich
 * sauber voranstellen ("In 300 Metern rechts abbiegen auf Hauptstraße").
 */
object Instructions {

    fun formatDistance(m: Double): String = when {
        m < 0 -> "0 m"
        m < 100 -> "${((m / 10).roundToInt() * 10).coerceAtLeast(10)} m"
        m < 1000 -> "${(m / 50).roundToInt() * 50} m"
        m < 10_000 -> String.format(Locale.GERMANY, "%.1f km", m / 1000)
        else -> "${(m / 1000).roundToInt()} km"
    }

    fun formatDuration(sec: Double): String {
        val min = (sec / 60).roundToInt()
        return when {
            min < 1 -> "< 1 Min."
            min < 60 -> "$min Min."
            else -> "${min / 60} Std. ${min % 60} Min."
        }
    }

    /** "300 Metern", "einem Kilometer", "1,5 Kilometern" – fuer "In … rechts abbiegen". */
    fun spokenDistance(m: Double): String = when {
        m < 1000 -> {
            val r = when {
                m < 75 -> 50
                m < 150 -> 100
                m < 250 -> 200
                else -> ((m / 100).roundToInt() * 100).coerceAtMost(900)
            }
            "$r Metern"
        }
        m < 1250 -> "einem Kilometer"
        m < 10_000 -> {
            val halfKm = (m / 500).roundToInt() / 2.0
            if (halfKm % 1.0 == 0.0) "${halfKm.toInt()} Kilometern"
            else String.format(Locale.GERMANY, "%.1f Kilometern", halfKm)
        }
        else -> "${(m / 1000).roundToInt()} Kilometern"
    }

    fun ordinal(n: Int): String = when (n) {
        1 -> "erste"; 2 -> "zweite"; 3 -> "dritte"; 4 -> "vierte"; 5 -> "fünfte"
        6 -> "sechste"; 7 -> "siebte"; 8 -> "achte"; else -> "$n."
    }

    /** Strassennummern fuer die Sprachausgabe lesbar machen ("St 2084" waere sonst "Sankt 2084"). */
    fun speakableRoad(s: String): String = s
        .replace(Regex("^St\\s?(\\d)"), "Staatsstraße $1")
        .replace(Regex("\\bSt\\s(\\d)"), "Staatsstraße $1")
        .replace(Regex("^Kr\\s?(\\d)"), "Kreisstraße $1")
        .replace(Regex("\\bA(\\d)"), "A $1")
        .replace(Regex("\\bB(\\d)"), "B $1")

    private fun onto(m: Maneuver, prefix: String = " auf "): String =
        m.street?.takeIf { it.isNotBlank() }?.let { prefix + it } ?: ""

    /** " auf A 9 Richtung Nürnberg" – Wegweiser bevorzugt, sonst (optional) der Strassenname. */
    private fun signTarget(m: Maneuver, ontoFallback: Boolean): String {
        val sb = StringBuilder()
        val branch = m.signBranch?.takeIf { it.isNotBlank() }
        if (branch != null) sb.append(" auf ").append(branch)
        else if (ontoFallback && !m.street.isNullOrBlank()) sb.append(" auf ").append(m.street)
        m.signToward?.takeIf { it.isNotBlank() }?.let { sb.append(" Richtung ").append(it) }
        return sb.toString()
    }

    /** Kurztext fuer Banner und Android Auto. */
    fun text(m: Maneuver): String = when (m.kind) {
        ManeuverKind.DEPART -> "Losfahren" + onto(m)
        ManeuverKind.ARRIVE -> "Ziel erreicht"
        ManeuverKind.ARRIVE_LEFT -> "Ziel erreicht – links"
        ManeuverKind.ARRIVE_RIGHT -> "Ziel erreicht – rechts"
        ManeuverKind.WAYPOINT -> "Zwischenziel erreicht"
        ManeuverKind.STRAIGHT -> "Geradeaus weiter" + signTarget(m, true)
        ManeuverKind.CONTINUE -> "Weiter" + onto(m)
        ManeuverKind.NAME_CHANGE -> "Weiter" + onto(m)
        ManeuverKind.SLIGHT_RIGHT -> "Leicht rechts abbiegen" + onto(m)
        ManeuverKind.RIGHT -> "Rechts abbiegen" + onto(m)
        ManeuverKind.SHARP_RIGHT -> "Scharf rechts abbiegen" + onto(m)
        ManeuverKind.SLIGHT_LEFT -> "Leicht links abbiegen" + onto(m)
        ManeuverKind.LEFT -> "Links abbiegen" + onto(m)
        ManeuverKind.SHARP_LEFT -> "Scharf links abbiegen" + onto(m)
        ManeuverKind.UTURN -> "Wenden" + onto(m)
        ManeuverKind.KEEP_RIGHT -> "Rechts halten" + signTarget(m, true)
        ManeuverKind.KEEP_LEFT -> "Links halten" + signTarget(m, true)
        ManeuverKind.RAMP_RIGHT -> "Rechts auf die Auffahrt" + rampTarget(m)
        ManeuverKind.RAMP_LEFT -> "Links auf die Auffahrt" + rampTarget(m)
        ManeuverKind.RAMP_STRAIGHT -> "Geradeaus auf die Auffahrt" + rampTarget(m)
        ManeuverKind.EXIT_RIGHT -> "Ausfahrt" + exitLabel(m) + " nehmen" + signTarget(m, false)
        ManeuverKind.EXIT_LEFT -> "Ausfahrt" + exitLabel(m) + " links nehmen" + signTarget(m, false)
        ManeuverKind.MERGE, ManeuverKind.MERGE_LEFT, ManeuverKind.MERGE_RIGHT -> "Einfädeln" + onto(m)
        ManeuverKind.ROUNDABOUT ->
            if (m.roundaboutExit > 0) "Im Kreisverkehr ${m.roundaboutExit}. Ausfahrt" + onto(m)
            else "In den Kreisverkehr" + onto(m)
        ManeuverKind.ROUNDABOUT_EXIT -> "Kreisverkehr verlassen" + onto(m)
        ManeuverKind.FERRY -> "Fähre nehmen" + onto(m)
    }

    private fun rampTarget(m: Maneuver): String {
        val branch = m.signBranch ?: m.street
        val sb = StringBuilder()
        if (!branch.isNullOrBlank()) sb.append(" ").append(branch)
        if (!m.signToward.isNullOrBlank()) sb.append(" Richtung ").append(m.signToward)
        return sb.toString()
    }

    private fun exitLabel(m: Maneuver): String = m.exitNumber?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""

    /** Ansage ohne Entfernung (fuer "Jetzt …"). Kleingeschrieben, damit "In 300 Metern …" passt. */
    private fun spokenAction(m: Maneuver): String {
        val s = when (m.kind) {
            ManeuverKind.ROUNDABOUT ->
                if (m.roundaboutExit > 0) "im Kreisverkehr die ${ordinal(m.roundaboutExit)} Ausfahrt nehmen" + onto(m)
                else "in den Kreisverkehr fahren"
            ManeuverKind.EXIT_RIGHT, ManeuverKind.EXIT_LEFT ->
                "die Ausfahrt" + exitLabel(m) + (if (m.kind == ManeuverKind.EXIT_LEFT) " links" else "") + " nehmen" +
                    (m.signToward?.let { ", Richtung $it" } ?: "")
            ManeuverKind.ARRIVE, ManeuverKind.ARRIVE_LEFT, ManeuverKind.ARRIVE_RIGHT, ManeuverKind.WAYPOINT -> ""
            else -> text(m).replaceFirstChar { it.lowercase(Locale.GERMANY) }
        }
        return speakableRoad(s)
    }

    private fun arrivalSide(m: Maneuver) = when (m.kind) {
        ManeuverKind.ARRIVE_LEFT -> ", es liegt links"
        ManeuverKind.ARRIVE_RIGHT -> ", es liegt rechts"
        else -> ""
    }

    /** Ansage mit Entfernung; distM == null bedeutet "jetzt". */
    fun voice(m: Maneuver, distM: Double?, isFuelStop: Boolean = false): String {
        if (m.kind == ManeuverKind.WAYPOINT) {
            val what = if (isFuelStop) "die Tankstelle" else "Ihr Zwischenziel"
            return if (distM == null) "Sie haben $what erreicht." else "In ${spokenDistance(distM)} erreichen Sie $what."
        }
        if (m.kind.isArrival) {
            return if (distM == null) "Sie haben Ihr Ziel erreicht${arrivalSide(m)}."
            else "In ${spokenDistance(distM)} erreichen Sie Ihr Ziel${arrivalSide(m)}."
        }
        val action = spokenAction(m)
        return if (distM == null) "Jetzt $action." else "In ${spokenDistance(distM)} $action."
    }

    /** Kurzer Nachsatz, wenn das uebernaechste Manoever direkt folgt. */
    fun thenText(next: Maneuver): String {
        val a = when (next.kind) {
            ManeuverKind.ARRIVE, ManeuverKind.ARRIVE_LEFT, ManeuverKind.ARRIVE_RIGHT -> "erreichen Sie Ihr Ziel"
            ManeuverKind.WAYPOINT -> "erreichen Sie Ihr Zwischenziel"
            else -> spokenAction(next)
        }
        return "Danach $a."
    }

    // ---- Meldungen ----

    fun hazardVoice(h: Hazard, distM: Double): String {
        val d = spokenDistance(distM)
        return when (h.type) {
            HazardType.SPEED_CAMERA -> "Achtung, ${if (h.title.isNotBlank() && h.title != "Blitzer") h.title else "Blitzer"} in $d" + (h.maxSpeed?.let { ", erlaubt sind $it" } ?: "") + "."
            HazardType.DISTANCE_CHECK -> "Achtung, ${if (h.title.startsWith("Mindestabstand")) h.title else "Abstandsmessung"} in $d."
            HazardType.JAM -> "Stau in $d" + delayPart(h) + "."
            HazardType.SLOW_TRAFFIC -> "Stockender Verkehr in $d" + delayPart(h) + "."
            HazardType.ROADWORKS -> "Baustelle in $d."
            HazardType.CLOSURE -> "Achtung, Sperrung in $d."
            HazardType.ACCIDENT -> "Achtung, Unfall in $d."
            HazardType.DANGER -> "Achtung, Gefahrenstelle in $d" + (h.title.takeIf { it.isNotBlank() && it.length < 40 }?.let { ": $it" } ?: "") + "."
            HazardType.OTHER -> "Hinweis in $d: ${h.title}."
        }
    }

    private fun delayPart(h: Hazard): String {
        val min = (h.delaySec / 60.0).roundToInt()
        return if (min >= 2) ", etwa $min Minuten Verzögerung" else ""
    }

    fun hazardShort(h: Hazard): String = when (h.type) {
        HazardType.SPEED_CAMERA -> (if (h.title.isNotBlank()) h.title else "Blitzer") + (h.maxSpeed?.let { " · $it km/h" } ?: "")
        HazardType.DISTANCE_CHECK -> if (h.title.startsWith("Mindestabstand")) h.title else "Abstandsmessung"
        HazardType.JAM, HazardType.SLOW_TRAFFIC -> h.type.label + delayShort(h)
        else -> h.type.label
    }

    private fun delayShort(h: Hazard): String {
        val min = (h.delaySec / 60.0).roundToInt()
        return if (min >= 1) " · +$min Min." else ""
    }
}
