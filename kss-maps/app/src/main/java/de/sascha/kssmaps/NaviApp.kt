package de.sascha.kssmaps

import android.app.Application
import android.util.Log
import de.sascha.kssmaps.data.Http
import de.sascha.kssmaps.data.Prefs
import de.sascha.kssmaps.data.Services
import de.sascha.kssmaps.nav.NavEngine
import de.sascha.kssmaps.nav.Voice
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager

class NaviApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var services: Services
        private set
    lateinit var voice: Voice
        private set
    lateinit var engine: NavEngine
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
        prefs = Prefs(this)
        services = Services(this, prefs)
        voice = Voice(this)
        engine = NavEngine(this, prefs, services, voice)
        engine.onRouteActivated = { r ->
            de.sascha.kssmaps.ui.OfflineRoute.prefetch(
                this, r, if (engine.isNight()) de.sascha.kssmaps.ui.MapController.STYLE_NIGHT else de.sascha.kssmaps.ui.MapController.STYLE_DAY
            )
        }
    }

    companion object {
        lateinit var instance: NaviApp
            private set
        var versionName: String = "?"
            private set
    }
}
