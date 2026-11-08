package de.sascha.kssmaps.core

import kotlin.math.max
import kotlin.math.min

/**
 * Anordnung der eigenen Knoepfe auf der Android-Auto-Karte (alles in Pixeln, ohne Android –
 * daher testbar). Android Auto meldet zwei Bereiche: "sichtbar" (gerade frei) und "stabil" (frei,
 * auch wenn die Leisten von Android Auto eingeblendet sind).
 *
 * - Knopf-Raster rechts im stabilen Bereich: eine Spalte, wenn die Knoepfe dort gross genug werden,
 *   sonst zwei Spalten (paarweise, der letzte – Zentrieren – allein unten rechts). Knoepfe moeglichst
 *   gross (bis 76 dp, Empfehlung fuer Auto-Bildschirme).
 * - Unten links waehrend der Navigation das Ankunftsfeld mit Beenden-Knopf (wie Google Maps): in
 *   der linken Spalte unter der Abbiegekarte von Android Auto, wenn es die gibt, sonst ganz links.
 * - Daneben Tempo und Tempolimit, dann Zoom −/+.
 */
object CarLayout {
    data class Box(val x: Int, val y: Int, val w: Int, val h: Int) {
        val right: Int get() = x + w
        val bottom: Int get() = y + h
        fun overlaps(o: Box): Boolean = x < o.right && o.x < right && y < o.bottom && o.y < bottom
    }

    /** Rechteck wie android.graphics.Rect (links, oben, rechts, unten). */
    data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    data class Result(
        /** Knoepfe in der uebergebenen Reihenfolge. */
        val grid: List<Box>,
        val columns: Int,
        val buttonSize: Int,
        val zoomOut: Box,
        val zoomIn: Box,
        /** Tempo-Anzeige: linker Rand und Abstand zum unteren Rand der Flaeche. */
        val speedLeft: Int,
        val speedBottomInset: Int,
        /** Ankunftsfeld mit Beenden-Knopf (nur waehrend der Navigation). */
        val eta: Box?,
        /** Warnbanner oben: so viel Platz rechts frei lassen (Knopf-Raster). */
        val bannerRightInset: Int,
    )

    /** Breite der Tempo-Anzeige (Tempo 70 dp + 8 dp + Schild 62 dp) bzw. Hoehe. */
    const val SPEED_W_DP = 140
    const val SPEED_H_DP = 70

    fun compute(width: Int, height: Int, visible: Area, stable: Area, density: Float, n: Int, navigating: Boolean): Result {
        fun dp(v: Int) = (v * density).toInt()
        val m = dp(8)
        val gap = dp(8)
        val maxS = dp(76)

        // ---- Knopf-Raster rechts ----
        val availH = stable.height - 2 * m
        val one = if (n > 0) (availH - (n - 1) * gap) / n else 0
        val columns: Int
        val size: Int
        if (one >= dp(72) || n <= 1) {
            columns = 1
            size = min(one, maxS).coerceAtLeast(dp(36))
        } else {
            columns = 2
            val rows = (n - 1 + 1) / 2 + 1 // Paare + letzter allein
            size = min((availH - (rows - 1) * gap) / rows, maxS).coerceAtLeast(dp(36))
        }
        val xRight = stable.right - m - size
        val xLeft = xRight - gap - size
        val grid = ArrayList<Box>(n)
        for (i in 0 until n) {
            val (col, row) = when {
                columns == 1 -> 1 to i
                i == n - 1 -> 1 to (n - 1 + 1) / 2 // letzter (Zentrieren) allein unten rechts
                else -> (i % 2) to (i / 2)
            }
            val x = if (col == 1) xRight else xLeft
            grid.add(Box(x, stable.top + m + row * (size + gap), size, size))
        }
        val gridLeft = if (columns == 2) xLeft else xRight

        // ---- unten: Ankunftsfeld, Tempo, Zoom ----
        val z = max(size, dp(64)).coerceAtMost(dp(80))
        val bottom = visible.bottom - m
        var eta: Box? = null
        var afterEta = visible.left + m
        if (navigating) {
            eta = if (visible.left >= dp(220)) {
                // linke Spalte von Android Auto (Abbiegekarte oben): Feld unten in dieser Spalte
                Box(m, bottom - z, min(visible.left - 2 * m, dp(360)), z)
            } else {
                Box(visible.left + m, bottom - z, dp(280), z)
            }
            if (visible.left < dp(220)) afterEta = eta.right + m
        }
        val speedLeft = afterEta
        var zx = speedLeft + dp(SPEED_W_DP) + gap
        var zy = bottom - z
        // Zu wenig Platz bis zum Knopf-Raster: Zoom ueber die Tempo-Anzeige stellen
        val gridBottom = grid.maxOfOrNull { it.bottom } ?: 0
        if (zx + 2 * z + gap > gridLeft - m && zy < gridBottom) {
            zx = speedLeft
            zy = bottom - dp(SPEED_H_DP) - gap - z
        }
        val zoomOut = Box(zx, zy, z, z)
        val zoomIn = Box(zx + z + gap, zy, z, z)
        return Result(
            grid = grid,
            columns = columns,
            buttonSize = size,
            zoomOut = zoomOut,
            zoomIn = zoomIn,
            speedLeft = speedLeft,
            speedBottomInset = height - bottom,
            eta = eta,
            bannerRightInset = width - gridLeft + m,
        )
    }
}
