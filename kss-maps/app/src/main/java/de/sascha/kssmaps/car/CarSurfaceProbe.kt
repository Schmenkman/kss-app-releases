package de.sascha.kssmaps.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.car.app.SurfaceContainer
import java.io.File

/**
 * Nur fuer die automatische Testfahrt (Debug-Build, per Test-Intent): zeichnet die
 * Android-Auto-Karte genau wie im Auto ueber eine VirtualDisplay – nur auf eine unsichtbare
 * Bildflaeche statt auf den Auto-Bildschirm – und speichert auf Anforderung ein Bild davon.
 * So laesst sich die Kartendarstellung fuer Android Auto ohne Auto pruefen.
 */
object CarSurfaceProbe {
    private const val TAG = "KSSMaps"
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var renderer: CarMapRenderer? = null
    @Volatile private var last: Bitmap? = null
    @Volatile private var frames = 0
    @Volatile private var lastCopy = 0L

    fun start(ctx: Context, w: Int = 1280, h: Int = 720, dpi: Int = 160) {
        stop()
        val t = HandlerThread("kss-car-probe").also { it.start() }
        thread = t
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ ir ->
            val img = try {
                ir.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            try {
                frames++
                val now = SystemClock.elapsedRealtime()
                if (now - lastCopy >= 400) {
                    lastCopy = now
                    val p = img.planes[0]
                    val full = Bitmap.createBitmap(p.rowStride / p.pixelStride, img.height, Bitmap.Config.ARGB_8888)
                    full.copyPixelsFromBuffer(p.buffer)
                    last = if (full.width == img.width) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height)
                }
            } catch (e: Exception) {
                Log.w(TAG, "TEST_CAR_SURFACE Bild: ${e.message}")
            } finally {
                img.close()
            }
        }, Handler(t.looper))
        reader = r
        val cr = CarMapRenderer(ctx.applicationContext, customButtons = true)
        renderer = cr
        cr.onSurfaceAvailable(SurfaceContainer(r.surface, w, h, dpi))
        // Wie Android Auto: links die Fahrkarte bzw. Routenliste, rechts die Knopfleisten
        cr.onVisibleAreaChanged(Rect(w * 30 / 100, 0, w - w * 8 / 100, h))
        cr.onStableAreaChanged(Rect(w * 30 / 100, 0, w - w * 8 / 100, h))
        Log.i(TAG, "TEST_CAR_SURFACE gestartet ${w}x$h")
    }

    fun capture(ctx: Context, name: String) {
        val b = last
        if (b == null) {
            Log.i(TAG, "TEST_CAR_CAPTURE $name: noch kein Bild (frames=$frames)")
            return
        }
        val f = File(ctx.filesDir, "car_$name.png")
        try {
            f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i(TAG, "TEST_CAR_CAPTURE $name ok frames=$frames ${b.width}x${b.height}")
        } catch (e: Exception) {
            Log.w(TAG, "TEST_CAR_CAPTURE $name: ${e.message}")
        }
    }

    /** Antippen eines eigenen Knopfs nachstellen (z. B. "RECENTER"); das Ergebnis steht im Protokoll. */
    fun click(name: String) {
        renderer?.testClick(name) ?: Log.i(TAG, "TEST_CAR_BUTTON kein Renderer")
    }

    fun stop() {
        renderer?.release()
        renderer = null
        reader?.close()
        reader = null
        thread?.quitSafely()
        thread = null
        last = null
        frames = 0
    }
}
