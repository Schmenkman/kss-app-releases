package de.sascha.bayernnavi.car

import android.graphics.Bitmap
import android.graphics.Color
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.core.graphics.drawable.IconCompat
import de.sascha.bayernnavi.core.Instructions
import de.sascha.bayernnavi.core.Maneuver
import de.sascha.bayernnavi.core.ManeuverKind
import de.sascha.bayernnavi.ui.Icons
import java.util.TimeZone
import kotlin.math.roundToInt
import androidx.car.app.navigation.model.Maneuver as CarManeuver

/** Umrechnung App-Modell -> Android-Auto-Vorlagen. */
object CarUtil {

    fun icon(b: Bitmap): CarIcon = CarIcon.Builder(IconCompat.createWithBitmap(b)).build()

    fun glyph(g: Icons.Glyph, tint: CarColor? = null): CarIcon {
        val b = CarIcon.Builder(IconCompat.createWithBitmap(Icons.glyph(g, 96, Color.WHITE)))
        if (tint != null) b.setTint(tint)
        return b.build()
    }

    fun action(g: Icons.Glyph, title: String? = null, tint: CarColor? = null, onClick: () -> Unit): Action {
        val b = Action.Builder().setIcon(glyph(g, tint)).setOnClickListener { onClick() }
        if (title != null) b.setTitle(title)
        return b.build()
    }

    fun textAction(title: String, onClick: () -> Unit): Action =
        Action.Builder().setTitle(title).setOnClickListener { onClick() }.build()

    fun distance(m: Double): Distance = when {
        m < 1000 -> Distance.create(((m / 10).roundToInt() * 10).coerceAtLeast(0).toDouble(), Distance.UNIT_METERS)
        m < 10_000 -> Distance.create(m / 1000.0, Distance.UNIT_KILOMETERS_P1)
        else -> Distance.create((m / 1000.0).roundToInt().toDouble(), Distance.UNIT_KILOMETERS)
    }

    fun travelEstimate(remainingM: Double, remainingSec: Double, delaySec: Double): TravelEstimate {
        val arrival = DateTimeWithZone.create(System.currentTimeMillis() + (remainingSec * 1000).toLong(), TimeZone.getDefault())
        return TravelEstimate.Builder(distance(remainingM), arrival)
            .setRemainingTimeSeconds(remainingSec.toLong().coerceAtLeast(0))
            .setRemainingTimeColor(
                when {
                    delaySec >= 600 -> CarColor.RED
                    delaySec >= 120 -> CarColor.YELLOW
                    else -> CarColor.GREEN
                }
            )
            .build()
    }

    fun carManeuverType(m: Maneuver): Int = when (m.kind) {
        ManeuverKind.DEPART -> CarManeuver.TYPE_DEPART
        ManeuverKind.ARRIVE -> CarManeuver.TYPE_DESTINATION
        ManeuverKind.ARRIVE_LEFT -> CarManeuver.TYPE_DESTINATION_LEFT
        ManeuverKind.ARRIVE_RIGHT -> CarManeuver.TYPE_DESTINATION_RIGHT
        ManeuverKind.WAYPOINT -> CarManeuver.TYPE_DESTINATION
        ManeuverKind.STRAIGHT, ManeuverKind.CONTINUE -> CarManeuver.TYPE_STRAIGHT
        ManeuverKind.NAME_CHANGE -> CarManeuver.TYPE_NAME_CHANGE
        ManeuverKind.SLIGHT_RIGHT -> CarManeuver.TYPE_TURN_SLIGHT_RIGHT
        ManeuverKind.RIGHT -> CarManeuver.TYPE_TURN_NORMAL_RIGHT
        ManeuverKind.SHARP_RIGHT -> CarManeuver.TYPE_TURN_SHARP_RIGHT
        ManeuverKind.SLIGHT_LEFT -> CarManeuver.TYPE_TURN_SLIGHT_LEFT
        ManeuverKind.LEFT -> CarManeuver.TYPE_TURN_NORMAL_LEFT
        ManeuverKind.SHARP_LEFT -> CarManeuver.TYPE_TURN_SHARP_LEFT
        ManeuverKind.UTURN -> CarManeuver.TYPE_U_TURN_LEFT
        ManeuverKind.KEEP_RIGHT -> CarManeuver.TYPE_KEEP_RIGHT
        ManeuverKind.KEEP_LEFT -> CarManeuver.TYPE_KEEP_LEFT
        ManeuverKind.RAMP_RIGHT -> CarManeuver.TYPE_ON_RAMP_SLIGHT_RIGHT
        ManeuverKind.RAMP_LEFT -> CarManeuver.TYPE_ON_RAMP_SLIGHT_LEFT
        ManeuverKind.RAMP_STRAIGHT -> CarManeuver.TYPE_STRAIGHT
        ManeuverKind.EXIT_RIGHT -> CarManeuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
        ManeuverKind.EXIT_LEFT -> CarManeuver.TYPE_OFF_RAMP_SLIGHT_LEFT
        ManeuverKind.MERGE -> CarManeuver.TYPE_MERGE_SIDE_UNSPECIFIED
        ManeuverKind.MERGE_LEFT -> CarManeuver.TYPE_MERGE_LEFT
        ManeuverKind.MERGE_RIGHT -> CarManeuver.TYPE_MERGE_RIGHT
        ManeuverKind.ROUNDABOUT -> if (m.roundaboutExit > 0) CarManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW else CarManeuver.TYPE_ROUNDABOUT_ENTER_CCW
        ManeuverKind.ROUNDABOUT_EXIT -> CarManeuver.TYPE_ROUNDABOUT_EXIT_CCW
        ManeuverKind.FERRY -> CarManeuver.TYPE_FERRY_BOAT
    }

    fun step(m: Maneuver, fuelStopName: String? = null): Step {
        val type = carManeuverType(m)
        val mb = CarManeuver.Builder(type)
            .setIcon(icon(Icons.maneuver(m.kind, 128, Color.WHITE, m.roundaboutExit, m.turnAngle)))
        if (type == CarManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW) mb.setRoundaboutExitNumber(m.roundaboutExit)
        val cue = if (m.kind == ManeuverKind.WAYPOINT && fuelStopName != null) "Tankstelle: $fuelStopName" else Instructions.text(m)
        val sb = Step.Builder(cue).setManeuver(mb.build())
        m.street?.takeIf { it.isNotBlank() }?.let { sb.setRoad(it) }
        return sb.build()
    }

    /** Wie viele Listeneintraege der Bildschirm im Auto zulaesst. */
    fun listLimit(ctx: CarContext, wanted: Int): Int = try {
        if (ctx.carAppApiLevel >= 2) minOf(wanted, ctx.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST))
        else minOf(wanted, 6)
    } catch (e: Exception) {
        minOf(wanted, 6)
    }
}
