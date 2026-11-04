package de.sascha.kssmaps.car

import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.SearchTemplate
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.testing.TestCarContext
import androidx.test.core.app.ApplicationProvider
import de.sascha.kssmaps.NaviApp
import de.sascha.kssmaps.core.FuelStation
import de.sascha.kssmaps.core.HazardType
import de.sascha.kssmaps.core.LatLon
import de.sascha.kssmaps.core.Maneuver
import de.sascha.kssmaps.core.ManeuverKind
import de.sascha.kssmaps.core.Place
import de.sascha.kssmaps.core.Polyline
import de.sascha.kssmaps.core.RankedStation
import de.sascha.kssmaps.core.Route
import de.sascha.kssmaps.core.RouteSource
import de.sascha.kssmaps.core.RouteTracker
import de.sascha.kssmaps.core.UtaStatus
import de.sascha.kssmaps.nav.NavEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** App ohne native Karten-Bibliothek und ohne Netz – fuer Tests der Android-Auto-Vorlagen. */
class TestNaviApp : NaviApp() {
    override fun onCreate() {
        getSharedPreferences("kss_maps", MODE_PRIVATE).edit().putBoolean("cameras", false).commit()
        super.onCreate()
    }

    override fun initMaps() {}
    override fun prefetchRoute(r: Route) {}
}

/**
 * Android Auto prueft beim Bauen jeder Vorlage strenge Regeln (Pflichtfelder, Anzahl Aktionen,
 * Manoever-Typen …) und wirft sonst eine Ausnahme – im Auto waere das ein Absturz. Diese Tests
 * bauen alle Vorlagen der App in allen Zustaenden einmal durch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestNaviApp::class)
class CarScreensTest {
    private lateinit var car: TestCarContext
    private val engine: NavEngine get() = NaviApp.instance.engine

    @Before
    fun setUp() {
        car = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())
    }

    private fun dest() = Place("Marienplatz 7", "85354 Freising", 48.4, 11.74, street = "Marienplatz", houseNumber = "7")

    private fun route(label: String = "Schnellste"): Route {
        val pts = (0 until 300).map { LatLon(48.1, 11.5 + it * 0.0005) }
        val g = Polyline(pts)
        val L = g.length
        val r = Route(
            RouteSource.VALHALLA, g, L,
            listOf(
                Maneuver(ManeuverKind.DEPART, 0.0, pts[0], "Leopoldstraße"),
                Maneuver(ManeuverKind.RIGHT, L * 0.1, g.pointAt(L * 0.1), "Ungererstraße"),
                Maneuver(ManeuverKind.ROUNDABOUT, L * 0.2, g.pointAt(L * 0.2), "B 13", roundaboutExit = 2),
                Maneuver(ManeuverKind.ROUNDABOUT_EXIT, L * 0.21, g.pointAt(L * 0.21), "B 13"),
                Maneuver(ManeuverKind.RAMP_LEFT, L * 0.4, g.pointAt(L * 0.4), null, signBranch = "A 9"),
                Maneuver(ManeuverKind.KEEP_RIGHT, L * 0.5, g.pointAt(L * 0.5), "A 9", signToward = "Nürnberg"),
                Maneuver(ManeuverKind.EXIT_RIGHT, L * 0.8, g.pointAt(L * 0.8), null, exitNumber = "65", signToward = "Freising"),
                Maneuver(ManeuverKind.ARRIVE_RIGHT, L, pts.last()),
            ),
            listOf(L), listOf(0.0 to 0.0, L to 1500.0), setOf("A9"), false, true, false, "über A 9",
        )
        r.label = label
        return r
    }

    @Test
    fun everyManeuverKindBuildsACarStep() {
        for (k in ManeuverKind.values()) {
            for (exit in listOf(0, 3)) {
                val m = Maneuver(k, 100.0, LatLon(48.1, 11.5), "Hauptstraße", exitNumber = "65", signToward = "Nürnberg", roundaboutExit = exit, turnAngle = 45.0)
                assertNotNull(CarUtil.step(m, "Aral"))
                assertNotNull(CarUtil.step(m.copy(street = null, signToward = null, exitNumber = null), null))
            }
        }
        for (d in listOf(0.0, 5.0, 999.0, 1000.0, 9_999.0, 12_345.0, 250_000.0)) {
            assertNotNull(CarUtil.distance(d))
            assertNotNull(CarUtil.travelEstimate(d, d / 20, 0.0))
            assertNotNull(CarUtil.travelEstimate(d, d / 20, 900.0))
        }
    }

    @Test
    fun mainScreenIdleNavigatingArrived() {
        engine.testSetState(NavEngine.Mode.IDLE, null, emptyList())
        assertTrue(CarMainScreen(car, NaviSession()).onGetTemplate() is NavigationTemplate)

        val r = route()
        val st = RouteTracker(r).update(r.geometry.pointAt(50.0), 5.0, 10.0, 90.0)
        engine.testSetState(NavEngine.Mode.NAVIGATING, dest(), listOf(r), r, st)
        val t = CarMainScreen(car, NaviSession()).onGetTemplate() as NavigationTemplate
        assertTrue(t.navigationInfo is RoutingInfo)
        assertNotNull(t.destinationTravelEstimate)
        assertNotNull(NaviSession().buildTrip(r, st))

        // kurz vor dem Kreisverkehr und vor der Ankunft
        for (a in listOf(r.distanceM * 0.19, r.distanceM * 0.79, r.distanceM - 30)) {
            val tr = RouteTracker(r, a - 10)
            val s2 = tr.update(r.geometry.pointAt(a), 5.0, 10.0, 90.0)
            engine.testSetState(NavEngine.Mode.NAVIGATING, dest(), listOf(r), r, s2)
            assertTrue(CarMainScreen(car, NaviSession()).onGetTemplate() is NavigationTemplate)
            assertNotNull(NaviSession().buildTrip(r, s2))
        }

        engine.testSetState(NavEngine.Mode.NAVIGATING, dest(), listOf(r), r, st, arrived = true)
        val t2 = CarMainScreen(car, NaviSession()).onGetTemplate() as NavigationTemplate
        assertTrue(t2.navigationInfo is MessageInfo)

        // Navigation gestartet, aber noch kein GPS-Fix -> "laedt"
        engine.testSetState(NavEngine.Mode.NAVIGATING, dest(), listOf(r), r, null)
        assertTrue(CarMainScreen(car, NaviSession()).onGetTemplate() is NavigationTemplate)
    }

    @Test
    fun routePreviewWithAndWithoutRoutes() {
        engine.testSetState(NavEngine.Mode.PREVIEW, dest(), listOf(route("Schnellste"), route("Ohne Stau"), route("Alternative 1")))
        assertTrue(CarRoutePreviewScreen(car).onGetTemplate() is RoutePreviewNavigationTemplate)
        engine.testSetState(NavEngine.Mode.PREVIEW, dest(), emptyList())
        assertTrue(CarRoutePreviewScreen(car).onGetTemplate() is MessageTemplate)
    }

    @Test
    fun searchHistoryMorePermission() {
        engine.prefs.history = listOf(dest(), Place("Ohne Adresszeile", "", 48.0, 11.0))
        assertTrue(CarSearchScreen(car).onGetTemplate() is SearchTemplate)
        // ohne Standort: einfache Liste, mit Standort: Kartenliste mit Entfernungen
        engine.prefs.lastPosition = null
        assertTrue(CarHistoryScreen(car).onGetTemplate() is ListTemplate)
        engine.prefs.lastPosition = LatLon(48.1, 11.5)
        assertTrue(CarHistoryScreen(car).onGetTemplate() is PlaceListNavigationTemplate)
        engine.prefs.history = emptyList()
        assertTrue(CarHistoryScreen(car).onGetTemplate() is PlaceListNavigationTemplate)
        engine.testSetState(NavEngine.Mode.IDLE, null, emptyList())
        assertTrue(CarMoreScreen(car).onGetTemplate() is ListTemplate)
        engine.prefs.camerasEnabled = true // mit Melde-Zeilen (Blitzer / Abstandsmessung)
        assertTrue(CarMoreScreen(car).onGetTemplate() is ListTemplate)
        val r = route()
        engine.testSetState(NavEngine.Mode.NAVIGATING, dest(), listOf(r), r, null)
        assertTrue(CarMoreScreen(car).onGetTemplate() is ListTemplate)
        assertTrue(CarPermissionScreen(car).onGetTemplate() is MessageTemplate)
    }

    @Test
    fun fuelScreenStates() {
        val s = CarFuelScreen(car)
        val st = FuelStation("1", "ARAL", "Aral", "Münchner Str.", "5", "85354", "Freising", 48.4, 11.7, 1.659, 1.799, 1.749, true, UtaStatus.YES)
        s.error = null
        s.items = listOf(
            RankedStation(st, 2000.0, 300.0, true, 1500.0, 2100.0, 1.659),
            RankedStation(st.copy(id = "2", uta = UtaStatus.UNKNOWN, diesel = null), null, 4000.0, false, 3000.0, 3900.0, null),
        )
        assertTrue(s.onGetTemplate() is PlaceListNavigationTemplate)
        s.items = emptyList()
        assertTrue(s.onGetTemplate() is PlaceListNavigationTemplate)
        s.items = null
        assertTrue(s.onGetTemplate() is PlaceListNavigationTemplate)
        s.error = "Für Tankpreise wird ein Schlüssel benötigt"
        assertTrue(s.onGetTemplate() is MessageTemplate)
    }

    @Test
    fun appointmentsCustomerAndCall() {
        engine.testSetState(NavEngine.Mode.IDLE, null, emptyList())
        engine.testSetActiveAppointment(null)
        engine.setAppointmentsForTest(emptyList())
        assertTrue(CarAppointmentsScreen(car).onGetTemplate() is ListTemplate) // noch nicht eingerichtet
        val demo = de.sascha.kssmaps.data.DemoAppointments.list()
        engine.setAppointmentsForTest(demo)
        assertTrue(CarAppointmentsScreen(car).onGetTemplate() is ListTemplate)

        // Kunde: ohne Termin, mit einem / zwei Ansprechpartnern, ohne Nummer, ohne Terminzusatz
        assertTrue(CarCustomerScreen(car).onGetTemplate() is ListTemplate)
        for (a in demo + demo[0].copy(note = "Termin bestätigt, Kunde öffnet selbst.") + demo[0].copy(note = "", customerName = "")) {
            engine.testSetActiveAppointment(a)
            assertTrue(CarCustomerScreen(car).onGetTemplate() is ListTemplate)
        }

        // Leisten mit Knopf "Kunde": frei, beim Navigieren; "Mehr" in beiden Faellen
        engine.testSetActiveAppointment(demo[1])
        assertTrue(CarMainScreen(car, NaviSession()).onGetTemplate() is NavigationTemplate)
        assertTrue(CarMoreScreen(car).onGetTemplate() is ListTemplate)
        val r = route()
        engine.testSetState(NavEngine.Mode.NAVIGATING, dest(), listOf(r), r, null)
        assertTrue(CarMainScreen(car, NaviSession()).onGetTemplate() is NavigationTemplate)
        assertTrue(CarMoreScreen(car).onGetTemplate() is ListTemplate)
        engine.testSetState(NavEngine.Mode.IDLE, null, emptyList())
        engine.testSetActiveAppointment(null)

        // "Anrufen?" -> Ja: Nummer geht an Android Auto (ohne Berechtigung als Waehl-Absicht)
        val confirm = CarCallConfirmScreen(car, "Herr Probe", "08761/72345", "0876172345")
        assertTrue(confirm.onGetTemplate() is MessageTemplate)
        confirm.call()
        val sent = car.startCarAppIntents.last()
        assertEquals(android.content.Intent.ACTION_DIAL, sent.action)
        assertEquals("tel:0876172345", sent.data.toString())
    }

    @Test
    fun alertsDoNotThrow() {
        showCarAlert(car, NavEngine.Alert("cam", "Blitzer in 500 m", "Fester Blitzer · 80 km/h", HazardType.SPEED_CAMERA))
        showCarAlert(car, NavEngine.Alert("dist", "Abstandsmessung in 800 m", "", HazardType.DISTANCE_CHECK))
        showCarAlert(car, NavEngine.Alert("better-route", "Schnellere Route: −7 Min.", "über A 92", null, "Übernehmen") {})
    }
}
