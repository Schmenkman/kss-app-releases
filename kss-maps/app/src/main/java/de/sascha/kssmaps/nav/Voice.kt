package de.sascha.kssmaps.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import de.sascha.kssmaps.core.SpeechText
import de.sascha.kssmaps.core.VoiceCatalog
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sprachausgabe (deutsche Stimme). Laeuft ueber den Navigations-Audiokanal, damit Android Auto
 * die Ansage im Auto abspielt und Musik dabei leiser stellt.
 *
 * Standard ist eine eingebaute natuerliche Stimme ([NeuralVoice], offline; waehlbar: 5 Sprecherinnen,
 * 5 Sprecher, Thorsten). Bis sie bereit ist, und wenn sie ausgeschaltet ist, spricht die Android-Sprachausgabe –
 * Programm und Stimme dafuer sind waehlbar; ohne Wahl die beste deutsche Offline-Stimme.
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
    private val main = Handler(Looper.getMainLooper())
    /** Eingebaute natuerliche Stimme. */
    val neural = NeuralVoice(app, attrs, VoiceCatalog.byId(prefs?.neuralVoiceId)).also { n ->
        n.otherBusy = { ready && tts?.isSpeaking == true }
    }

    @Volatile var muted = false
        set(v) {
            field = v
            if (v) {
                tts?.stop()
                neural.stopAll()
            }
        }

    private val neuralOn: Boolean get() = prefs?.neuralVoice != false

    /** Eingebaute Stimme vorbereiten (beim App-Start; laedt das Sprachpaket einmalig). */
    fun startNeural() {
        if (neuralOn) neural.start()
    }

    /** Schalter "Natürliche Stimme" in den Einstellungen geaendert. */
    fun neuralChanged() {
        if (neuralOn) neural.start() else neural.stop()
    }

    /**
     * Eingebaute Stimme waehlen (Einstellungen). Laeuft ihr Modell schon, gleich eine Probe – sonst
     * wird ihr Paket geladen; bis dahin spricht die bisherige Stimme.
     */
    fun selectNeural(v: VoiceCatalog.Option, sample: Boolean = true) {
        prefs?.neuralVoiceId = v.id
        neural.select(v)
        if (sample && neuralOn && neural.canSpeak(v)) say(SAMPLE, urgent = true, force = true)
    }

    val neuralReady: Boolean get() = neuralOn && neural.ready
    val neuralBusy: Boolean get() = neuralOn && neural.busy
    fun neuralStatus(): String = neural.status(neuralOn)

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
            // Abgebrochen (dringendere Ansage, stumm) oder aus der Warteschlange geworfen: meldet sich
            // NUR hier – ohne das hielte die App den Ton fest und das Radio im Auto bliebe leise.
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
        })
        ready = true
        val queued = ArrayList(pending)
        pending.clear()
        queued.forEach { say(it) }
    }

    /** Ansage beginnt: Ton anfordern (Radio/Musik wird leiser), Waechter starten. */
    private fun begin() {
        active.incrementAndGet()
        am.requestAudioFocus(focus)
        quietChecks = 0
        main.removeCallbacks(focusGuard)
        main.postDelayed(focusGuard, GUARD_MS)
    }

    /** Ansage fertig (oder abgebrochen): sprechen keine mehr, Ton freigeben – Radio wieder laut. */
    private fun finished() {
        if (active.decrementAndGet() <= 0) {
            active.set(0)
            am.abandonAudioFocusRequest(focus)
        }
    }

    private var quietChecks = 0

    /**
     * Waechter: haelt die App den Ton, obwohl nichts mehr spricht (eine Ansage hat sich nicht
     * zurueckgemeldet), wird er nach zwei stillen Pruefungen freigegeben – das Radio bleibt nie leise.
     */
    private val focusGuard = object : Runnable {
        override fun run() {
            if (active.get() <= 0) return
            val speaking = neural.speakingOrQueued || (ready && try {
                tts?.isSpeaking == true
            } catch (e: Exception) {
                false
            })
            quietChecks = if (speaking) 0 else quietChecks + 1
            if (quietChecks >= 2) {
                android.util.Log.w("KSSMaps", "TON_FREI Waechter: nichts spricht mehr (offen=${active.get()}) – Ton freigegeben")
                active.set(0)
                quietChecks = 0
                am.abandonAudioFocusRequest(focus)
                return
            }
            main.postDelayed(this, GUARD_MS)
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
        say(SAMPLE, urgent = true, force = true)
    }

    /** Android-Stimme waehlen (Name aus [voices]) und sofort Probe hoeren – mit genau dieser Stimme. */
    fun selectVoice(name: String) {
        prefs?.ttsVoice = name
        tts?.let { pickVoice(it) }
        neural.stopAll()
        speakSystem(SAMPLE, true)
    }

    /** urgent = laufende Ansage abbrechen (z. B. "Jetzt rechts abbiegen"). */
    fun say(text: String, urgent: Boolean = false, force: Boolean = false) = say(text, urgent, force, 0)

    private fun say(text: String, urgent: Boolean, force: Boolean, attempt: Int) {
        // Eingebaute Stimme startet gerade (App-Start): kurz warten statt mit der Android-Stimme zu
        // sprechen – es soll immer dieselbe Stimme sein
        if (neuralOn && !neural.ready && neural.startingSoon && attempt < 20 && !text.isBlank() && (!muted || force)) {
            main.postDelayed({ say(text, urgent, force, attempt + 1) }, 500)
            return
        }
        android.util.Log.i("KSSMaps", "SAY${if (muted && !force) " (stumm)" else ""}${if (neuralReady) " [natürlich ${neural.speaking?.id}]" else ""}: $text")
        if ((muted && !force) || text.isBlank()) return
        if (neuralReady) {
            if (urgent) try {
                tts?.stop()
            } catch (e: Exception) {
                // Android-Stimme sprach gerade nicht
            }
            // Ton (Radio leiser) erst, wenn die Ansage wirklich beginnt – nicht schon beim Rechnen
            val began = java.util.concurrent.atomic.AtomicBoolean(false)
            neural.speak(
                SpeechText.normalize(text), urgent,
                // rechnet die eingebaute Stimme zu lange: lieber rechtzeitig mit der Android-Stimme
                // (nicht bei Hoerproben – die sollen die gewaehlte Stimme zeigen)
                // Keine Ersatzstimme mehr bei langem Rechnen: es spricht immer die gewaehlte Stimme
                late = null,
                started = { if (began.compareAndSet(false, true)) begin() },
            ) { ok ->
                // Fehler der eingebauten Stimme: diese Ansage mit der Android-Stimme
                if (!ok) main.post { speakSystem(text, false) }
                if (began.get()) finished()
            }
            return
        }
        speakSystem(text, urgent)
    }

    /** Ansage vorausberechnen, die gleich kommt ("Jetzt …") – nur die eingebaute Stimme braucht das. */
    fun prepare(text: String) {
        if (neuralReady && !muted && text.isNotBlank()) neural.prepare(SpeechText.normalize(text))
    }

    private fun speakSystem(text: String, urgent: Boolean) {
        val t = tts
        if (!ready || t == null) {
            if (pending.size < 3) pending.add(text)
            return
        }
        begin()
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

    companion object {
        const val SAMPLE = "In 300 Metern rechts abbiegen auf die Hauptstraße. Danach links halten."
        private const val GUARD_MS = 3_000L
    }

    fun shutdown() {
        neural.stop()
        tts?.stop()
        tts?.shutdown()
        tts = null
        tone?.release()
        tone = null
    }
}
