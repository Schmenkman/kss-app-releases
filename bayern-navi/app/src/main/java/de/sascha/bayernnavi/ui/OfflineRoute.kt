package de.sascha.bayernnavi.ui

import android.content.Context
import android.util.Log
import de.sascha.bayernnavi.core.Geo
import de.sascha.bayernnavi.core.Route
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Laedt beim Navigationsstart die Karte entlang der Route vorab (Zoom 8-14; die Vektorkarte
 * hat ab Zoom 14 keine neuen Daten mehr, darueber wird hochskaliert). So bleibt die Karte
 * auch in Funkloechern sichtbar. Es werden nur die letzten Routen behalten.
 */
object OfflineRoute {
    private const val TAG = "BayernNavi"
    private const val PREFIX = "bayernnavi-route:"
    private const val KEEP = 3
    private var lastKey: String? = null

    fun prefetch(ctx: Context, route: Route, styleUrl: String) {
        // Datenvolumen begrenzen: hoechstens die ersten 250 km (bei Neuberechnung kommt der Rest).
        val g = route.geometry
        val pts = Geo.simplify(g.slice(0.0, minOf(g.length, 250_000.0)), 25.0)
        if (pts.size < 2) return
        val key = styleUrl + pts.first().fmt() + pts.last().fmt() + pts.size
        if (key == lastKey) return
        lastKey = key
        try {
            val om = OfflineManager.getInstance(ctx.applicationContext)
            om.setOfflineMapboxTileCountLimit(30_000)
            om.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    val ours = (offlineRegions ?: emptyArray()).filter { String(it.metadata).startsWith(PREFIX) }
                        .sortedByDescending { String(it.metadata).removePrefix(PREFIX).toLongOrNull() ?: 0L }
                    ours.drop(KEEP - 1).forEach { r ->
                        r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() {}
                            override fun onError(error: String) {}
                        })
                    }
                    create(om, ctx, pts.map { Point.fromLngLat(it.lon, it.lat) }, styleUrl)
                }

                override fun onError(error: String) {
                    create(om, ctx, pts.map { Point.fromLngLat(it.lon, it.lat) }, styleUrl)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Offline-Route: ${e.message}")
        }
    }

    private fun create(om: OfflineManager, ctx: Context, points: List<Point>, styleUrl: String) {
        val def = OfflineGeometryRegionDefinition(styleUrl, LineString.fromLngLats(points), 8.0, 14.0, ctx.resources.displayMetrics.density)
        om.createOfflineRegion(def, (PREFIX + System.currentTimeMillis()).toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        if (status.isComplete) {
                            Log.i(TAG, "Offline-Route fertig: ${status.completedResourceCount} Teile")
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        }
                    }

                    override fun onError(error: OfflineRegionError) {
                        Log.w(TAG, "Offline-Route: ${error.message}")
                    }

                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        Log.w(TAG, "Offline-Route: zu viele Kacheln ($limit)")
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String) {
                Log.w(TAG, "Offline-Route anlegen: $error")
            }
        })
    }
}
