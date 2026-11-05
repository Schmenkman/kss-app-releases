package de.sascha.kssmaps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VoicePackTest {
    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return bo.toByteArray()
    }

    @Test
    fun unpacksCompletePack() {
        val dir = Files.createTempDirectory("stimme").toFile()
        try {
            val n = VoicePack.unzip(
                ByteArrayInputStream(
                    zip(
                        "de_DE-thorsten-medium.onnx" to "modell",
                        "tokens.txt" to "_ 0",
                        "espeak-ng-data/phontab" to "x",
                        "espeak-ng-data/de_dict" to "x",
                        "espeak-ng-data/lang/gmw/de" to "name German",
                    ),
                ),
                dir,
            )
            assertEquals(5, n)
            assertTrue(VoicePack.isComplete(dir))
            assertEquals("de_DE-thorsten-medium.onnx", VoicePack.model(dir)?.name)
            File(dir, "espeak-ng-data/de_dict").delete()
            assertFalse(VoicePack.isComplete(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun rejectsEntriesOutsideTheFolder() {
        val dir = Files.createTempDirectory("stimme").toFile()
        try {
            assertNull(VoicePack.safeEntry(dir, "../boese.txt"))
            assertNull(VoicePack.safeEntry(dir, "a/../../boese.txt"))
            assertEquals(File(dir, "a/b.txt").canonicalFile, VoicePack.safeEntry(dir, "a/b.txt"))
            var failed = false
            try {
                VoicePack.unzip(ByteArrayInputStream(zip("../boese.txt" to "x")), dir)
            } catch (e: java.io.IOException) {
                failed = true
            }
            assertTrue(failed)
            assertFalse(File(dir.parentFile, "boese.txt").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun pcmIsEvenlyLoudWithLeadingSilence() {
        val rate = 1000
        val pcm = VoicePack.toPcm16(floatArrayOf(0.1f, -0.3f, 0.05f), rate, leadMs = 100, tailMs = 20)
        assertEquals(100 + 3 + 20, pcm.size)
        assertTrue(pcm.take(100).all { it.toInt() == 0 })
        // lauteste Stelle auf ~ -1 dB angehoben (0,3 -> 0,89), hoechstens 4-fach
        assertEquals((-0.89f * 32767).toInt(), pcm[101].toInt(), 2)
        val quiet = VoicePack.toPcm16(floatArrayOf(0.02f), rate, 0, 0)
        assertEquals((0.08f * 32767).toInt(), quiet[0].toInt(), 2)
    }

    @Test
    fun wavHeader() {
        val w = VoicePack.wav(shortArrayOf(1, -1, 300), 22050)
        assertEquals(44 + 6, w.size)
        assertEquals("RIFF", String(w, 0, 4))
        assertEquals("WAVE", String(w, 8, 4))
        assertEquals("data", String(w, 36, 4))
        val rate = (w[24].toInt() and 0xff) or ((w[25].toInt() and 0xff) shl 8) or ((w[26].toInt() and 0xff) shl 16)
        assertEquals(22050, rate)
        assertEquals(6, w[40].toInt())
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("erwartet $expected, war $actual", kotlin.math.abs(expected - actual) <= tolerance)
}
