package de.sascha.kssmaps.core

import java.io.File

/**
 * Die eingebauten natuerlichen Stimmen (ohne Android, daher testbar). Zwei Sprachpakete, die die
 * App erst laedt, wenn eine ihrer Stimmen gewaehlt ist:
 *  - Supertonic 3 (Supertone, ~125 MB): 5 Sprecherinnen und 5 Sprecher in einem Paket. Ziffern
 *    spricht das Modell teils englisch – die App schreibt Zahlen vorher als deutsche Woerter aus.
 *  - Piper "Thorsten" (~60 MB): die erste eingebaute Stimme.
 */
object VoiceCatalog {
    enum class Pack(val dir: String, val approxMb: Int) {
        SUPERTONIC("supertonic_3", 125),
        THORSTEN("de_thorsten_medium", 60),
    }

    /** Eine waehlbare Stimme. sid = Sprechernummer im Modell. */
    data class Option(val id: String, val name: String, val female: Boolean, val pack: Pack, val sid: Int)

    val all: List<Option> =
        (1..5).map { Option("st-f$it", "Sprecherin $it", true, Pack.SUPERTONIC, it - 1) } +
            (1..5).map { Option("st-m$it", "Sprecher $it", false, Pack.SUPERTONIC, it + 4) } +
            Option("thorsten", "Thorsten", false, Pack.THORSTEN, 0)

    /** Ohne eigene Wahl in den Einstellungen. */
    const val DEFAULT_ID = "st-f1"
    val default: Option = all.first { it.id == DEFAULT_ID }

    fun byId(id: String?): Option = all.firstOrNull { it.id == id } ?: default

    /** Erste Stimme eines Pakets (spricht, wenn das Paket nur zur Ueberbrueckung startet). */
    fun firstOf(pack: Pack): Option = all.first { it.pack == pack }

    /** Dateien des Supertonic-Pakets (int8-Fassung von sherpa-onnx). */
    val SUPERTONIC_FILES = listOf(
        "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx", "vocoder.int8.onnx",
        "tts.json", "unicode_indexer.bin", "voice.bin",
    )

    /** Ist das ausgepackte Paket vollstaendig? */
    fun isComplete(pack: Pack, dir: File): Boolean = when (pack) {
        Pack.THORSTEN -> VoicePack.isComplete(dir)
        Pack.SUPERTONIC -> SUPERTONIC_FILES.all { File(dir, it).let { f -> f.isFile && f.length() > 0 } }
    }

    /** Ansagetext (schon [SpeechText.normalize]d) fuer das Modell des Pakets. */
    fun text(pack: Pack, normalized: String): String =
        if (pack == Pack.SUPERTONIC) SpeechText.spellNumbers(normalized) else normalized

    /** Supertonic: Rechenschritte fuer beste Qualitaet (wie in den Hoerproben). */
    const val MAX_STEPS = 8

    /**
     * Weniger Rechenschritte, wenn das Handy zu langsam rechnet (rtf = Rechenzeit / Sprechdauer):
     * eine Ansage soll ohne spuerbare Verzoegerung kommen. 5 ist der Standard des Modells.
     */
    fun steps(current: Int, rtf: Double): Int = when {
        rtf > 0.45 && current > 4 -> 4
        rtf > 0.30 && current > 5 -> 5
        else -> current
    }
}
