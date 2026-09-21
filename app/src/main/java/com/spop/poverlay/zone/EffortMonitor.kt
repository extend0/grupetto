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

        /**
         * Output at or above this fraction of holding power is enough to believe a rider is
         * still doing the work without a heart rate to prove it. Stricter than
         * [CollapsedRatio]: this vouches for someone, rather than merely not condemning them.
         */
        const val VouchRatio = 0.85f

        /** Output wobbles with every pedal stroke, so it is smoothed before it is trusted. */
        private const val PowerTauMs = 10_000.0

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
    private var smoothedWattsValue: Double? = null
    private var lastPowerAtMs: Long? = null
    private var baselineWatts: Double? = null
    private var steadyInZoneMs = 0L

    /** Positive while the heart rate is climbing. Null before there are two samples. */
    var trendBpmPerMin: Float? = null
        private set

    /** The output holding the zone, once enough steady riding has been seen to mean it. */
    val holdingWatts: Float?
        get() = baselineWatts?.takeIf { steadyInZoneMs >= BaselineReadyMs }?.toFloat()

    /** Smoothed output. What every judgement about power is actually made on. */
    val smoothedWatts: Float? get() = smoothedWattsValue?.toFloat()

    fun reset() {
        slowBpm = null
        lastSampleAtMs = null
        smoothedWattsValue = null
        lastPowerAtMs = null
        baselineWatts = null
        steadyInZoneMs = 0
        trendBpmPerMin = null
    }

    /**
     * Feeds output, on every tick, heart rate or no heart rate.
     *
     * Kept separate from [onSample] precisely because it must keep running when the strap has
     * gone: output is the only thing still reporting then, and it is what a rider riding blind
     * is credited on.
     */
    fun onPower(nowMs: Long, watts: Float?) {
        if (watts == null || !watts.isFinite() || watts < 0f) {
            smoothedWattsValue = null
            return
        }
        val previous = smoothedWattsValue
        val previousAt = lastPowerAtMs
        val dt = previousAt?.let { nowMs - it } ?: -1L
        lastPowerAtMs = nowMs
        if (dt > StaleSessionMs) {
            // Nothing at all has arrived for minutes - not even a pedal stroke. Whatever this
            // is, it is not the ride we were watching.
            reset()
            lastPowerAtMs = nowMs
        }
        if (smoothedWattsValue == null || previous == null || dt <= 0L || dt > StaleSessionMs) {
            smoothedWattsValue = watts.toDouble()
            return
        }
        smoothedWattsValue = previous + (1.0 - exp(-dt / PowerTauMs)) * (watts - previous)
    }

    /**
     * Whether output alone is good enough to believe the rider is still doing the work.
     *
     * Needs a holding figure, which takes a minute of steady riding *in the zone* to earn - so
     * this can only ever vouch for someone who has already proved, with a heart rate, what
     * their zone costs. Taking the strap off is not a way to get here.
     */
    val powerRatio: Float?
        get() = holdingWatts?.takeIf { it > 0f }?.let { holding ->
            smoothedWatts?.div(holding)
        }

    fun vouchesForEffort(): Boolean {
        val holding = holdingWatts ?: return false
        val watts = smoothedWattsValue ?: return false
        return watts >= holding * VouchRatio
    }

    /**
     * Feeds one tick of usable data. [bpm] is the conditioned reading - the one the machine is
     * judging - so that the trend describes the same signal every other decision is made on.
     */
    fun onSample(nowMs: Long, bpm: Int, inZone: Boolean, smoothingTauMs: Long) {
        val previousAt = lastSampleAtMs
        val dt = previousAt?.let { nowMs - it } ?: -1L

        if (previousAt == null || dt <= 0L || dt > StaleSessionMs) {
            // A long gap invalidates the *trend* - nobody knows what the heart rate did in
            // between - but not what was learned about output. A rider whose strap died for
            // three minutes while they kept pedalling has not unlearned what their zone costs,
            // and making them earn it back would be the second punishment for one flat battery.
            if (dt > StaleSessionMs) trendBpmPerMin = null
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

        // Learned from smoothed output, not the raw figure: a single hard pedal stroke is not
        // evidence about what the zone costs.
        val steadyWatts = smoothedWattsValue
        if (!inZone || steadyWatts == null || steadyWatts <= 0.0 || abs(trend) > SteadyBpmPerMin) {
            return
        }

        steadyInZoneMs += dt
        val previous = baselineWatts
        baselineWatts = if (previous == null) {
            steadyWatts
        } else {
            val tau = if (steadyWatts < previous) BaselineFallTauMs else BaselineRiseTauMs
            previous + (1.0 - exp(-dt.toDouble() / tau)) * (steadyWatts - previous)
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
    fun health(bpm: Int, band: ZoneBounds): Float {
        val floor = band.floor
        var health = if (floor == null) {
            1f
        } else {
            val width = band.ceiling?.let { (it - floor).toFloat() } ?: OpenBandBpm
            ((bpm - floor) / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        }

        val holding = holdingWatts
        val watts = smoothedWatts
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
