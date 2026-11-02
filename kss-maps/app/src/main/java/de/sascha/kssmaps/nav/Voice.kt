package de.sascha.kssmaps.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sprachausgabe (deutsche Stimme des Systems). Laeuft ueber den Navigations-Audiokanal, damit
 * Android Auto die Ansage im Auto abspielt und Musik dabei leiser stellt.
 */
class Voice(ctx: Context) : TextToSpeech.OnInitListener {
    private val app = ctx.applicationContext
    private val am = app.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { }
        .build()
    private var tts: TextToSpeech? = TextToSpeech(app, this)
    private var ready = false
    private val pending = ArrayList<String>()
    private val counter = AtomicInteger()
    private val active = AtomicInteger()
    private var tone: ToneGenerator? = null

    @Volatile var muted = false
        set(v) {
            field = v
            if (v) tts?.stop()
        }

    /** Fehlertext, falls keine deutsche Stimme installiert ist. */
    var problem: String? = null
        private set

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            problem = "Sprachausgabe nicht verfügbar"
            return
        }
        val r = t.setLanguage(Locale.GERMANY)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            problem = "Deutsche Stimme fehlt – bitte in den Android-Einstellungen (Sprachausgabe) installieren"
        }
        t.setAudioAttributes(attrs)
        t.setSpeechRate(1.0f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
            override fun onError(utteranceId: String?, errorCode: Int) = finished()
        })
        ready = true
        val queued = ArrayList(pending)
        pending.clear()
        queued.forEach { say(it) }
    }

    private fun finished() {
        if (active.decrementAndGet() <= 0) {
            active.set(0)
            am.abandonAudioFocusRequest(focus)
        }
    }

    /** urgent = laufende Ansage abbrechen (z. B. "Jetzt rechts abbiegen"). */
    fun say(text: String, urgent: Boolean = false) {
        android.util.Log.i("KSSMaps", "SAY${if (muted) " (stumm)" else ""}: $text")
        if (muted || text.isBlank()) return
        val t = tts
        if (!ready || t == null) {
            if (pending.size < 3) pending.add(text)
            return
        }
        am.requestAudioFocus(focus)
        active.incrementAndGet()
        // Kein KEY_PARAM_STREAM: die AudioAttributes (Navigation) sollen gelten, nicht "Musik".
        val params = Bundle()
        val res = t.speak(text, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, "u" + counter.incrementAndGet())
        if (res != TextToSpeech.SUCCESS) finished()
    }

    /** Kurzer Warnton (Tempo zu hoch). */
    fun beep() {
        if (muted) return
        try {
            val g = tone ?: ToneGenerator(AudioManager.STREAM_MUSIC, 70).also { tone = it }
            g.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
        } catch (e: Exception) {
            // manche Geraete haben keinen Tongenerator frei – dann eben ohne Ton
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        tone?.release()
        tone = null
    }
}
