package de.sascha.kssmaps

import android.app.Application
import android.util.Log
import de.sascha.kssmaps.core.Route
import de.sascha.kssmaps.data.CompAppointmentSource
import de.sascha.kssmaps.data.Http
import de.sascha.kssmaps.data.Prefs
import de.sascha.kssmaps.data.SecretStore
import de.sascha.kssmaps.data.Services
import de.sascha.kssmaps.nav.NavEngine
import de.sascha.kssmaps.nav.Voice
import de.sascha.kssmaps.ui.MapController
import de.sascha.kssmaps.ui.OfflineRoute
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager

open class NaviApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var services: Services
        private set
    lateinit var voice: Voice
        private set
    lateinit var engine: NavEngine
        private set
    /** Termine aus COMP (eigener Zugang, verschluesselt gespeichert). */
    lateinit var comp: CompAppointmentSource
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        Http.userAgent = "KSS-Maps/$versionName (Android; private Navigations-App)"
        initMaps()
        prefs = Prefs(this)
        services = Services(this, prefs)
        voice = Voice(this, prefs)
        startNeuralVoice()
        engine = NavEngine(this, prefs, services, voice)
        engine.onRouteActivated = { r -> prefetchRoute(r) }
        comp = CompAppointmentSource(prefs, SecretStore(this))
        engine.appointmentSource = comp
    }

    /** Karten-Bibliothek starten (native). In Tests ohne Geraet ueberschrieben. */
    protected open fun initMaps() {
        MapLibre.getInstance(this)
        try {
            // Grosser Kachel-Cache: oft gefahrene Gegenden laden dann auch bei schlechtem Netz.
            OfflineManager.getInstance(this).setMaximumAmbientCacheSize(600L * 1024 * 1024, object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {}
                override fun onError(message: String) {}
            })
        } catch (e: Exception) {
            Log.w("KSSMaps", "Cache: ${e.message}")
        }
    }

    /** Eingebaute natuerliche Stimme vorbereiten (laedt das Sprachpaket einmalig). In Tests aus. */
    protected open fun startNeuralVoice() {
        voice.startNeural()
    }

    /** Karte entlang der aktiven Route offline vorhalten. */
    protected open fun prefetchRoute(r: Route) {
        OfflineRoute.prefetch(this, r, if (engine.isNight()) MapController.STYLE_NIGHT else MapController.STYLE_DAY)
    }

    companion object {
        lateinit var instance: NaviApp
            private set
        var versionName: String = "?"
            private set
    }
}
