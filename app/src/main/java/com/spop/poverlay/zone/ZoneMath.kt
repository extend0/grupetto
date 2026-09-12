package com.spop.poverlay.zone

/**
 * Heart rate zone arithmetic.
 *
 * Zone boundaries are the four transition points already stored by HeartRateManager:
 * `[z12, z23, z34, z45]`. Four boundaries describe five zones.
 *
 * A zone is closed at its floor and open at its ceiling, so a bpm sitting exactly on a
 * transition belongs to the higher zone. Zone 1 has no floor and zone 5 has no ceiling.
 *
 * Everything here is total: invalid input returns null rather than throwing, because these
 * run on every tick of a live overlay where a crash would take the whole ride down.
 */

const val MinZone = 1
const val MaxZone = 5

data class ZoneBounds(val floor: Int?, val ceiling: Int?)

/**
 * Matches the validation HeartRateManager.updateHeartRateZones already applies before
 * publishing heartRateZones.
 */
fun List<Int>?.isValidZoneBoundaries(): Boolean {
    val boundaries = this ?: return false
    if (boundaries.size != 4) return false
    if (boundaries.first() <= 0) return false
    return boundaries.zipWithNext().all { (lower, upper) -> lower < upper }
}

/** The zone [bpm] falls in, 1..5, or null if [boundaries] are unusable. */
fun zoneFor(bpm: Int, boundaries: List<Int>?): Int? {
    if (!boundaries.isValidZoneBoundaries()) return null
    val bounds = boundaries!!
    return when {
        bpm < bounds[0] -> 1
        bpm < bounds[1] -> 2
        bpm < bounds[2] -> 3
        bpm < bounds[3] -> 4
        else -> 5
    }
}

/** The bpm band for [zone], or null if [zone] or [boundaries] are unusable. */
fun zoneBounds(zone: Int, boundaries: List<Int>?): ZoneBounds? {
    if (!boundaries.isValidZoneBoundaries()) return null
    if (zone < MinZone || zone > MaxZone) return null
    val bounds = boundaries!!
    return ZoneBounds(
        floor = if (zone == MinZone) null else bounds[zone - 2],
        ceiling = if (zone == MaxZone) null else bounds[zone - 1],
    )
}

/**
 * Narrows [bounds] by [hysteresisBpm] on both sides when [strict].
 *
 * Strict is what we demand of someone trying to get back *into* the zone; staying in uses the
 * raw band. Without that asymmetry a bpm hovering on the boundary flaps between states - and
 * each flap would pause and resume the video.
 *
 * The inset is dropped when it would leave no room, so a narrow band still behaves sanely.
 */
fun effectiveBand(bounds: ZoneBounds, hysteresisBpm: Int, strict: Boolean): ZoneBounds {
    if (!strict) return bounds
    val inset = hysteresisBpm.coerceAtLeast(0)
    if (inset == 0) return bounds
    val floor = bounds.floor?.plus(inset)
    val ceiling = bounds.ceiling?.minus(inset)
    // The inset swallowed the band; fall back to the raw one.
    if (floor != null && ceiling != null && floor >= ceiling) return bounds
    return ZoneBounds(floor, ceiling)
}

/** Is [bpm] inside [bounds]? Closed at the floor, open at the ceiling. */
fun isInBand(bpm: Int, bounds: ZoneBounds): Boolean {
    if (bounds.floor != null && bpm < bounds.floor) return false
    if (bounds.ceiling != null && bpm >= bounds.ceiling) return false
    return true
}
