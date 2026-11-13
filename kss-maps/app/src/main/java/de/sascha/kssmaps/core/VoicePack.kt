package de.sascha.kssmaps.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Hilfen fuer das Sprachpaket der eingebauten Stimme (ohne Android, daher testbar). */
object VoicePack {
    /** Ist das ausgepackte Sprachpaket vollstaendig (Modell, Zeichen, Aussprache-Daten Deutsch)? */
    fun isComplete(dir: File): Boolean =
        model(dir) != null && File(dir, "tokens.txt").isFile &&
            File(dir, "espeak-ng-data/phontab").isFile && File(dir, "espeak-ng-data/de_dict").isFile

    fun model(dir: File): File? = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".onnx") }?.maxByOrNull { it.length() }

    /** Ziel eines ZIP-Eintrags – null, wenn er aus dem Zielordner herausfuehren wuerde ("../"). */
    fun safeEntry(root: File, name: String): File? {
        val base = root.canonicalFile
        val f = File(base, name).canonicalFile
        return if (f.path.startsWith(base.path + File.separator)) f else null
    }

    /** Packt ein ZIP sicher aus; Rueckgabe: Anzahl Dateien. */
    fun unzip(input: InputStream, dest: File): Int {
        var n = 0
        ZipInputStream(input.buffered(64 * 1024)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val f = safeEntry(dest, e.name) ?: throw java.io.IOException("Ungültiger Eintrag im Sprachpaket: ${e.name}")
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zin.copyTo(it, 64 * 1024) }
                    n++
                }
            }
        }
        return n
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val r = inp.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Modell-Ausgabe (-1..1) als 16-Bit-PCM: gleichmaessig laut (Spitze bei ~ -1 dB, hoechstens
     * 4-fach verstaerkt), mit etwas Stille vorne – Bluetooth und Android Auto schneiden sonst
     * gern den Anfang ab.
     */
    fun toPcm16(samples: FloatArray, rate: Int, leadMs: Int = 150, tailMs: Int = 60, boost: Float = 1f): ShortArray {
        var peak = 0f
        for (v in samples) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        val gain = if (peak > 0.01f) min(0.89f / peak, 4f) else 1f
        val lead = rate * leadMs / 1000
        val tail = rate * tailMs / 1000
        val out = ShortArray(lead + samples.size + tail)
        for (i in samples.indices) {
            var v = samples[i] * gain
            // boost > 1: leise Stellen anheben, Spitzen weich begrenzen – Sprache wird neben
            // (laut gemasterter) Musik deutlich besser hoerbar, ohne zu uebersteuern
            if (boost > 1f) v = (kotlin.math.tanh((v * boost).toDouble()) / kotlin.math.tanh(boost.toDouble()) * 0.95).toFloat()
            out[lead + i] = (v * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** WAV-Datei (16 Bit, mono) – fuer die Hoerprobe der Testfahrt. */
    fun wav(pcm: ShortArray, rate: Int): ByteArray {
        val data = pcm.size * 2
        val o = ByteArrayOutputStream(44 + data)
        fun i32(v: Int) = o.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun i16(v: Int) = o.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        o.write("RIFF".toByteArray()); i32(36 + data); o.write("WAVE".toByteArray())
        o.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
        o.write("data".toByteArray()); i32(data)
        val b = ByteArray(data)
        for (i in pcm.indices) {
            b[2 * i] = pcm[i].toInt().toByte()
            b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        o.write(b)
        return o.toByteArray()
    }
}
