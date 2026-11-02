package de.sascha.kssmaps.core

/**
 * Grober Umriss Bayerns (auf wenige Kilometer genau). Reicht, um zu entscheiden, ob die
 * amtlichen Luftbilder (nur Bayern) oder die weltweiten Satellitenbilder gezeigt werden.
 */
object Bavaria {
    val OUTLINE: List<LatLon> = listOf(
        LatLon(50.02, 9.00), LatLon(50.10, 9.10), LatLon(50.22, 9.55), LatLon(50.33, 9.75),
        LatLon(50.42, 9.95), LatLon(50.53, 10.05), LatLon(50.38, 10.40), LatLon(50.28, 10.70),
        LatLon(50.35, 10.95), LatLon(50.40, 11.20), LatLon(50.56, 11.40), LatLon(50.48, 11.60),
        LatLon(50.40, 11.95), LatLon(50.32, 12.10), LatLon(50.25, 12.25), LatLon(50.08, 12.40),
        LatLon(49.95, 12.55), LatLon(49.75, 12.45), LatLon(49.55, 12.60), LatLon(49.43, 12.85),
        LatLon(49.30, 13.05), LatLon(49.12, 13.40), LatLon(48.95, 13.62), LatLon(48.77, 13.84),
        LatLon(48.58, 13.73), LatLon(48.55, 13.45), LatLon(48.35, 13.30), LatLon(48.26, 13.05),
        LatLon(48.15, 12.85), LatLon(47.95, 12.95), LatLon(47.75, 13.05), LatLon(47.47, 13.05),
        LatLon(47.62, 12.80), LatLon(47.67, 12.45), LatLon(47.58, 12.20), LatLon(47.60, 11.95),
        LatLon(47.52, 11.60), LatLon(47.40, 11.25), LatLon(47.40, 10.95), LatLon(47.55, 10.70),
        LatLon(47.27, 10.25), LatLon(47.52, 9.95), LatLon(47.55, 9.60), LatLon(47.65, 9.85),
        LatLon(47.80, 10.10), LatLon(48.10, 10.12), LatLon(48.42, 10.00), LatLon(48.62, 10.25),
        LatLon(48.95, 10.30), LatLon(49.25, 10.15), LatLon(49.45, 10.10), LatLon(49.62, 9.85),
        LatLon(49.78, 9.50), LatLon(49.62, 9.30), LatLon(49.68, 9.10), LatLon(49.80, 9.03),
    )

    val BBOX: BBox = BBox.of(OUTLINE)!!

    fun contains(p: LatLon): Boolean = BBOX.contains(p) && Geo.pointInRing(p, OUTLINE)

    /** Mitte Bayerns – Startansicht, solange noch kein GPS da ist. */
    val CENTER = LatLon(48.95, 11.40)
}
