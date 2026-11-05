package de.sascha.kssmaps.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import de.sascha.kssmaps.BuildConfig
import de.sascha.kssmaps.core.VoicePack
import de.sascha.kssmaps.data.Http
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Eingebaute natuerliche Stimme: Piper-Sprachmodell "Thorsten" (neuronal, klingt wie ein Mensch),
 * gerechnet von sherpa-onnx komplett auf dem Handy – kostenlos, ohne Konto, ohne Schluessel.
 *
 * Das Sprachpaket (~60 MB) laedt die App beim ersten Start einmalig von ihrer GitHub-Seite
 * (Pruefsumme steht fest in der App), danach spricht sie auch im Funkloch. Bis es bereit ist –
 * und falls etwas schiefgeht – spricht wie bisher die Android-Sprachausgabe.
 */
class NeuralVoice(ctx: Context, private val attrs: AudioAttributes) {
    enum class State { OFF, WAITING, DOWNLOADING, LOADING, READY, UNSUPPORTED }

    private val app = ctx.applicationContext
    private val baseDir = File(app.noBackupFilesDir, "stimme")
    private val modelDir = File(baseDir, PACK)

    @Volatile var state = State.OFF
        private set
    /** Fortschritt des Ladens in Prozent. */
    @Volatile var progress = 0
        private set
    @Volatile var problem: String? = null
        private set
    /** Rechenzeit der letzten Ansage im Verhaeltnis zur Sprechdauer (0,2 = fuenfmal schneller als gesprochen). */
    @Volatile var lastRtf = 0.0
        private set
    @Volatile private var enabled = false
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var sampleRate = 22050
    private var loader: Thread? = null
    private val generation = AtomicInteger()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kss-stimme").apply { isDaemon = true } }
    // nur im Arbeits-Thread benutzt: zuletzt gesprochene Saetze ("Jetzt rechts abbiegen.")
    private val cache = object : LinkedHashMap<String, ShortArray>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ShortArray>) = size > 40
    }

    val ready: Boolean get() = state == State.READY && tts != null
    val busy: Boolean get() = state == State.WAITING || state == State.DOWNLOADING || state == State.LOADING

    /** Einschalten: Sprachpaket laden bzw. Modell starten – im Hintergrund, bei Netzproblemen spaeter erneut. */
    @Synchronized
    fun start() {
        enabled = true
        if (state == State.READY || state == State.UNSUPPORTED || loader?.isAlive == true) return
        loader = Thread({ prepareLoop() }, "kss-stimme-laden").apply {
            isDaemon = true
            start()
        }
    }

    /** Ausschalten: nichts mehr sprechen, ein laufender Download wird angehalten (spaeter fortgesetzt). */
    fun stop() {
        enabled = false
        stopAll()
        if (busy) state = State.OFF
    }

    /** Laufende und wartende Ansagen verwerfen. */
    fun stopAll() {
        generation.incrementAndGet()
    }

    private fun prepareLoop() {
        var pause = 60_000L
        while (enabled) {
            try {
                if (prepare()) return
                if (state == State.UNSUPPORTED) return
            } catch (e: Throwable) {
                problem = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "NEURAL_VOICE Fehler: $problem")
                if (state != State.UNSUPPORTED) state = State.WAITING
            }
            if (!enabled) break
            try {
                Thread.sleep(pause)
            } catch (e: InterruptedException) {
                return
            }
            pause = min(pause * 2, 15 * 60_000L)
        }
        if (state != State.READY && state != State.UNSUPPORTED) state = State.OFF
    }

    /** true = bereit. */
    private fun prepare(): Boolean {
        if (!libraryOk()) return false
        if (BuildConfig.VOICE_URL.isBlank() || BuildConfig.VOICE_SHA256.isBlank()) {
            unsupported("In dieser Version nicht enthalten")
            return false
        }
        if (!installed()) {
            download()
            if (!enabled) return false
        }
        load()
        return true
    }

    private fun unsupported(why: String) {
        problem = why
        state = State.UNSUPPORTED
        Log.i(TAG, "NEURAL_VOICE nicht verfuegbar: $why")
    }

    private fun libraryOk(): Boolean = try {
        System.loadLibrary("sherpa-onnx-jni")
        true
    } catch (e: Throwable) {
        unsupported("Auf diesem Gerät nicht verfügbar (nur für 64-Bit-Handys)")
        false
    }

    private fun installed(): Boolean {
        val ok = File(modelDir, "ok")
        return ok.isFile && ok.readText().trim() == BuildConfig.VOICE_SHA256 && VoicePack.isComplete(modelDir)
    }

    private fun download() {
        baseDir.mkdirs()
        // alte bzw. unvollstaendige Pakete wegraeumen (nicht die angefangene Datei – die wird fortgesetzt)
        baseDir.listFiles()?.filter { it.isDirectory }?.forEach { it.deleteRecursively() }
        val expected = BuildConfig.VOICE_SIZE
        val needed = (if (expected > 0) expected else 70_000_000L) * 2 + 50_000_000L
        if (baseDir.usableSpace < needed) {
            throw IOException("Zu wenig freier Speicher für die Stimme (ca. 150 MB nötig)")
        }
        val part = File(baseDir, "$PACK.zip.part")
        state = State.DOWNLOADING
        var have = if (part.isFile) part.length() else 0L
        if (expected > 0 && have > expected) {
            part.delete()
            have = 0
        }
        val t0 = SystemClock.elapsedRealtime()
        if (expected <= 0 || have < expected) {
            val c = URL(BuildConfig.VOICE_URL).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 20_000
                c.readTimeout = 30_000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", Http.userAgent)
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val code = c.responseCode
                if (code != 200 && code != 206 && code != 416) throw IOException("Sprachpaket: HTTP $code")
                if (code != 416) {
                    val append = code == 206 && have > 0
                    if (!append) have = 0
                    val total = if (expected > 0) expected else c.contentLengthLong.let { if (it > 0) have + it else -1L }
                    Log.i(TAG, "NEURAL_VOICE download start ab=$have von=$total")
                    FileOutputStream(part, append).use { out ->
                        c.inputStream.use { inp ->
                            val buf = ByteArray(128 * 1024)
                            var done = have
                            while (true) {
                                if (!enabled) throw IOException("angehalten")
                                val r = inp.read(buf)
                                if (r < 0) break
                                out.write(buf, 0, r)
                                done += r
                                if (total > 0) progress = (done * 100 / total).toInt().coerceIn(0, 100)
                            }
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
        }
        if (expected > 0 && part.length() != expected) {
            if (part.length() > expected) part.delete()
            throw IOException("Sprachpaket unvollständig (${part.length() / 1_000_000} von ${expected / 1_000_000} MB) – wird fortgesetzt")
        }
        val sha = VoicePack.sha256(part)
        if (sha != BuildConfig.VOICE_SHA256) {
            part.delete()
            throw IOException("Sprachpaket beschädigt (Prüfsumme) – wird neu geladen")
        }
        val tmp = File(baseDir, "neu")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val n = part.inputStream().use { VoicePack.unzip(it, tmp) }
        if (!VoicePack.isComplete(tmp)) {
            tmp.deleteRecursively()
            part.delete()
            throw IOException("Sprachpaket unvollständig")
        }
        File(tmp, "ok").writeText(sha)
        modelDir.deleteRecursively()
        if (!tmp.renameTo(modelDir)) throw IOException("Sprachpaket: Umbenennen fehlgeschlagen")
        part.delete()
        progress = 100
        Log.i(TAG, "NEURAL_VOICE download ok dateien=$n ${(SystemClock.elapsedRealtime() - t0) / 1000}s")
    }

    private fun load() {
        state = State.LOADING
        // Schutz vor Absturz-Schleifen: stuerzt die native Bibliothek beim Laden ab, steht diese
        // Marke beim naechsten Start noch da – nach zwei Fehlversuchen bleibt die Android-Stimme.
        val lock = File(baseDir, "laden_${BuildConfig.VERSION_CODE}.lock")
        val tries = if (lock.isFile) lock.readText().trim().toIntOrNull() ?: 1 else 0
        if (tries >= 2) {
            unsupported("Stimme ließ sich auf diesem Gerät nicht starten")
            return
        }
        lock.writeText((tries + 1).toString())
        try {
            loadModel()
        } finally {
            // nur ein Absturz der nativen Bibliothek laesst die Marke stehen
            lock.delete()
        }
    }

    private fun loadModel() {
        val model = VoicePack.model(modelDir) ?: throw IOException("Sprachmodell fehlt")
        val cores = Runtime.getRuntime().availableProcessors()
        val cfg = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.path,
                    tokens = File(modelDir, "tokens.txt").path,
                    dataDir = File(modelDir, "espeak-ng-data").path,
                ),
                numThreads = if (cores >= 8) 4 else 2,
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        val t0 = SystemClock.elapsedRealtime()
        val t = OfflineTts(config = cfg)
        // Laesst sich das Modell nicht laden, liefert die Bibliothek einen leeren Zeiger – jeder
        // weitere Aufruf wuerde dann abstuerzen.
        val ptr = try {
            OfflineTts::class.java.getDeclaredField("ptr").apply { isAccessible = true }.getLong(t)
        } catch (e: Exception) {
            -1L
        }
        if (ptr == 0L) throw IOException("Sprachmodell ließ sich nicht laden")
        sampleRate = t.sampleRate()
        val t1 = SystemClock.elapsedRealtime()
        // Aufwaermen: die erste Rechnung ist sonst spuerbar langsamer
        t.generate("Route wird berechnet.", 0, SPEED)
        val t2 = SystemClock.elapsedRealtime()
        tts = t
        problem = null
        state = State.READY
        Log.i(TAG, "NEURAL_VOICE ready laden=${t1 - t0}ms aufwaermen=${t2 - t1}ms rate=$sampleRate threads=${cfg.model.numThreads}")
    }

    /**
     * Ansage sprechen. urgent = laufende und wartende Ansagen verwerfen. done(false) heisst:
     * hat nicht geklappt – der Aufrufer nimmt dann die Android-Stimme.
     */
    fun speak(text: String, urgent: Boolean, done: (Boolean) -> Unit) {
        val g = if (urgent) generation.incrementAndGet() else generation.get()
        worker.execute {
            if (generation.get() != g) {
                done(true) // ueberholt: einfach weglassen
                return@execute
            }
            val ok = try {
                play(g, pcm(text))
            } catch (e: Throwable) {
                Log.w(TAG, "NEURAL_SAY Fehler: ${e.message ?: e.javaClass.simpleName}")
                false
            }
            done(ok)
        }
    }

    /** Hoerprobe als WAV-Datei (Testfahrt). */
    fun writeWav(text: String, out: File) {
        worker.execute {
            try {
                val p = pcm(text)
                out.writeBytes(VoicePack.wav(p, sampleRate))
                Log.i(TAG, "NEURAL_WAV ok ${out.length()} Bytes ${p.size * 1000L / sampleRate}ms")
            } catch (e: Throwable) {
                Log.w(TAG, "NEURAL_WAV Fehler: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun pcm(text: String): ShortArray {
        cache[text]?.let {
            Log.i(TAG, "NEURAL_SAY cache ${it.size * 1000L / sampleRate}ms")
            return it
        }
        val t = tts ?: throw IOException("Stimme nicht bereit")
        val t0 = SystemClock.elapsedRealtime()
        val a = t.generate(text, 0, SPEED)
        val ms = SystemClock.elapsedRealtime() - t0
        if (a.samples.isEmpty()) throw IOException("keine Ausgabe")
        sampleRate = a.sampleRate
        val p = VoicePack.toPcm16(a.samples, a.sampleRate)
        val audioMs = a.samples.size * 1000L / a.sampleRate
        lastRtf = if (audioMs > 0) ms.toDouble() / audioMs else 0.0
        Log.i(TAG, "NEURAL_SAY rechnen=${ms}ms sprechen=${audioMs}ms zeichen=${text.length}")
        cache[text] = p
        return p
    }

    /** Spielt PCM ueber den Navigations-Kanal; bricht ab, sobald eine dringendere Ansage kommt. */
    private fun play(g: Int, pcm: ShortArray): Boolean {
        if (generation.get() != g) return true
        priority(Process.THREAD_PRIORITY_AUDIO)
        val rate = sampleRate
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, rate)) // ~0,5 s Puffer
            .build()
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) throw IOException("Audio nicht verfügbar")
            val total = pcm.size
            val limit = SystemClock.elapsedRealtime() + total * 1000L / rate + 4_000
            var off = 0
            var playing = false
            while (off < total) {
                if (generation.get() != g) {
                    track.pause()
                    track.flush()
                    return true
                }
                val w = track.write(pcm, off, min(total - off, rate / 10), AudioTrack.WRITE_NON_BLOCKING)
                if (w < 0) throw IOException("Audio-Fehler $w")
                off += w
                if (!playing) {
                    track.play()
                    playing = true
                }
                if (w == 0) {
                    if (SystemClock.elapsedRealtime() > limit) throw IOException("Audio haengt")
                    Thread.sleep(10)
                }
            }
            // Ausklingen lassen, bis alles gespielt ist
            var lastPos = -1
            var lastMove = SystemClock.elapsedRealtime()
            while (true) {
                if (generation.get() != g) {
                    track.pause()
                    track.flush()
                    return true
                }
                val pos = track.playbackHeadPosition
                if (pos >= total) break
                val now = SystemClock.elapsedRealtime()
                if (pos != lastPos) {
                    lastPos = pos
                    lastMove = now
                } else if (now - lastMove > 2_500 || now > limit) {
                    Log.w(TAG, "NEURAL_PLAY steht bei $pos von $total")
                    break
                }
                Thread.sleep(15)
            }
            track.stop()
            return true
        } finally {
            track.release()
            priority(Process.THREAD_PRIORITY_DEFAULT)
        }
    }

    private fun priority(p: Int) = try {
        Process.setThreadPriority(p)
    } catch (e: Exception) {
        // nicht erlaubt: normale Prioritaet reicht auch
    }

    /** Text fuer die Einstellungen. */
    fun status(on: Boolean): String = when {
        !on -> "Aus – es spricht die Android-Sprachausgabe."
        state == State.READY -> "Bereit: natürliche Stimme „Thorsten“, spricht auch ohne Internet."
        state == State.DOWNLOADING -> "Wird einmalig geladen (ca. 60 MB) … $progress % – bis dahin spricht die Android-Stimme."
        state == State.LOADING -> "Wird gestartet …"
        state == State.UNSUPPORTED -> "${problem ?: "Nicht verfügbar"} – es spricht die Android-Sprachausgabe."
        state == State.WAITING -> "Noch nicht geladen" + (problem?.let { " ($it)" } ?: "") + " – neuer Versuch in wenigen Minuten, bis dahin spricht die Android-Stimme."
        else -> "Wird vorbereitet …"
    }

    companion object {
        private const val TAG = "KSSMaps"
        /** Name des Sprachpakets (Ordner auf dem Handy). Neues Paket = neuer Name. */
        const val PACK = "de_thorsten_medium"
        /** Sprechtempo (1 = wie aufgenommen). */
        const val SPEED = 1.0f
        /** Hoerprobe mit typischen Ansagen (Testfahrt). */
        const val PROBE = "In 300 Metern rechts abbiegen auf die Leopoldstraße. Danach links halten. " +
            "Achtung, Stau in 2,5 Kilometern, etwa 8 Minuten Verzögerung. " +
            "Route gestartet. 23,4 Kilometer, Ankunft gegen 14:35 Uhr. " +
            "Jetzt im Kreisverkehr die dritte Ausfahrt nehmen auf B 13. " +
            "Max Mustermann anrufen? " +
            "Sie haben Ihr Ziel erreicht, es liegt rechts."
    }
}
