package com.spop.poverlay.zone

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Conditions the raw strap signal before the state machine is allowed to judge it.
 *
 * Two stages, in order:
 *
 *  - a median of the last three samples, which *discards* a single bad reading rather than
 *    averaging it in. Straps drop to zero and spike by twenty; one artifact should never be
 *    able to cost someone their video.
 *  - an exponential moving average, which takes the ordinary wobble off what survives. Real
 *    heart rate moves a couple of bpm beat to beat, and a boundary sitting inside that wobble
 *    makes the machine flap between states while the rider's effort never changed.
 *
 * The average is time-aware: alpha comes from the gap between samples rather than a fixed
 * sample count, so an irregular strap decays at the right rate and a long gap snaps straight
 * to the new value instead of dragging a stale one forward.
 *
 * Smoothing is not free. Heart rate already lags effort by tens of seconds, so every second of
 * filter lag is a second later that a genuine drop-out is noticed - see
 * [EnforcementConfig.smoothingTauSeconds] for where that budget is set.
 */
class HeartRateFilter {

    companion object {
        /** Three is the smallest window that can outvote a single artifact. */
        const val MedianWindow = 3
    }

    private val window = ArrayDeque<Int>()
    private var smoothed: Double? = null
    private var lastSampleAtMs: Long? = null

    /** The conditioned value, or null before the first sample. */
    val value: Int? get() = smoothed?.roundToInt()

    /** Drops the history. Used whenever the signal goes away, so it cannot be dragged back. */
    fun reset() {
        window.clear()
        smoothed = null
        lastSampleAtMs = null
    }

    /**
     * Feeds one genuinely new sample, taken at [sampleAtMs], and returns the conditioned bpm.
     *
     * Callers must not feed the same sample twice: repeats would pull the average toward a
     * value the heart never held and would let duplicates outvote the median.
     */
    fun accept(bpm: Int, sampleAtMs: Long, tauMs: Long): Int {
        if (tauMs <= 0) {
            // Conditioning off: pass the strap through untouched and keep no history at all,
            // so switching it back on later seeds cleanly rather than inheriting raw samples.
            reset()
            return bpm
        }

        val previous = smoothed
        val previousAt = lastSampleAtMs
        val dt = previousAt?.let { (sampleAtMs - it).coerceAtLeast(0L) }
        lastSampleAtMs = sampleAtMs

        // A gap longer than the time constant means the window is describing a different effort
        // - a strap that dropped out, or a rider who stopped. Carrying those samples forward
        // would let a reading from a minute ago outvote the one in front of us.
        if (dt != null && dt > tauMs) window.clear()

        window.addLast(bpm)
        while (window.size > MedianWindow) window.removeFirst()
        val median = window.sorted()[window.size / 2]

        if (previous == null || previousAt == null || dt == null) {
            // Seed on the first sample rather than ramping up from zero, which would read as a
            // rider who arrived dead and would hold them below their zone for the whole tau.
            smoothed = median.toDouble()
            return median
        }

        val alpha = 1.0 - exp(-dt.toDouble() / tauMs)
        smoothed = previous + alpha * (median - previous)
        return value!!
    }
}
