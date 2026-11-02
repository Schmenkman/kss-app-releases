package de.sascha.bayernnavi.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

/**
 * Schlanker HTTP-Client auf Basis von HttpURLConnection (gzip macht Android selbst).
 * Bewusst ohne Bibliothek: weniger Abhaengigkeiten, weniger Ueberraschungen im Build.
 */
object Http {
    var userAgent = "BayernNavi/0.1 (Android; private Navigations-App)"

    class HttpError(val code: Int, val body: String) : IOException("HTTP $code")

    fun get(url: String, timeoutMs: Int = 15_000, headers: Map<String, String> = emptyMap()): String =
        request("GET", url, null, null, timeoutMs, headers)

    fun post(
        url: String,
        body: String,
        contentType: String = "application/json",
        timeoutMs: Int = 20_000,
        headers: Map<String, String> = emptyMap(),
    ): String = request("POST", url, body, contentType, timeoutMs, headers)

    private fun request(
        method: String,
        url: String,
        body: String?,
        contentType: String?,
        timeoutMs: Int,
        headers: Map<String, String>,
    ): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = min(timeoutMs, 10_000)
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "application/json, */*")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", (contentType ?: "application/json") + "; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, text)
            return text
        } finally {
            c.disconnect()
        }
    }
}
