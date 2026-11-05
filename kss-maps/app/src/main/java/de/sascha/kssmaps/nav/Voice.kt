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
 * Sprachausgabe (deutsche Stimme). Laeuft ueber den Navigations-Audiokanal, damit Android Auto
 * die Ansage im Auto abspielt und Musik dabei leiser stellt. Sprachausgabe-Programm und Stimme
 * sind waehlbar (z. B. Google oder SherpaTTS mit neuronalen Stimmen); ohne Wahl nimmt die App die
 * beste deutsche Stimme, die ohne Internet funktioniert.
 */
class Voice(ctx: Context, private val prefs: de.sascha.kssmaps.data.Prefs? = null) : TextToSpeech.OnInitListener {
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
    private var tts: TextToSpeech? = create(prefs?.ttsEngine.orEmpty())
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
        pickVoice(t)
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

    private fun create(engine: String): TextToSpeech =
        if (engine.isBlank()) TextToSpeech(app, this) else TextToSpeech(app, this, engine)

    /** Gewaehlte Stimme, sonst die beste deutsche (offline vor online, dann nach Qualitaet). */
    private fun pickVoice(t: TextToSpeech) {
        val list = germanVoices(t)
        val want = prefs?.ttsVoice.orEmpty()
        val v = list.firstOrNull { it.name == want } ?: list.firstOrNull()
        if (v != null) {
            try {
                t.voice = v
            } catch (e: Exception) {
                // Stimme laesst sich nicht setzen: Standardstimme bleibt
            }
        }
        android.util.Log.i("KSSMaps", "TTS_VOICE ${t.defaultEngine} ${v?.name ?: "Standard"} q=${v?.quality ?: 0}")
    }

    private fun germanVoices(t: TextToSpeech): List<android.speech.tts.Voice> = try {
        (t.voices ?: emptySet()).filter { v ->
            v.locale.language == "de" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in (v.features ?: emptySet())
        }.sortedWith(
            compareBy<android.speech.tts.Voice>({ it.isNetworkConnectionRequired }, { -it.quality }, { if (it.locale.country == "DE") 0 else 1 }, { it.name }),
        )
    } catch (e: Exception) {
        emptyList()
    }

    /** Fuer die Einstellungen: installierte Sprachausgabe-Programme (Paket, Name). */
    fun engines(): List<Pair<String, String>> = try {
        tts?.engines?.map { it.name to it.label } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    data class VoiceInfo(val name: String, val label: String)

    /** Fuer die Einstellungen: deutsche Stimmen des aktuellen Programms, beste zuerst. */
    fun voices(): List<VoiceInfo> {
        val t = tts ?: return emptyList()
        return germanVoices(t).mapIndexed { i, v ->
            val q = when {
                v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> "sehr hohe Qualität"
                v.quality >= android.speech.tts.Voice.QUALITY_HIGH -> "hohe Qualität"
                else -> "normale Qualität"
            }
            VoiceInfo(v.name, "Stimme ${i + 1} · $q · ${if (v.isNetworkConnectionRequired) "braucht Internet" else "offline"}")
        }
    }

    val currentVoice: String? get() = try {
        tts?.voice?.name
    } catch (e: Exception) {
        null
    }

    /** Programm bzw. Stimme gewechselt: neu starten (Ansagen in der Zwischenzeit warten kurz). */
    fun reconfigure() {
        ready = false
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            // altes Programm schon weg
        }
        tts = create(prefs?.ttsEngine.orEmpty())
    }

    /** Hoerprobe mit einer typischen Ansage (auch wenn die Ansagen stumm geschaltet sind). */
    fun sample() {
        say("In 300 Metern rechts abbiegen auf die Hauptstraße. Danach links halten.", urgent = true, force = true)
    }

    /** Stimme waehlen (Name aus [voices]) und sofort Probe hoeren. */
    fun selectVoice(name: String) {
        prefs?.ttsVoice = name
        tts?.let { pickVoice(it) }
        sample()
    }

    /** urgent = laufende Ansage abbrechen (z. B. "Jetzt rechts abbiegen"). */
    fun say(text: String, urgent: Boolean = false, force: Boolean = false) {
        android.util.Log.i("KSSMaps", "SAY${if (muted && !force) " (stumm)" else ""}: $text")
        if ((muted && !force) || text.isBlank()) return
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
