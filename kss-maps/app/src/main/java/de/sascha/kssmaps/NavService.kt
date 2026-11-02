package de.sascha.kssmaps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import de.sascha.kssmaps.core.Instructions
import de.sascha.kssmaps.nav.NavEngine
import de.sascha.kssmaps.ui.Icons

/**
 * Vordergrund-Dienst waehrend der Navigation: haelt GPS und Sprachausgabe am Leben, auch bei
 * gesperrtem Handy und wenn nur Android Auto laeuft. Zeigt die naechste Anweisung als Mitteilung.
 */
class NavService : Service(), NavEngine.Listener {

    companion object {
        private const val CHANNEL = "navigation"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "de.sascha.kssmaps.STOP"

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, NavService::class.java))
            } catch (e: Exception) {
                // z. B. Android 12+ im Hintergrund ohne Ausnahme -> Navigation laeuft trotzdem, solange die App sichtbar ist
                Log.w("KSSMaps", "Vordergrunddienst: ${e.message}")
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, NavService::class.java))
        }
    }

    private val engine get() = (application as NaviApp).engine
    private var lastUpdate = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Navigation", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aktive Zielführung"
                setShowBadge(false)
            })
        }
        goForeground()
        engine.addListener(this)
        engine.acquireLocation()
        try {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KSSMaps:navigation").apply { acquire(6 * 3600 * 1000L) }
        } catch (e: Exception) {
            Log.w("KSSMaps", "WakeLock: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            engine.stopNavigation()
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        return START_NOT_STICKY
    }

    private fun goForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, build())
            }
        } catch (e: Exception) {
            Log.w("KSSMaps", "startForeground: ${e.message}")
        }
    }

    private fun build(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NavService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val e = engine
        val st = e.state
        val r = e.activeRoute
        var title = "KSS-Maps"
        var text = "Navigation aktiv"
        var icon = Icon.createWithBitmap(Icons.glyph(Icons.Glyph.NAVIGATE, 96))
        if (st != null && r != null && st.nextIdx >= 0) {
            val m = r.maneuvers[st.nextIdx]
            title = Instructions.formatDistance(st.distToNext) + " · " + Instructions.text(m)
            text = "Ankunft ${e.etaText(st.remainingSec)} · noch ${Instructions.formatDistance(st.remainingM)}"
            icon = Icon.createWithBitmap(Icons.maneuver(m.kind, 96, roundaboutExit = m.roundaboutExit, angleHint = m.turnAngle))
        } else if (e.rerouting) {
            text = "Route wird neu berechnet …"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Beenden", stop).build())
            .build()
    }

    private fun refresh() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUpdate < 2_500) return
        lastUpdate = now
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build())
        } catch (e: Exception) {
            // Mitteilungen evtl. nicht erlaubt – egal
        }
    }

    override fun onStateChanged() {
        if (engine.mode != NavEngine.Mode.NAVIGATING) {
            stopSelf()
            return
        }
        refresh()
    }

    override fun onLocationChanged() = refresh()

    override fun onDestroy() {
        engine.removeListener(this)
        engine.releaseLocation()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignorieren
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
