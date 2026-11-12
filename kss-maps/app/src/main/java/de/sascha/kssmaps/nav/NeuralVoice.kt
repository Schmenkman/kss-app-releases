package de.sascha.kssmaps.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import de.sascha.kssmaps.BuildConfig
import de.sascha.kssmaps.core.VoiceCatalog
import de.sascha.kssmaps.core.VoiceCatalog.Pack
import de.sascha.kssmaps.core.VoicePack
import de.sascha.kssmaps.data.Http
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * Eingebaute natuerliche Stimmen (neuronal, klingen wie ein Mensch), gerechnet von sherpa-onnx
 * komplett auf dem Handy – kostenlos, ohne Konto, ohne Schluessel. Zur Wahl stehen die Stimmen aus
 * [VoiceCatalog]: Supertonic 3 (5 Sprecherinnen, 5 Sprecher) und Piper "Thorsten".
 *
 * Das Paket der gewaehlten Stimme laedt die App einmalig von ihrer GitHub-Seite (Pruefsumme steht
 * fest in der App), danach spricht sie auch im Funkloch. Bis es bereit ist, spricht eine schon
 * vorhandene Stimme weiter – sonst, und falls etwas schiefgeht, die Android-Sprachausgabe.
 */
class NeuralVoice(ctx: Context, private val attrs: AudioAttributes, initial: VoiceCatalog.Option) {
    enum class State { OFF, WAITING, DOWNLOADING, LOADING, READY, UNSUPPORTED }

    /** Gestartetes Modell eines Pakets. */
    private class Engine(val pack: Pack, val tts: OfflineTts)
    /** Was gerade spricht: Modell und Stimme gehoeren immer zusammen (ein Zugriff, nie gemischt). */
    private class Current(val engine: Engine, val voice: VoiceCatalog.Option)
    /** Fertig gerechnete Ansage. */
    private class Pcm(val samples: ShortArray, val rate: Int)
    private class Source(val url: String, val sha: String, val size: Long)

    private val app = ctx.applicationContext
    private val baseDir = File(app.noBackupFilesDir, "stimme")

    /** Gewaehlte Stimme. */
    @Volatile var wanted: VoiceCatalog.Option = initial
        private set
    /** Zustand der gewaehlten Stimme (laedt, bereit, …). */
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
    @Volatile private var current: Current? = null
    @Volatile private var libraryBroken = false
    /** Supertonic: Rechenschritte – weniger, wenn das Handy zu langsam rechnet. */
    @Volatile private var steps = VoiceCatalog.MAX_STEPS
    /** Pakete, die sich auf diesem Geraet nicht nutzen lassen, mit Grund. */
    private val broken = ConcurrentHashMap<Pack, String>()
    /** Fehlgeschlagene Starts je Paket (nur im Lade-Thread). */
    private val failures = HashMap<Pack, Int>()
    private var loader: Thread? = null
    private val wake = Semaphore(0)
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    /** Spricht gerade die Android-Stimme (z. B. als Ersatz)? Dann wartet die eingebaute kurz. */
    @Volatile var otherBusy: () -> Boolean = { false }
    /** Wie oft die Android-Stimme einspringen musste, weil die eingebaute zu lange rechnete. */
    @Volatile var lateCount = 0
        private set
    // Rechnen und Abspielen getrennt: waehrend eine Ansage laeuft, rechnet schon die naechste
    // (z. B. das "Jetzt …" waehrend der Vorankuendigung) – dann kommt sie ohne Rechenpause.
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread({
            priority(Process.THREAD_PRIORITY_DISPLAY)
            r.run()
        }, "kss-stimme").apply { isDaemon = true }
    }
    private val player = Executors.newSingleThreadExecutor { r ->
        Thread({
            priority(Process.THREAD_PRIORITY_AUDIO)
            r.run()
        }, "kss-stimme-ton").apply { isDaemon = true }
    }
    /** Angefangene Ansagen (rechnen, warten oder spielen). */
    private val inFlight = AtomicInteger()
    // nur im Arbeits-Thread benutzt: zuletzt gesprochene Saetze ("Jetzt rechts abbiegen."), je Stimme
    private val cache = object : LinkedHashMap<String, Pcm>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pcm>) = size > 40
    }

    /** Spricht eine eingebaute Stimme (die gewaehlte oder zur Ueberbrueckung eine andere)? */
    val ready: Boolean get() = current != null
    /** Stimme, die gerade spricht – bis die gewaehlte bereit ist, ggf. eine andere. */
    val speaking: VoiceCatalog.Option? get() = current?.voice
    /** Rechnet oder spielt gerade eine Ansage (oder wartet darauf)? */
    val speakingOrQueued: Boolean get() = inFlight.get() > 0
    /** Wird die gewaehlte Stimme noch vorbereitet? */
    val busy: Boolean get() = state == State.WAITING || state == State.DOWNLOADING || state == State.LOADING

    /** Kann diese Stimme sofort sprechen (ihr Modell laeuft schon)? */
    fun canSpeak(v: VoiceCatalog.Option): Boolean = current?.engine?.pack == v.pack

    /** Ist das Paket schon auf dem Handy? */
    fun installed(p: Pack): Boolean = source(p)?.let { installed(p, it) } ?: false

    /** Einschalten: Sprachpaket laden bzw. Modell starten – im Hintergrund, bei Netzproblemen spaeter erneut. */
    @Synchronized
    fun start() {
        enabled = true
        kick()
    }

    /** Ausschalten: nichts mehr sprechen, ein laufender Download wird angehalten (spaeter fortgesetzt). */
    fun stop() {
        enabled = false
        stopAll()
        wake.release()
        if (busy) state = State.OFF
    }

    /**
     * Andere Stimme waehlen. Laeuft ihr Modell schon (gleiches Paket), spricht sie ab der naechsten
     * Ansage; sonst wird ihr Paket geladen und gestartet – bis dahin spricht die bisherige Stimme.
     */
    fun select(v: VoiceCatalog.Option) {
        synchronized(this) {
            wanted = v
            val c = current
            if (c != null && c.engine.pack == v.pack) {
                current = Current(c.engine, v)
                if (enabled) {
                    problem = null
                    state = State.READY
                }
            } else if (enabled) {
                problem = null
                progress = 0
                state = State.LOADING
            }
        }
        Log.i(TAG, "NEURAL_VOICE gewaehlt ${v.id}")
        kick()
    }

    /** Laufende und wartende Ansagen verwerfen. */
    fun stopAll() {
        generation.incrementAndGet()
    }

    /** Lade-Thread starten bzw. wecken (z. B. nach einer neuen Wahl). */
    @Synchronized
    private fun kick() {
        if (!enabled || libraryBroken) return
        val l = loader
        if (l != null && l.isAlive) {
            wake.release()
            return
        }
        loader = Thread({ prepareLoop() }, "kss-stimme-laden").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Schleife beenden? Nicht, wenn inzwischen eine andere Stimme gewaehlt bzw. die Stimme gerade
     * wieder eingeschaltet wurde (start() weckt dann nur diesen Thread).
     */
    @Synchronized
    private fun finished(want: VoiceCatalog.Option, done: Boolean): Boolean {
        if (enabled && (wanted != want || !done)) return false
        loader = null
        if (!enabled && busy) state = State.OFF
        return true
    }

    private fun prepareLoop() {
        try {
            cleanup()
        } catch (e: Exception) {
            // Aufraeumen ist nicht wichtig
        }
        var pause = 60_000L
        var last: Pack? = null
        while (true) {
            wake.drainPermits()
            val want = wanted
            if (want.pack != last) {
                last = want.pack
                pause = 60_000L
            }
            val done = try {
                prepare(want)
            } catch (e: Throwable) {
                // nur melden, wenn der Fehler nicht vom Wechsel bzw. Ausschalten kommt
                if (enabled && wanted.pack == want.pack) {
                    problem = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "NEURAL_VOICE Fehler: $problem")
                    state = State.WAITING
                }
                false
            }
            if (done || !enabled) {
                if (finished(want, done)) return
                continue
            }
            // anderes Paket gewaehlt: sofort weiter, sonst spaeter neu versuchen (eine neue Wahl weckt)
            if (wanted.pack != want.pack) continue
            val woken = try {
                wake.tryAcquire(pause, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                true
            }
            if (!woken) pause = min(pause * 2, 15 * 60_000L)
        }
    }

    /** true = fertig (gewaehlte Stimme spricht oder ist hier nicht moeglich), false = spaeter erneut. */
    private fun prepare(want: VoiceCatalog.Option): Boolean {
        if (!enabled) return false
        if (!libraryOk()) return true
        val p = want.pack
        current?.engine?.takeIf { it.pack == p }?.let {
            activate(it, want, final = true)
            return true
        }
        broken[p]?.let { why ->
            unsupported(why)
            fallback(p)
            return true
        }
        val src = source(p)
        if (src == null) {
            broken[p] = "In dieser Version nicht enthalten"
            unsupported(broken[p]!!)
            fallback(p)
            return true
        }
        state = State.LOADING
        if (!installed(p, src)) {
            // Bis das Paket da ist: eine schon vorhandene Stimme starten (falls noch keine spricht)
            fallback(p)
            if (!enabled || wanted.pack != p) return false
            download(p, src)
            if (!enabled || wanted.pack != p) return false
            state = State.LOADING
        }
        val e = try {
            open(p)
        } catch (x: Throwable) {
            // Startet das Modell wiederholt nicht, bleibt das Paket aus (statt endlos neu zu laden)
            val n = (failures[p] ?: 0) + 1
            failures[p] = n
            if (n >= 3) broken[p] = "Stimme ließ sich nicht starten (${x.message ?: x.javaClass.simpleName})"
            fallback(p)
            throw x
        }
        if (e == null) {
            unsupported(broken[p] ?: "Nicht verfügbar")
            fallback(p)
            return true
        }
        failures.remove(p)
        val now = wanted
        if (now.pack != p) {
            // waehrend des Startens umentschieden: zur Ueberbrueckung behalten, falls sonst keine spricht
            if (current == null) activate(e, VoiceCatalog.firstOf(p), final = false) else e.tts.release()
            return false
        }
        activate(e, now, final = true)
        return true
    }

    /** Eine andere schon vorhandene Stimme starten, solange gar keine spricht. */
    private fun fallback(skip: Pack) {
        if (current != null || !enabled) return
        for (p in Pack.values()) {
            if (p == skip || broken.containsKey(p)) continue
            val src = source(p) ?: continue
            if (!installed(p, src)) continue
            val e = try {
                open(p)
            } catch (t: Throwable) {
                Log.w(TAG, "NEURAL_VOICE Ueberbrueckung ${p.dir}: ${t.message ?: t.javaClass.simpleName}")
                null
            } ?: continue
            if (current == null) activate(e, VoiceCatalog.firstOf(p), final = false) else e.tts.release()
            return
        }
    }

    /**
     * Diese Stimme spricht ab der naechsten Ansage. Ein abgeloestes Modell wird erst nach einer
     * gerade laufenden Ansage freigegeben (im Arbeits-Thread – die rechnet evtl. noch damit).
     */
    @Synchronized
    private fun activate(e: Engine, chosen: VoiceCatalog.Option, final: Boolean) {
        // inzwischen eine andere Stimme desselben Pakets gewaehlt? Dann gleich diese
        val v = if (wanted.pack == e.pack) wanted else chosen
        val old = current?.engine
        current = Current(e, v)
        if (old != null && old !== e) {
            worker.execute {
                cache.clear()
                if (current?.engine !== old) {
                    try {
                        old.tts.release()
                    } catch (t: Throwable) {
                        // schon frei
                    }
                }
            }
        }
        if (final) {
            problem = null
            state = State.READY
            Log.i(TAG, "NEURAL_VOICE ready stimme=${v.id} paket=${e.pack.dir}" + if (e.pack == Pack.SUPERTONIC) " schritte=$steps" else "")
        } else {
            Log.i(TAG, "NEURAL_VOICE ueberbrueckung stimme=${v.id} (gewaehlt: ${wanted.id})")
        }
    }

    private fun unsupported(why: String) {
        problem = why
        state = State.UNSUPPORTED
        Log.i(TAG, "NEURAL_VOICE nicht verfuegbar: $why (${wanted.id})")
    }

    private fun libraryOk(): Boolean {
        if (libraryBroken) return false
        return try {
            System.loadLibrary("sherpa-onnx-jni")
            true
        } catch (e: Throwable) {
            libraryBroken = true
            unsupported("Auf diesem Gerät nicht verfügbar (nur für 64-Bit-Handys)")
            false
        }
    }

    private fun source(p: Pack): Source? = when (p) {
        Pack.THORSTEN -> Source(BuildConfig.VOICE_URL, BuildConfig.VOICE_SHA256, BuildConfig.VOICE_SIZE)
        Pack.SUPERTONIC -> Source(BuildConfig.VOICE_ST_URL, BuildConfig.VOICE_ST_SHA256, BuildConfig.VOICE_ST_SIZE)
    }.takeIf { it.url.isNotBlank() && it.sha.isNotBlank() }

    private fun dir(p: Pack) = File(baseDir, p.dir)

    private fun installed(p: Pack, src: Source): Boolean {
        val d = dir(p)
        val ok = File(d, "ok")
        return ok.isFile && ok.readText().trim() == src.sha && VoiceCatalog.isComplete(p, d)
    }

    /** Alte Pakete, abgebrochenes Auspacken und Startmarken frueherer Versionen wegraeumen. */
    private fun cleanup() {
        val known = Pack.values().map { it.dir }.toSet()
        baseDir.listFiles()?.forEach { f ->
            val n = f.name
            val stale = when {
                f.isDirectory -> n !in known
                n.endsWith(".zip.part") -> n.removeSuffix(".zip.part") !in known
                n.startsWith("laden_") -> !n.endsWith("_${BuildConfig.VERSION_CODE}.lock")
                else -> false
            }
            if (stale) f.deleteRecursively()
        }
    }

    private fun download(p: Pack, src: Source) {
        baseDir.mkdirs()
        val target = dir(p)
        // unvollstaendiges bzw. veraltetes Paket weg (die angefangene Datei bleibt – wird fortgesetzt)
        target.deleteRecursively()
        val expected = src.size
        val needed = (if (expected > 0) expected else p.approxMb * 1_000_000L) * 2 + 50_000_000L
        if (baseDir.usableSpace < needed) {
            throw IOException("Zu wenig freier Speicher für die Stimme (ca. ${needed / 1_000_000} MB nötig)")
        }
        val part = File(baseDir, "${p.dir}.zip.part")
        progress = 0
        state = State.DOWNLOADING
        var have = if (part.isFile) part.length() else 0L
        if (expected > 0 && have > expected) {
            part.delete()
            have = 0
        }
        val t0 = SystemClock.elapsedRealtime()
        if (expected <= 0 || have < expected) {
            val c = URL(src.url).openConnection() as HttpURLConnection
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
                    Log.i(TAG, "NEURAL_VOICE download start paket=${p.dir} ab=$have von=$total")
                    FileOutputStream(part, append).use { out ->
                        c.inputStream.use { inp ->
                            val buf = ByteArray(128 * 1024)
                            var done = have
                            while (true) {
                                if (!enabled || wanted.pack != p) throw IOException("angehalten")
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
        if (sha != src.sha) {
            part.delete()
            throw IOException("Sprachpaket beschädigt (Prüfsumme) – wird neu geladen")
        }
        val tmp = File(baseDir, "neu_${p.dir}")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val n = part.inputStream().use { VoicePack.unzip(it, tmp) }
        if (!VoiceCatalog.isComplete(p, tmp)) {
            tmp.deleteRecursively()
            part.delete()
            throw IOException("Sprachpaket unvollständig")
        }
        File(tmp, "ok").writeText(sha)
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw IOException("Sprachpaket: Umbenennen fehlgeschlagen")
        part.delete()
        progress = 100
        Log.i(TAG, "NEURAL_VOICE download ok paket=${p.dir} dateien=$n ${(SystemClock.elapsedRealtime() - t0) / 1000}s")
    }

    /**
     * Modell eines Pakets starten. Schutz vor Absturz-Schleifen: stuerzt die native Bibliothek
     * dabei ab, steht die Marke beim naechsten Start noch da – nach zwei Fehlversuchen bleibt das
     * Paket aus (null, Grund in [broken]).
     */
    private fun open(p: Pack): Engine? {
        baseDir.mkdirs()
        val lock = File(baseDir, "laden_${p.dir}_${BuildConfig.VERSION_CODE}.lock")
        val tries = if (lock.isFile) lock.readText().trim().toIntOrNull() ?: 1 else 0
        if (tries >= 2) {
            broken[p] = "Stimme ließ sich auf diesem Gerät nicht starten"
            return null
        }
        lock.writeText((tries + 1).toString())
        try {
            return loadModel(p)
        } finally {
            // nur ein Absturz der nativen Bibliothek laesst die Marke stehen
            lock.delete()
        }
    }

    private fun loadModel(p: Pack): Engine {
        val d = dir(p)
        val cores = Runtime.getRuntime().availableProcessors()
        val threads = if (cores >= 8) 4 else 2
        val model = when (p) {
            Pack.THORSTEN -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = (VoicePack.model(d) ?: throw IOException("Sprachmodell fehlt")).path,
                    tokens = File(d, "tokens.txt").path,
                    dataDir = File(d, "espeak-ng-data").path,
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
            Pack.SUPERTONIC -> OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = File(d, "duration_predictor.int8.onnx").path,
                    textEncoder = File(d, "text_encoder.int8.onnx").path,
                    vectorEstimator = File(d, "vector_estimator.int8.onnx").path,
                    vocoder = File(d, "vocoder.int8.onnx").path,
                    ttsJson = File(d, "tts.json").path,
                    unicodeIndexer = File(d, "unicode_indexer.bin").path,
                    voiceStyle = File(d, "voice.bin").path,
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            )
        }
        val t0 = SystemClock.elapsedRealtime()
        // Die Rechen-Threads des Modells entstehen hier und erben die Prioritaet: etwas vorziehen,
        // damit Ansagen auch bei voll ausgelastetem Handy (Karte, Android Auto) zuegig fertig sind
        priority(Process.THREAD_PRIORITY_DISPLAY)
        // Laesst sich das Modell nicht laden, bricht der Konstruktor ab (statt eines leeren Zeigers)
        val t = try {
            OfflineTts(config = OfflineTtsConfig(model = model, maxNumSentences = 1))
        } catch (e: IllegalArgumentException) {
            throw IOException("Sprachmodell ließ sich nicht laden")
        } finally {
            priority(Process.THREAD_PRIORITY_DEFAULT)
        }
        val e = Engine(p, t)
        try {
            val t1 = SystemClock.elapsedRealtime()
            // Aufwaermen: die erste Rechnung ist sonst spuerbar langsamer
            generate(e, VoiceCatalog.firstOf(p), "Route wird berechnet.", steps)
            val t2 = SystemClock.elapsedRealtime()
            var probe = ""
            if (p == Pack.SUPERTONIC) {
                // Wie schnell rechnet das Handy? Danach richten sich die Rechenschritte.
                val a = generate(e, VoiceCatalog.firstOf(p), "In dreihundert Metern rechts abbiegen.", VoiceCatalog.MAX_STEPS)
                val ms = SystemClock.elapsedRealtime() - t2
                val audioMs = a.samples.size * 1000L / a.sampleRate.coerceAtLeast(1)
                val rtf = if (audioMs > 0) ms.toDouble() / audioMs else 0.0
                steps = VoiceCatalog.steps(VoiceCatalog.MAX_STEPS, rtf)
                probe = " messung=${String.format(Locale.ROOT, "%.2f", rtf)} schritte=$steps"
            }
            Log.i(TAG, "NEURAL_VOICE geladen paket=${p.dir} laden=${t1 - t0}ms aufwaermen=${t2 - t1}ms rate=${t.sampleRate()} threads=$threads$probe")
            return e
        } catch (x: Throwable) {
            t.release()
            throw x
        }
    }

    private fun generate(e: Engine, v: VoiceCatalog.Option, text: String, steps: Int): GeneratedAudio = when (e.pack) {
        Pack.THORSTEN -> e.tts.generate(text, 0, SPEED)
        Pack.SUPERTONIC -> e.tts.generateWithConfig(
            VoiceCatalog.text(e.pack, text),
            GenerationConfig(sid = v.sid, speed = SPEED, numSteps = steps, extra = mapOf("lang" to "de")),
        )
    }

    /**
     * Ansage sprechen. urgent = laufende und wartende Ansagen verwerfen. done(false) heisst:
     * hat nicht geklappt – der Aufrufer nimmt dann die Android-Stimme.
     *
     * late: rechnet die Stimme laenger als [DEADLINE_MS] (dringend: [DEADLINE_URGENT_MS]), wird
     * late() aufgerufen (Hauptthread) und der Aufrufer spricht die Ansage mit der Android-Stimme –
     * lieber rechtzeitig als schoen. Die fertig gerechnete Ansage wird dann nicht mehr gespielt.
     * started: kurz bevor der Ton beginnt (Abspiel-Thread) – erst dann den Ton anfordern, damit das
     * Radio nur waehrend der Ansage leiser ist (nicht schon waehrend des Rechnens).
     */
    fun speak(text: String, urgent: Boolean, late: (() -> Unit)? = null, started: (() -> Unit)? = null, done: (Boolean) -> Unit) {
        val g = if (urgent) generation.incrementAndGet() else generation.get()
        inFlight.incrementAndGet()
        val closed = AtomicBoolean(false)
        fun finish(ok: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            inFlight.decrementAndGet()
            done(ok)
        }
        val claim = AtomicBoolean(false)
        val timer = AtomicReference<Runnable?>(null)
        fun arm() {
            if (late == null) return
            val r = Runnable {
                if (generation.get() == g && claim.compareAndSet(false, true)) {
                    lateCount++
                    Log.i(TAG, "NEURAL_SAY zu langsam – Android-Stimme spricht: $text")
                    late()
                }
            }
            timer.set(r)
            main.postDelayed(r, if (urgent) DEADLINE_URGENT_MS else DEADLINE_MS)
        }
        fun disarm() {
            timer.getAndSet(null)?.let { main.removeCallbacks(it) }
        }
        // Dringend ("Jetzt …"): die Uhr laeuft ab sofort – der Rechen-Thread ist evtl. noch mit etwas
        // anderem beschaeftigt. Sonst erst ab dem eigenen Rechnen.
        if (urgent) arm()
        try {
            worker.execute {
                if (generation.get() != g) {
                    disarm()
                    finish(true) // ueberholt: einfach weglassen
                    return@execute
                }
                val c = current
                if (!urgent && c != null && !cache.containsKey(key(c, text))) arm()
                val p = try {
                    pcm(text)
                } catch (e: Throwable) {
                    disarm()
                    Log.w(TAG, "NEURAL_SAY Fehler: ${e.message ?: e.javaClass.simpleName}")
                    // schon von der Android-Stimme gesprochen: nicht noch einmal
                    finish(!claim.compareAndSet(false, true))
                    return@execute
                }
                disarm()
                if (!claim.compareAndSet(false, true)) {
                    finish(true) // die Android-Stimme hat schon uebernommen
                    return@execute
                }
                // abspielen im eigenen Thread (der Reihe nach); hier geht es gleich weiter mit Rechnen
                player.execute {
                    val ok = try {
                        waitForOtherVoice(g)
                        if (generation.get() != g) {
                            true // inzwischen ueberholt
                        } else {
                            started?.invoke()
                            play(g, p)
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "NEURAL_PLAY Fehler: ${e.message ?: e.javaClass.simpleName}")
                        false
                    }
                    finish(ok)
                }
            }
        } catch (e: Exception) {
            disarm()
            finish(false)
        }
    }

    /** Nicht gleichzeitig mit der Android-Stimme sprechen (die ist evtl. gerade eingesprungen). */
    private fun waitForOtherVoice(g: Int) {
        val until = SystemClock.elapsedRealtime() + 8_000
        while (generation.get() == g && SystemClock.elapsedRealtime() < until) {
            val busy = try {
                otherBusy()
            } catch (e: Exception) {
                false
            }
            if (!busy) return
            Thread.sleep(50)
        }
    }

    /**
     * Vorausberechnen (z. B. "Jetzt rechts abbiegen …" waehrend die Vorankuendigung laeuft). Kommt
     * vorher eine dringende Ansage, entfaellt es – die rechnet dann selbst.
     */
    fun prepare(text: String) {
        val g = generation.get()
        worker.execute {
            val c = current ?: return@execute
            if (generation.get() != g || cache.containsKey(key(c, text))) return@execute
            try {
                pcm(text)
            } catch (e: Throwable) {
                // dann eben beim Sprechen
            }
        }
    }

    /** Hoerprobe als WAV-Datei (Testfahrt). */
    fun writeWav(text: String, out: File) {
        worker.execute {
            try {
                out.delete() // keine alte Probe liegen lassen, falls diese nicht klappt
                val p = pcm(text)
                out.writeBytes(VoicePack.wav(p.samples, p.rate))
                Log.i(TAG, "NEURAL_WAV ok ${out.length()} Bytes ${p.samples.size * 1000L / p.rate}ms stimme=${current?.voice?.id}")
            } catch (e: Throwable) {
                Log.w(TAG, "NEURAL_WAV Fehler: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun key(c: Current, text: String) = c.voice.id + "|" + text

    private fun pcm(text: String): Pcm {
        val c = current ?: throw IOException("Stimme nicht bereit")
        val k = key(c, text)
        cache[k]?.let {
            Log.i(TAG, "NEURAL_SAY cache ${it.samples.size * 1000L / it.rate}ms")
            return it
        }
        val st = steps
        val t0 = SystemClock.elapsedRealtime()
        val a = generate(c.engine, c.voice, text, st)
        val ms = SystemClock.elapsedRealtime() - t0
        if (a.samples.isEmpty()) throw IOException("keine Ausgabe")
        val p = Pcm(VoicePack.toPcm16(a.samples, a.sampleRate), a.sampleRate)
        val audioMs = a.samples.size * 1000L / a.sampleRate
        lastRtf = if (audioMs > 0) ms.toDouble() / audioMs else 0.0
        Log.i(TAG, "NEURAL_SAY rechnen=${ms}ms sprechen=${audioMs}ms zeichen=${text.length} stimme=${c.voice.id}")
        if (c.engine.pack == Pack.SUPERTONIC && audioMs >= 1_000) {
            val s = VoiceCatalog.steps(st, lastRtf)
            if (s != st) {
                steps = s
                Log.i(TAG, "NEURAL_VOICE schneller rechnen: $s Schritte (Faktor ${String.format(Locale.ROOT, "%.2f", lastRtf)})")
            }
        }
        cache[k] = p
        return p
    }

    /** Spielt PCM ueber den Navigations-Kanal; bricht ab, sobald eine dringendere Ansage kommt. */
    private fun play(g: Int, pcm: Pcm): Boolean {
        if (generation.get() != g) return true
        val rate = pcm.rate
        val data = pcm.samples
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
            val total = data.size
            val limit = SystemClock.elapsedRealtime() + total * 1000L / rate + 4_000
            var off = 0
            var playing = false
            while (off < total) {
                if (generation.get() != g) {
                    track.pause()
                    track.flush()
                    return true
                }
                val w = track.write(data, off, min(total - off, rate / 10), AudioTrack.WRITE_NON_BLOCKING)
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
        }
    }

    private fun priority(p: Int) = try {
        Process.setThreadPriority(p)
    } catch (e: Exception) {
        // nicht erlaubt: normale Prioritaet reicht auch
    }

    /** Text fuer die Einstellungen. */
    fun status(on: Boolean): String {
        val w = wanted
        val now = speaking
        val meanwhile = if (now != null && now != w) "„${now.name}“" else "die Android-Stimme"
        return when {
            !on -> "Aus – es spricht die Android-Sprachausgabe."
            state == State.READY && now == w ->
                "Bereit: „${w.name}“, spricht auch ohne Internet." +
                    when {
                        lateCount > 0 -> " Rechnet auf diesem Handy manchmal zu lange – $lateCount Ansage(n) sprach deshalb die Android-Stimme" +
                            if (w.pack == Pack.SUPERTONIC) "; „Thorsten“ spricht schneller los." else "."
                        w.pack == Pack.SUPERTONIC && lastRtf > 0.6 -> " Rechnet auf diesem Handy langsam – „Thorsten“ spricht schneller los."
                        else -> ""
                    }
            state == State.DOWNLOADING -> "„${w.name}“ wird einmalig geladen (ca. ${w.pack.approxMb} MB) … $progress % – bis dahin spricht $meanwhile."
            state == State.LOADING -> "„${w.name}“ wird gestartet …"
            state == State.UNSUPPORTED -> "${problem ?: "Nicht verfügbar"} – es spricht $meanwhile."
            state == State.WAITING -> "„${w.name}“ noch nicht geladen" + (problem?.let { " ($it)" } ?: "") + " – neuer Versuch in wenigen Minuten, bis dahin spricht $meanwhile."
            else -> "Wird vorbereitet …"
        }
    }

    companion object {
        private const val TAG = "KSSMaps"
        /** Sprechtempo (1 = wie aufgenommen). */
        const val SPEED = 1.0f
        /** So lange darf eine Ansage hoechstens rechnen, sonst spricht die Android-Stimme (ms). */
        const val DEADLINE_MS = 4_000L
        /** … bei "Jetzt …"-Ansagen (die sind meist schon vorausberechnet). */
        const val DEADLINE_URGENT_MS = 2_000L
        /** Hoerprobe mit typischen Ansagen (Testfahrt). */
        const val PROBE = "In 300 Metern rechts abbiegen auf die Leopoldstraße. Danach links halten. " +
            "Achtung, Stau in 2,5 Kilometern, etwa 8 Minuten Verzögerung. " +
            "Route gestartet. 23,4 Kilometer, Ankunft gegen 14:35 Uhr. " +
            "Jetzt im Kreisverkehr die dritte Ausfahrt nehmen auf B 13. " +
            "Max Mustermann anrufen? " +
            "Sie haben Ihr Ziel erreicht, es liegt rechts."
    }
}
