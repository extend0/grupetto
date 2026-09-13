package com.spop.poverlay.zone

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * Judges how sustainable the current effort is, ahead of what the rider's heart has got round
 * to showing.
 *
 * Heart rate lags effort by tens of seconds, so a machine that watches only heart rate always
 * finds out too late: by the time the number crosses the floor, the easing off that caused it
 * happened half a minute ago. Output does not lag at all. Watching both is what buys back the
 * warning time.
 *
 * Two signals, neither of which needs to know anything about the rider:
 *
 *  - **Holding power.** The output that has been keeping *this* rider in *their* zone, learned
 *    from their own last few minutes. It is an observation, not a profile, so it needs no
 *    calibration, survives swapping riders, and is correct from the first ride.
 *  - **Heart rate trend.** How fast the heart rate is moving, read off the gap between two
 *    averages rather than a buffer of samples.
 *
 * Everything here is incremental: a handful of doubles, no history, no allocation per sample.
 */
class EffortMonitor {

    companion object {
        /**
         * Span between the fast average (the conditioned bpm) and the slow one the trend is
         * read against. Both lag a ramp by their own time constant, so the gap between them is
         * the ramp rate times this span - which makes the trend independent of how hard the
         * signal happens to be smoothed.
         */
        const val SlopeSpanMs = 20_000L

        /**
         * A trend flatter than this counts as steady.
         *
         * Only steady riding teaches anything about holding power. Right after a surge a rider
         * can sit in the zone on almost no output while their heart rate falls back - learning
         * from that would teach the monitor that nothing holds the zone.
         */
        const val SteadyBpmPerMin = 3f

        /** Output at or below this fraction of holding power scores zero. */
        const val CollapsedRatio = 0.7f

        /** Headroom at or beyond this stops constraining the score. */
        const val FullHeadroomSeconds = 45f

        /** Zones 1 and 5 are open-ended, so there is no width to normalise a buffer against. */
        const val OpenBandBpm = 15f

        /** A longer gap than this is a different session, whoever is on the bike. */
        const val StaleSessionMs = 120_000L

        /** Steady in-zone riding needed before holding power is worth comparing against. */
        const val BaselineReadyMs = 60_000L

        /**
         * Holding power falls fast and rises slowly, so it settles on the *least* output that
         * kept the rider in their zone. A plain average would sit far too high: most in-zone
         * time is spent comfortably above the floor, and the question being asked is where the
         * floor is.
         */
        private const val BaselineFallTauMs = 30_000.0
        private const val BaselineRiseTauMs = 180_000.0
    }

    private var slowBpm: Double? = null
    private var lastSampleAtMs: Long? = null
    private var baselineWatts: Double? = null
    private var steadyInZoneMs = 0L

    /** Positive while the heart rate is climbing. Null before there are two samples. */
    var trendBpmPerMin: Float? = null
        private set

    /** The output holding the zone, once enough steady riding has been seen to mean it. */
    val holdingWatts: Float?
        get() = baselineWatts?.takeIf { steadyInZoneMs >= BaselineReadyMs }?.toFloat()

    fun reset() {
        slowBpm = null
        lastSampleAtMs = null
        baselineWatts = null
        steadyInZoneMs = 0
        trendBpmPerMin = null
    }

    /**
     * Feeds one tick of usable data. [bpm] is the conditioned reading - the one the machine is
     * judging - so that the trend describes the same signal every other decision is made on.
     */
    fun onSample(nowMs: Long, bpm: Int, watts: Float?, inZone: Boolean, smoothingTauMs: Long) {
        val previousAt = lastSampleAtMs
        val dt = previousAt?.let { nowMs - it } ?: -1L

        if (previousAt == null || dt <= 0L || dt > StaleSessionMs) {
            // First sample, a clock that went backwards, or a gap long enough that nothing
            // learned before it still describes whoever is on the bike now.
            if (dt > StaleSessionMs) reset()
            lastSampleAtMs = nowMs
            slowBpm = bpm.toDouble()
            return
        }
        lastSampleAtMs = nowMs

        val tauSlow = smoothingTauMs + SlopeSpanMs
        val previousSlow = slowBpm ?: bpm.toDouble()
        val slow = previousSlow + (1.0 - exp(-dt.toDouble() / tauSlow)) * (bpm - previousSlow)
        slowBpm = slow
        val trend = ((bpm - slow) / SlopeSpanMs * 60_000.0).toFloat()
        trendBpmPerMin = trend

        if (!inZone || watts == null || watts <= 0f || abs(trend) > SteadyBpmPerMin) return

        steadyInZoneMs += dt
        val previous = baselineWatts
        baselineWatts = if (previous == null) {
            watts.toDouble()
        } else {
            val tau = if (watts < previous) BaselineFallTauMs else BaselineRiseTauMs
            previous + (1.0 - exp(-dt.toDouble() / tau)) * (watts - previous)
        }
    }

    /**
     * Seconds before the current decline carries [bpm] down to [floor], or null when the rider
     * is not heading there at all.
     */
    fun headroomSeconds(bpm: Int, floor: Int?): Long? {
        if (floor == null) return null
        val trend = trendBpmPerMin ?: return null
        // Flat or climbing: nothing is coming, and a countdown would be a lie.
        if (trend >= -0.1f) return null
        val margin = (bpm - floor).toFloat()
        if (margin <= 0f) return 0L
        return (margin / (-trend / 60f)).toLong().coerceIn(0L, 600L)
    }

    /**
     * How healthy the current effort is, from 1 (comfortably sustainable) to 0 (about to lose
     * the zone).
     *
     * Three terms, each of which can only make the score worse - a rider is never told they are
     * fine on the strength of one signal while another says otherwise:
     *
     *  - where they sit in the band, which is the buffer they have to spend;
     *  - their output against holding power, which is whether they are spending it;
     *  - their headroom, which is how long the spending can go on.
     *
     * Riding *above* the zone scores full health. The ceiling is a nag, never a penalty, so as
     * far as this is concerned someone over their zone is in no danger of losing it.
     */
    fun health(bpm: Int, band: ZoneBounds, watts: Float?): Float {
        val floor = band.floor
        var health = if (floor == null) {
            1f
        } else {
            val width = band.ceiling?.let { (it - floor).toFloat() } ?: OpenBandBpm
            ((bpm - floor) / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        }

        val holding = holdingWatts
        if (holding != null && holding > 0f && watts != null) {
            val ratio = watts / holding
            health *= ((ratio - CollapsedRatio) / (1f - CollapsedRatio)).coerceIn(0f, 1f)
        }

        headroomSeconds(bpm, floor)?.let {
            health = min(health, it / FullHeadroomSeconds)
        }

        return health.coerceIn(0f, 1f)
    }
}
