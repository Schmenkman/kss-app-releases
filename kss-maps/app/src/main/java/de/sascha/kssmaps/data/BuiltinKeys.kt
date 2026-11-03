package de.sascha.kssmaps.data

import android.util.Base64
import de.sascha.kssmaps.BuildConfig

/**
 * Beim Bauen eingebaute API-Schluessel (TomTom, Tankerkoenig). Sie kommen aus den GitHub-Secrets
 * KSSMAPS_TOMTOM_KEY und KSSMAPS_TANKERKOENIG_KEY und stehen NIE im (oeffentlichen) Repo.
 * In der APK sind sie leicht verschleiert (kein Klartext fuer automatische Suchen) – das ist kein
 * echter Schutz: wer die APK zerlegt, kann sie auslesen.
 * Ein in den Einstellungen eingetragener eigener Schluessel hat immer Vorrang.
 */
object BuiltinKeys {
    private val mask = "kss-maps".toByteArray()

    private fun decode(s: String): String {
        if (s.isBlank()) return ""
        return try {
            val b = Base64.decode(s, Base64.NO_WRAP)
            String(ByteArray(b.size) { (b[it].toInt() xor mask[it % mask.size].toInt()).toByte() }).trim()
        } catch (e: Exception) {
            ""
        }
    }

    val tomtom: String by lazy { decode(BuildConfig.BUILTIN_TOMTOM) }
    val tankerkoenig: String by lazy { decode(BuildConfig.BUILTIN_TANKERKOENIG) }
}
