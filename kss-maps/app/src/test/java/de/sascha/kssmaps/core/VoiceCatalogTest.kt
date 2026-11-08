package de.sascha.kssmaps.core

import de.sascha.kssmaps.core.VoiceCatalog.Pack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VoiceCatalogTest {
    @Test
    fun elevenVoicesWithUniqueIds() {
        val all = VoiceCatalog.all
        assertEquals(11, all.size)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        // Supertonic: Sprecherinnen 0-4, Sprecher 5-9 (wie in den Hoerproben)
        val st = all.filter { it.pack == Pack.SUPERTONIC }
        assertEquals((0..9).toList(), st.map { it.sid })
        assertEquals(5, st.count { it.female })
        assertTrue(st.filter { it.female }.all { it.sid in 0..4 })
        assertEquals("Sprecherin 3", VoiceCatalog.byId("st-f3").name)
        assertEquals(6, VoiceCatalog.byId("st-m2").sid)
    }

    @Test
    fun defaultIsAFemaleVoiceAndUnknownIdsFallBack() {
        assertTrue(VoiceCatalog.default.female)
        assertEquals(VoiceCatalog.default, VoiceCatalog.byId(""))
        assertEquals(VoiceCatalog.default, VoiceCatalog.byId(null))
        assertEquals(VoiceCatalog.default, VoiceCatalog.byId("gibt-es-nicht"))
        assertEquals(Pack.THORSTEN, VoiceCatalog.byId("thorsten").pack)
        assertEquals("st-f1", VoiceCatalog.firstOf(Pack.SUPERTONIC).id)
        assertEquals("thorsten", VoiceCatalog.firstOf(Pack.THORSTEN).id)
    }

    @Test
    fun fewerStepsOnlyWhenTheDeviceIsSlow() {
        assertEquals(8, VoiceCatalog.steps(8, 0.20))
        assertEquals(5, VoiceCatalog.steps(8, 0.35))
        assertEquals(4, VoiceCatalog.steps(8, 0.60))
        assertEquals(5, VoiceCatalog.steps(5, 0.40))
        assertEquals(4, VoiceCatalog.steps(5, 0.50))
        assertEquals(4, VoiceCatalog.steps(4, 0.90))
    }

    @Test
    fun supertonicGetsSpelledOutNumbersThorstenNot() {
        val t = SpeechText.normalize("In 300 m rechts abbiegen. Ankunft gegen 14:35 Uhr.")
        assertEquals("In dreihundert Meter rechts abbiegen. Ankunft gegen vierzehn Uhr fünfunddreißig.", VoiceCatalog.text(Pack.SUPERTONIC, t))
        assertEquals(t, VoiceCatalog.text(Pack.THORSTEN, t))
    }

    @Test
    fun supertonicPackCompleteOnlyWithAllFiles() {
        val dir = Files.createTempDirectory("st").toFile()
        try {
            assertFalse(VoiceCatalog.isComplete(Pack.SUPERTONIC, dir))
            for (f in VoiceCatalog.SUPERTONIC_FILES.dropLast(1)) File(dir, f).writeText("x")
            assertFalse(VoiceCatalog.isComplete(Pack.SUPERTONIC, dir))
            File(dir, VoiceCatalog.SUPERTONIC_FILES.last()).writeText("")
            assertFalse("leere Datei zaehlt nicht", VoiceCatalog.isComplete(Pack.SUPERTONIC, dir))
            File(dir, VoiceCatalog.SUPERTONIC_FILES.last()).writeText("x")
            assertTrue(VoiceCatalog.isComplete(Pack.SUPERTONIC, dir))
            assertFalse("kein Thorsten-Paket", VoiceCatalog.isComplete(Pack.THORSTEN, dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
