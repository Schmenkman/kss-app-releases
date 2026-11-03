package de.sascha.kssmaps.core

import org.json.JSONArray

/**
 * Update-Pruefung ueber die GitHub-Releases des Repos (dort liegen die APKs). Frueher las die App
 * kss-maps/version.json im main-Branch – den gibt es fuer KSS-Maps nicht (404), die Pruefung lief
 * ins Leere. Jetzt: neuestes veroeffentlichtes Release "kssmaps-v<version>" mit Anhang kss-maps.apk.
 */
object UpdateCheck {
    const val RELEASES_URL = "https://api.github.com/repos/Schmenkman/kss-app-releases/releases?per_page=40"

    data class Info(val version: String, val notes: List<String>, val apkUrl: String)

    /** "1.10.0" > "1.9.3"; fehlende Stellen zaehlen als 0. */
    fun compare(a: String, b: String): Int {
        val pa = a.trim().split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.trim().split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
            if (d != 0) return if (d > 0) 1 else -1
        }
        return 0
    }

    /** Neuestes KSS-Maps-Release aus der Antwort von [RELEASES_URL] (ohne Entwuerfe/Vorabversionen). */
    fun latest(json: String): Info? {
        val arr = JSONArray(json)
        var best: Info? = null
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val tag = r.optString("tag_name")
            if (!tag.startsWith("kssmaps-v")) continue
            val version = tag.removePrefix("kssmaps-v").trim()
            if (version.isEmpty() || !version[0].isDigit()) continue
            val assets = r.optJSONArray("assets") ?: continue
            var apk: String? = null
            for (k in 0 until assets.length()) {
                val a = assets.optJSONObject(k) ?: continue
                if (a.optString("name") == "kss-maps.apk") apk = a.optString("browser_download_url").takeIf { it.startsWith("https://") }
            }
            if (apk == null) continue
            // Release-Text: Stichpunkte "- …" sind die Neuerungen (ohne Installations-/Build-Hinweise)
            val notes = r.optString("body").lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }
            val info = Info(version, notes, apk)
            if (best == null || compare(version, best.version) > 0) best = info
        }
        return best
    }
}
