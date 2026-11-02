package de.sascha.kssmaps.car

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Trip
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import de.sascha.kssmaps.NaviApp
import de.sascha.kssmaps.core.Geocoding
import de.sascha.kssmaps.nav.NavEngine

/** Einstieg fuer Android Auto (Kategorie Navigation). */
class NaviCarAppService : CarAppService() {
    // Selbst installierte App ohne Play Store: jeden Host zulassen (Android Auto prueft selbst).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    @Deprecated("Deprecated in Java")
    override fun onCreateSession(): Session = NaviSession()
}

class NaviSession : Session(), NavEngine.Listener {
    lateinit var renderer: CarMapRenderer
        private set
    private val engine: NavEngine get() = NaviApp.instance.engine
    private var navManager: NavigationManager? = null
    private var navStarted = false
    private var lastTrip = 0L

    override fun onCreateScreen(intent: Intent): Screen {
        renderer = CarMapRenderer(carContext) { carContext.isDarkMode }
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        setupNavigationManager()
        engine.addListener(this)
        engine.acquireLocation()
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                engine.removeListener(this@NaviSession)
                engine.releaseLocation()
                renderer.release()
                if (navStarted) {
                    try {
                        navManager?.navigationEnded()
                    } catch (e: Exception) {
                        // Host schon weg
                    }
                    navStarted = false
                }
                try {
                    navManager?.clearNavigationManagerCallback()
                } catch (e: Exception) {
                    // ignorieren
                }
            }
        })
        val main = CarMainScreen(carContext, this)
        handleNavigateIntent(intent)
        if (!engine.hasLocationPermission()) {
            carContext.getCarService(androidx.car.app.ScreenManager::class.java).push(main)
            return CarPermissionScreen(carContext)
        }
        return main
    }

    override fun onNewIntent(intent: Intent) {
        handleNavigateIntent(intent)
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        renderer.onDarkModeChanged()
    }

    /** "Ok Google, navigiere zu …" bzw. Ziele aus anderen Apps (geo:-Intent). */
    private fun handleNavigateIntent(intent: Intent) {
        if (intent.action != CarContext.ACTION_NAVIGATE) return
        val data: Uri = intent.data ?: return
        val (p, q) = Geocoding.parseGeoUri(data.toString())
        engine.navigateTo(p, q)
    }

    private fun setupNavigationManager() {
        try {
            val nm = carContext.getCarService(NavigationManager::class.java)
            nm.setNavigationManagerCallback(object : NavigationManagerCallback {
                override fun onStopNavigation() {
                    engine.stopNavigation()
                }

                override fun onAutoDriveEnabled() {}
            })
            navManager = nm
        } catch (e: Exception) {
            Log.w("KSSMaps", "NavigationManager: ${e.message}")
        }
    }

    override fun onStateChanged() {
        val nm = navManager ?: return
        try {
            if (engine.mode == NavEngine.Mode.NAVIGATING && !navStarted) {
                nm.navigationStarted()
                navStarted = true
            } else if (engine.mode != NavEngine.Mode.NAVIGATING && navStarted) {
                nm.navigationEnded()
                navStarted = false
            }
        } catch (e: Exception) {
            Log.w("KSSMaps", "Navigation an Host melden: ${e.message}")
        }
        updateTrip(force = true)
    }

    override fun onLocationChanged() = updateTrip(force = false)

    /** Naechster Schritt + Ankunft fuer Kombiinstrument / Head-up-Display. */
    private fun updateTrip(force: Boolean) {
        if (!navStarted) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastTrip < 2_000) return
        lastTrip = now
        val nm = navManager ?: return
        val r = engine.activeRoute ?: return
        val st = engine.state ?: return
        try {
            nm.updateTrip(buildTrip(r, st))
        } catch (e: Exception) {
            Log.w("KSSMaps", "updateTrip: ${e.message}")
        }
    }

    /** Fahrtinfo fuer Kombiinstrument / Head-up-Display (eigene Funktion, damit testbar). */
    internal fun buildTrip(r: de.sascha.kssmaps.core.Route, st: de.sascha.kssmaps.core.RouteTracker.State): Trip {
        run {
            val tb = Trip.Builder()
            if (engine.rerouting) {
                // Beim Neuberechnen nur "laedt" melden – Schritte und Ziel duerfen dann nicht gesetzt sein.
                tb.setLoading(true)
            } else {
                val est = CarUtil.travelEstimate(st.remainingM, st.remainingSec, r.trafficDelaySec)
                val m = r.maneuvers.getOrNull(st.nextIdx)
                if (m != null) {
                    val stepEst = CarUtil.travelEstimate(st.distToNext, (r.timeAt(m.along) - r.timeAt(st.along)).coerceAtLeast(0.0), 0.0)
                    tb.addStep(CarUtil.step(m, engine.fuelStop?.displayName()), stepEst)
                }
                val d = engine.destination
                if (d != null) tb.addDestination(Destination.Builder().setName(d.name).setAddress(d.address.ifBlank { d.name }).build(), est)
                r.maneuvers.lastOrNull { it.along <= st.along + 1 }?.street?.let { tb.setCurrentRoad(it) }
            }
            return tb.build()
        }
    }
}
