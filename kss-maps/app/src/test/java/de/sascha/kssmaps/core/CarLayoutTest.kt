package de.sascha.kssmaps.core

import de.sascha.kssmaps.core.CarLayout.Area
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLayoutTest {
    private fun dp(v: Int, d: Float) = (v * d).toInt()

    /** Prueft: nichts ueberlappt, alles auf der Flaeche, Knoepfe gross genug. */
    private fun check(w: Int, h: Int, vis: Area, st: Area, d: Float, nav: Boolean, minDp: Int): CarLayout.Result {
        val r = CarLayout.compute(w, h, vis, st, d, 9, nav)
        val boxes = r.grid + listOf(r.zoomOut, r.zoomIn) + listOfNotNull(r.eta)
        for (b in boxes) {
            assertTrue("auf der Flaeche: $b", b.x >= 0 && b.y >= 0 && b.right <= w && b.bottom <= h)
        }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            assertFalse("ueberlappt: ${boxes[i]} / ${boxes[j]}", boxes[i].overlaps(boxes[j]))
        }
        // Tempo-Anzeige (unten) darf weder Raster noch Zoom noch Ankunftsfeld verdecken
        val speed = CarLayout.Box(r.speedLeft, h - r.speedBottomInset - dp(CarLayout.SPEED_H_DP, d), dp(CarLayout.SPEED_W_DP, d), dp(CarLayout.SPEED_H_DP, d))
        for (b in boxes) assertFalse("Tempo ueberlappt $b", speed.overlaps(b))
        assertTrue("Knoepfe ${r.buttonSize / d} dp", r.buttonSize >= dp(minDp, d))
        // Raster liegt im stabilen Bereich (dort blenden sich keine Leisten von Android Auto ein)
        for (b in r.grid) assertTrue("im stabilen Bereich: $b", b.x >= st.left && b.right <= st.right && b.y >= st.top && b.bottom <= st.bottom)
        return r
    }

    @Test
    fun smallScreen800x480() {
        // aelteres Autoradio, 160 dpi; Leiste oben rechts (eine Lupe) verkleinert den stabilen Bereich
        val r = check(800, 480, Area(0, 0, 800, 480), Area(0, 64, 800, 480), 1f, nav = true, minDp = 64)
        assertEquals(2, r.columns)
        assertNotNull(r.eta)
        assertEquals(8, r.eta!!.x)
    }

    @Test
    fun wideScreenWithLeftPanel() {
        // 1280x720 dp, Abbiegekarte links (sichtbarer Bereich beginnt bei 420 dp), PAN-Leiste rechts
        val r = check(1280, 720, Area(420, 0, 1280, 720), Area(420, 80, 1200, 720), 1f, nav = true, minDp = 72)
        // Ankunftsfeld in der linken Spalte unter der Abbiegekarte, Tempo rechts daneben
        assertEquals(8, r.eta!!.x)
        assertTrue(r.eta!!.right <= 420)
        assertEquals(428, r.speedLeft)
    }

    @Test
    fun hiDpiWideScreen() {
        // 1920x720 px bei 240 dpi (Faktor 1,5) – entspricht 1280x480 dp
        check(1920, 720, Area(630, 0, 1920, 720), Area(630, 96, 1800, 720), 1.5f, nav = true, minDp = 64)
    }

    @Test
    fun tallScreenOneColumn() {
        // hohe Bildschirme (z. B. hochkant): eine Spalte mit grossen Knoepfen
        val r = check(1080, 1500, Area(0, 0, 1080, 1500), Area(0, 64, 1080, 1500), 1f, nav = true, minDp = 72)
        assertEquals(1, r.columns)
    }

    @Test
    fun idleHasNoEtaCard() {
        val r = check(800, 480, Area(0, 0, 800, 480), Area(0, 64, 800, 480), 1f, nav = false, minDp = 64)
        assertNull(r.eta)
        assertEquals(8, r.speedLeft)
    }

    @Test
    fun recenterAloneBottomRightInTwoColumns() {
        val r = CarLayout.compute(800, 480, Area(0, 0, 800, 480), Area(0, 64, 800, 480), 1f, 9, true)
        val last = r.grid.last()
        assertEquals(r.grid[1].x, last.x) // rechte Spalte
        assertTrue(r.grid.dropLast(1).all { it.y < last.y })
    }
}
