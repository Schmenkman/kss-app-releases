package de.sascha.bayernnavi

import android.app.Application
import android.util.Log
import de.sascha.bayernnavi.data.Http
import de.sascha.bayernnavi.data.Prefs
import de.sascha.bayernnavi.data.Services
import de.sascha.bayernnavi.nav.NavEngine
import de.sascha.bayernnavi.nav.Voice
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
        Http.userAgent = "BayernNavi/$versionName (Android; private Navigations-App)"
        MapLibre.getInstance(this)
        try {
            // Grosser Kachel-Cache: oft gefahrene Gegenden laden dann auch bei schlechtem Netz.
            OfflineManager.getInstance(this).setMaximumAmbientCacheSize(600L * 1024 * 1024, object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {}
                override fun onError(message: String) {}
            })
        } catch (e: Exception) {
            Log.w("BayernNavi", "Cache: ${e.message}")
        }
        prefs = Prefs(this)
        services = Services(this, prefs)
        voice = Voice(this)
        engine = NavEngine(this, prefs, services, voice)
        engine.onRouteActivated = { r ->
            de.sascha.bayernnavi.ui.OfflineRoute.prefetch(
                this, r, if (engine.isNight()) de.sascha.bayernnavi.ui.MapController.STYLE_NIGHT else de.sascha.bayernnavi.ui.MapController.STYLE_DAY
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
