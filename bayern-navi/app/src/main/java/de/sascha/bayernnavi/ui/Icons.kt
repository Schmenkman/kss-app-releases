package de.sascha.bayernnavi.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import de.sascha.bayernnavi.core.HazardType
import de.sascha.bayernnavi.core.ManeuverKind
import kotlin.math.cos
import kotlin.math.sin

/**
 * Alle Symbole werden zur Laufzeit gezeichnet (keine Bilddateien): Fahrtpfeil, Abbiegepfeile,
 * Meldungs-Symbole, Knopf-Symbole. Gleich fuer Handy, Karte und Android Auto.
 */
object Icons {

    private fun bmp(w: Int, h: Int = w) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    // -----------------------------------------------------------------------------------
    // Fahrtpfeil (eigene Position)
    // -----------------------------------------------------------------------------------

    fun puck(size: Int, color: Int = Color.parseColor("#1A73E8")): Bitmap {
        val b = bmp(size)
        val c = Canvas(b)
        val s = size.toFloat()
        val path = Path().apply {
            moveTo(0.5f * s, 0.06f * s)
            lineTo(0.88f * s, 0.90f * s)
            lineTo(0.5f * s, 0.70f * s)
            lineTo(0.12f * s, 0.90f * s)
            close()
        }
        val shadow = fill(Color.argb(70, 0, 0, 0))
        c.save()
        c.translate(0f, 0.03f * s)
        c.drawPath(path, shadow)
        c.restore()
        c.drawPath(path, fill(color))
        c.drawPath(path, stroke(Color.WHITE, 0.07f * s))
        return b
    }

    // -----------------------------------------------------------------------------------
    // Abbiegepfeile
    // -----------------------------------------------------------------------------------

    fun angleFor(kind: ManeuverKind, hint: Double?): Double = when (kind) {
        ManeuverKind.SLIGHT_RIGHT, ManeuverKind.RAMP_RIGHT, ManeuverKind.EXIT_RIGHT, ManeuverKind.MERGE_RIGHT -> 40.0
        ManeuverKind.KEEP_RIGHT -> 28.0
        ManeuverKind.RIGHT -> 90.0
        ManeuverKind.SHARP_RIGHT -> 135.0
        ManeuverKind.SLIGHT_LEFT, ManeuverKind.RAMP_LEFT, ManeuverKind.EXIT_LEFT, ManeuverKind.MERGE_LEFT -> -40.0
        ManeuverKind.KEEP_LEFT -> -28.0
        ManeuverKind.LEFT -> -90.0
        ManeuverKind.SHARP_LEFT -> -135.0
        ManeuverKind.MERGE -> hint?.coerceIn(-45.0, 45.0) ?: 0.0
        else -> 0.0
    }

    fun maneuver(kind: ManeuverKind, size: Int, color: Int = Color.WHITE, roundaboutExit: Int = 0, angleHint: Double? = null): Bitmap {
        val b = bmp(size)
        val c = Canvas(b)
        val s = size.toFloat()
        val line = stroke(color, 0.12f * s)
        val f = fill(color)
        when (kind) {
            ManeuverKind.ROUNDABOUT, ManeuverKind.ROUNDABOUT_EXIT -> drawRoundabout(c, s, color, roundaboutExit, angleHint)
            ManeuverKind.UTURN -> {
                val p = Path().apply {
                    moveTo(0.64f * s, 0.92f * s)
                    lineTo(0.64f * s, 0.42f * s)
                    arcTo(RectF(0.34f * s, 0.24f * s, 0.64f * s, 0.54f * s), 0f, -180f, false)
                    lineTo(0.34f * s, 0.62f * s)
                }
                c.drawPath(p, line)
                arrowHead(c, s, 0.34f * s, 0.80f * s, 180.0, f)
            }
            ManeuverKind.ARRIVE, ManeuverKind.ARRIVE_LEFT, ManeuverKind.ARRIVE_RIGHT, ManeuverKind.WAYPOINT -> drawFlag(c, s, color)
            else -> {
                val a = angleFor(kind, angleHint)
                val px = 0.5f * s
                val py = if (a == 0.0) 0.30f * s else 0.52f * s
                c.drawLine(0.5f * s, 0.94f * s, px, py, line)
                if (a == 0.0) {
                    arrowHead(c, s, px, 0.16f * s, 0.0, f)
                } else {
                    val len = 0.26f * s
                    val rad = Math.toRadians(a)
                    val ex = px + (len * sin(rad)).toFloat()
                    val ey = py - (len * cos(rad)).toFloat()
                    c.drawLine(px, py, ex, ey, line)
                    val tipX = ex + (0.10f * s * sin(rad)).toFloat()
                    val tipY = ey - (0.10f * s * cos(rad)).toFloat()
                    arrowHead(c, s, tipX, tipY, a, f)
                    if (kind == ManeuverKind.KEEP_LEFT || kind == ManeuverKind.KEEP_RIGHT) {
                        // angedeutete zweite Spur
                        val other = stroke(Color.argb(110, Color.red(color), Color.green(color), Color.blue(color)), 0.08f * s)
                        val r2 = Math.toRadians(-a)
                        c.drawLine(px, py, px + (len * sin(r2)).toFloat(), py - (len * cos(r2)).toFloat(), other)
                    }
                }
            }
        }
        return b
    }

    /** Pfeilspitze: Spitze bei (x,y), zeigt in Richtung angle (0 = hoch, 90 = rechts). */
    private fun arrowHead(c: Canvas, s: Float, x: Float, y: Float, angle: Double, p: Paint) {
        val rad = Math.toRadians(angle)
        val dx = sin(rad).toFloat()
        val dy = -cos(rad).toFloat()
        val len = 0.22f * s
        val half = 0.17f * s
        val bx = x - dx * len
        val by = y - dy * len
        val path = Path().apply {
            moveTo(x, y)
            lineTo(bx + dy * half, by - dx * half)
            lineTo(bx - dy * half, by + dx * half)
            close()
        }
        c.drawPath(path, p)
    }

    private fun drawRoundabout(c: Canvas, s: Float, color: Int, exit: Int, hint: Double?) {
        val cx = 0.5f * s
        val cy = 0.42f * s
        val r = 0.19f * s
        c.drawCircle(cx, cy, r, stroke(Color.argb(140, Color.red(color), Color.green(color), Color.blue(color)), 0.07f * s))
        c.drawLine(cx, 0.94f * s, cx, cy + r, stroke(color, 0.11f * s))
        // Ausfahrtswinkel: Rechtsverkehr, gegen den Uhrzeigersinn. 1. Ausfahrt rechts, 2. gerade, 3. links …
        val angle = hint ?: when (exit) {
            1 -> 90.0
            2 -> 0.0
            3 -> -90.0
            0 -> 0.0
            else -> -150.0
        }
        val rad = Math.toRadians(angle)
        val sx = cx + (r * sin(rad)).toFloat()
        val sy = cy - (r * cos(rad)).toFloat()
        val ex = cx + ((r + 0.16f * s) * sin(rad)).toFloat()
        val ey = cy - ((r + 0.16f * s) * cos(rad)).toFloat()
        // gefahrener Bogen (vom Einfahrtspunkt unten gegen den Uhrzeigersinn bis zur Ausfahrt)
        // Canvas: 0 Grad = rechts, 90 = unten, positive Richtung = im Uhrzeigersinn.
        // Einfahrt unten (90), Ausfahrt bei (angle - 90); gegen den Uhrzeigersinn = negativer Bogen.
        val startDeg = 90f
        var sweep = (angle - 180.0).toFloat()
        while (sweep > 0f) sweep -= 360f
        while (sweep <= -360f) sweep += 360f
        if (sweep == 0f) sweep = -359f
        c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), startDeg, sweep, false, stroke(color, 0.11f * s))
        c.drawLine(sx, sy, ex, ey, stroke(color, 0.11f * s))
        arrowHead(c, s, ex + (0.08f * s * sin(rad)).toFloat(), ey - (0.08f * s * cos(rad)).toFloat(), angle, fill(color))
        if (exit > 0) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                textSize = 0.20f * s
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            c.drawText(exit.toString(), cx, cy + 0.07f * s, tp)
        }
    }

    private fun drawFlag(c: Canvas, s: Float, color: Int) {
        c.drawLine(0.32f * s, 0.92f * s, 0.32f * s, 0.10f * s, stroke(color, 0.08f * s))
        val p = Path().apply {
            moveTo(0.34f * s, 0.12f * s)
            lineTo(0.82f * s, 0.27f * s)
            lineTo(0.34f * s, 0.44f * s)
            close()
        }
        c.drawPath(p, fill(color))
    }

    // -----------------------------------------------------------------------------------
    // Meldungen auf der Karte
    // -----------------------------------------------------------------------------------

    fun hazardColor(t: HazardType): Int = when (t) {
        HazardType.SPEED_CAMERA -> Color.parseColor("#D32F2F")
        HazardType.JAM -> Color.parseColor("#C62828")
        HazardType.SLOW_TRAFFIC -> Color.parseColor("#EF6C00")
        HazardType.ROADWORKS -> Color.parseColor("#F9A825")
        HazardType.CLOSURE -> Color.parseColor("#6A1B9A")
        HazardType.ACCIDENT -> Color.parseColor("#B71C1C")
        HazardType.DANGER -> Color.parseColor("#E65100")
        HazardType.OTHER -> Color.parseColor("#455A64")
    }

    fun hazardEmoji(t: HazardType): String = when (t) {
        HazardType.SPEED_CAMERA -> "📷"
        HazardType.JAM -> "🚗"
        HazardType.SLOW_TRAFFIC -> "🐢"
        HazardType.ROADWORKS -> "🚧"
        HazardType.CLOSURE -> "⛔"
        HazardType.ACCIDENT -> "💥"
        HazardType.DANGER -> "⚠️"
        HazardType.OTHER -> "ℹ️"
    }

    fun hazard(t: HazardType, size: Int): Bitmap = badge(hazardEmoji(t), hazardColor(t), size)

    /** Weisses Schild mit farbigem Rand und Emoji. */
    fun badge(emoji: String, border: Int, size: Int): Bitmap {
        val b = bmp(size)
        val c = Canvas(b)
        val s = size.toFloat()
        val rect = RectF(0.06f * s, 0.06f * s, 0.94f * s, 0.94f * s)
        c.drawRoundRect(RectF(rect.left, rect.top + 0.03f * s, rect.right, rect.bottom + 0.03f * s), 0.22f * s, 0.22f * s, fill(Color.argb(60, 0, 0, 0)))
        c.drawRoundRect(rect, 0.22f * s, 0.22f * s, fill(Color.WHITE))
        c.drawRoundRect(rect, 0.22f * s, 0.22f * s, stroke(border, 0.09f * s))
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 0.50f * s
            textAlign = Paint.Align.CENTER
        }
        val fm = tp.fontMetrics
        c.drawText(emoji, 0.5f * s, 0.5f * s - (fm.ascent + fm.descent) / 2, tp)
        return b
    }

    fun fuelMarker(size: Int, uta: Boolean): Bitmap = badge("⛽", if (uta) Color.parseColor("#2E7D32") else Color.parseColor("#757575"), size)

    fun destinationPin(size: Int): Bitmap {
        val b = bmp(size)
        val c = Canvas(b)
        val s = size.toFloat()
        val p = Path().apply {
            moveTo(0.5f * s, 0.98f * s)
            cubicTo(0.18f * s, 0.62f * s, 0.12f * s, 0.48f * s, 0.12f * s, 0.38f * s)
            arcTo(RectF(0.12f * s, 0.0f * s, 0.88f * s, 0.76f * s), 180f, 180f, false)
            cubicTo(0.88f * s, 0.48f * s, 0.82f * s, 0.62f * s, 0.5f * s, 0.98f * s)
            close()
        }
        c.drawPath(p, fill(Color.parseColor("#E91E63")))
        c.drawPath(p, stroke(Color.WHITE, 0.05f * s))
        c.drawCircle(0.5f * s, 0.38f * s, 0.13f * s, fill(Color.WHITE))
        return b
    }

    fun avoidMarker(size: Int): Bitmap {
        val b = bmp(size)
        val c = Canvas(b)
        val s = size.toFloat()
        c.drawCircle(0.5f * s, 0.5f * s, 0.44f * s, fill(Color.parseColor("#D32F2F")))
        c.drawCircle(0.5f * s, 0.5f * s, 0.44f * s, stroke(Color.WHITE, 0.06f * s))
        c.drawRect(0.22f * s, 0.42f * s, 0.78f * s, 0.58f * s, fill(Color.WHITE))
        return b
    }

    // -----------------------------------------------------------------------------------
    // Knopf-Symbole (weiss, fuer Handy-Knoepfe und Android Auto)
    // -----------------------------------------------------------------------------------

    enum class Glyph { SEARCH, FUEL, VOLUME_ON, VOLUME_OFF, CLOSE, PLUS, MINUS, RECENTER, HISTORY, AVOID, LAYERS, TRAFFIC, SETTINGS, LIST, REFRESH, NAVIGATE, PAN, JAM_AVOID }

    fun glyph(g: Glyph, size: Int, color: Int = Color.WHITE): Bitmap {
        val b = bmp(size)
        val c = Canvas(b)
        val s = size.toFloat()
        val st = stroke(color, 0.09f * s)
        val f = fill(color)
        when (g) {
            Glyph.SEARCH -> {
                c.drawCircle(0.42f * s, 0.42f * s, 0.22f * s, st)
                c.drawLine(0.58f * s, 0.58f * s, 0.84f * s, 0.84f * s, stroke(color, 0.12f * s))
            }
            Glyph.FUEL -> {
                c.drawRoundRect(RectF(0.18f * s, 0.16f * s, 0.56f * s, 0.86f * s), 0.05f * s, 0.05f * s, f)
                c.drawRect(0.25f * s, 0.24f * s, 0.49f * s, 0.42f * s, fill(Color.TRANSPARENT).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) })
                val hose = Path().apply {
                    moveTo(0.56f * s, 0.34f * s)
                    lineTo(0.72f * s, 0.42f * s)
                    lineTo(0.72f * s, 0.72f * s)
                    quadTo(0.80f * s, 0.80f * s, 0.84f * s, 0.70f * s)
                    lineTo(0.84f * s, 0.36f * s)
                    lineTo(0.74f * s, 0.24f * s)
                }
                c.drawPath(hose, stroke(color, 0.06f * s))
            }
            Glyph.VOLUME_ON, Glyph.VOLUME_OFF -> {
                val sp = Path().apply {
                    moveTo(0.14f * s, 0.38f * s); lineTo(0.30f * s, 0.38f * s); lineTo(0.50f * s, 0.20f * s)
                    lineTo(0.50f * s, 0.80f * s); lineTo(0.30f * s, 0.62f * s); lineTo(0.14f * s, 0.62f * s); close()
                }
                c.drawPath(sp, f)
                if (g == Glyph.VOLUME_ON) {
                    c.drawArc(RectF(0.44f * s, 0.32f * s, 0.70f * s, 0.68f * s), -50f, 100f, false, stroke(color, 0.07f * s))
                    c.drawArc(RectF(0.44f * s, 0.18f * s, 0.88f * s, 0.82f * s), -50f, 100f, false, stroke(color, 0.07f * s))
                } else {
                    c.drawLine(0.60f * s, 0.36f * s, 0.86f * s, 0.64f * s, stroke(color, 0.08f * s))
                    c.drawLine(0.86f * s, 0.36f * s, 0.60f * s, 0.64f * s, stroke(color, 0.08f * s))
                }
            }
            Glyph.CLOSE -> {
                c.drawLine(0.24f * s, 0.24f * s, 0.76f * s, 0.76f * s, st)
                c.drawLine(0.76f * s, 0.24f * s, 0.24f * s, 0.76f * s, st)
            }
            Glyph.PLUS -> {
                c.drawLine(0.5f * s, 0.2f * s, 0.5f * s, 0.8f * s, st)
                c.drawLine(0.2f * s, 0.5f * s, 0.8f * s, 0.5f * s, st)
            }
            Glyph.MINUS -> c.drawLine(0.2f * s, 0.5f * s, 0.8f * s, 0.5f * s, st)
            Glyph.RECENTER -> {
                c.drawCircle(0.5f * s, 0.5f * s, 0.26f * s, stroke(color, 0.07f * s))
                c.drawCircle(0.5f * s, 0.5f * s, 0.11f * s, f)
                c.drawLine(0.5f * s, 0.08f * s, 0.5f * s, 0.24f * s, stroke(color, 0.07f * s))
                c.drawLine(0.5f * s, 0.76f * s, 0.5f * s, 0.92f * s, stroke(color, 0.07f * s))
                c.drawLine(0.08f * s, 0.5f * s, 0.24f * s, 0.5f * s, stroke(color, 0.07f * s))
                c.drawLine(0.76f * s, 0.5f * s, 0.92f * s, 0.5f * s, stroke(color, 0.07f * s))
            }
            Glyph.HISTORY -> {
                c.drawCircle(0.5f * s, 0.5f * s, 0.34f * s, stroke(color, 0.08f * s))
                c.drawLine(0.5f * s, 0.5f * s, 0.5f * s, 0.30f * s, stroke(color, 0.08f * s))
                c.drawLine(0.5f * s, 0.5f * s, 0.65f * s, 0.60f * s, stroke(color, 0.08f * s))
            }
            Glyph.AVOID -> {
                c.drawCircle(0.5f * s, 0.5f * s, 0.34f * s, stroke(color, 0.09f * s))
                c.drawLine(0.27f * s, 0.73f * s, 0.73f * s, 0.27f * s, stroke(color, 0.09f * s))
            }
            Glyph.LAYERS -> {
                val d1 = Path().apply { moveTo(0.5f * s, 0.14f * s); lineTo(0.88f * s, 0.36f * s); lineTo(0.5f * s, 0.58f * s); lineTo(0.12f * s, 0.36f * s); close() }
                c.drawPath(d1, f)
                val d2 = Path().apply { moveTo(0.12f * s, 0.56f * s); lineTo(0.5f * s, 0.78f * s); lineTo(0.88f * s, 0.56f * s) }
                c.drawPath(d2, stroke(color, 0.08f * s))
            }
            Glyph.TRAFFIC -> {
                c.drawRoundRect(RectF(0.30f * s, 0.08f * s, 0.70f * s, 0.92f * s), 0.12f * s, 0.12f * s, stroke(color, 0.07f * s))
                c.drawCircle(0.5f * s, 0.26f * s, 0.09f * s, f)
                c.drawCircle(0.5f * s, 0.50f * s, 0.09f * s, f)
                c.drawCircle(0.5f * s, 0.74f * s, 0.09f * s, f)
            }
            Glyph.SETTINGS -> {
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    c.drawLine(
                        0.5f * s + (0.22f * s * cos(a)).toFloat(), 0.5f * s + (0.22f * s * sin(a)).toFloat(),
                        0.5f * s + (0.38f * s * cos(a)).toFloat(), 0.5f * s + (0.38f * s * sin(a)).toFloat(), stroke(color, 0.12f * s)
                    )
                }
                c.drawCircle(0.5f * s, 0.5f * s, 0.24f * s, f)
                c.drawCircle(0.5f * s, 0.5f * s, 0.10f * s, fill(Color.TRANSPARENT).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) })
            }
            Glyph.LIST -> {
                for (i in 0 until 3) {
                    val y = (0.28f + i * 0.22f) * s
                    c.drawCircle(0.18f * s, y, 0.05f * s, f)
                    c.drawLine(0.32f * s, y, 0.84f * s, y, stroke(color, 0.08f * s))
                }
            }
            Glyph.REFRESH -> {
                c.drawArc(RectF(0.2f * s, 0.2f * s, 0.8f * s, 0.8f * s), -60f, 300f, false, stroke(color, 0.09f * s))
                arrowHead(c, s, 0.80f * s, 0.30f * s, 30.0, f)
            }
            Glyph.NAVIGATE -> {
                val p = Path().apply { moveTo(0.5f * s, 0.10f * s); lineTo(0.84f * s, 0.88f * s); lineTo(0.5f * s, 0.70f * s); lineTo(0.16f * s, 0.88f * s); close() }
                c.drawPath(p, f)
            }
            Glyph.PAN -> {
                c.drawLine(0.5f * s, 0.15f * s, 0.5f * s, 0.85f * s, stroke(color, 0.07f * s))
                c.drawLine(0.15f * s, 0.5f * s, 0.85f * s, 0.5f * s, stroke(color, 0.07f * s))
                arrowHead(c, s, 0.5f * s, 0.08f * s, 0.0, f)
                arrowHead(c, s, 0.5f * s, 0.92f * s, 180.0, f)
                arrowHead(c, s, 0.08f * s, 0.5f * s, -90.0, f)
                arrowHead(c, s, 0.92f * s, 0.5f * s, 90.0, f)
            }
            Glyph.JAM_AVOID -> {
                // Bogen um ein Hindernis
                c.drawRoundRect(RectF(0.40f * s, 0.40f * s, 0.60f * s, 0.62f * s), 0.04f * s, 0.04f * s, f)
                val p = Path().apply { moveTo(0.5f * s, 0.92f * s); lineTo(0.5f * s, 0.80f * s); cubicTo(0.12f * s, 0.74f * s, 0.12f * s, 0.30f * s, 0.5f * s, 0.24f * s) }
                c.drawPath(p, stroke(color, 0.08f * s))
                arrowHead(c, s, 0.66f * s, 0.20f * s, 80.0, f)
            }
        }
        return b
    }

    /** Rundes Tempolimit-Schild (roter Ring). kmh = -1 -> Aufhebungsschild (unbegrenzt). */
    fun speedSign(kmh: Int, size: Int): Bitmap {
        val b = bmp(size)
        val c = Canvas(b)
        val s = size.toFloat()
        c.drawCircle(0.5f * s, 0.5f * s, 0.48f * s, fill(Color.WHITE))
        if (kmh < 0) {
            c.drawCircle(0.5f * s, 0.5f * s, 0.44f * s, stroke(Color.DKGRAY, 0.03f * s))
            for (i in -2..2) {
                val o = i * 0.06f * s
                c.drawLine(0.2f * s + o, 0.8f * s + o, 0.8f * s + o, 0.2f * s + o, stroke(Color.DKGRAY, 0.025f * s))
            }
        } else {
            c.drawCircle(0.5f * s, 0.5f * s, 0.41f * s, stroke(Color.parseColor("#D32F2F"), 0.13f * s))
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                textSize = if (kmh >= 100) 0.36f * s else 0.44f * s
            }
            val fm = tp.fontMetrics
            c.drawText(kmh.toString(), 0.5f * s, 0.5f * s - (fm.ascent + fm.descent) / 2, tp)
        }
        return b
    }
}
