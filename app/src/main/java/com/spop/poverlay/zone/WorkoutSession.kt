package com.spop.poverlay.zone

/** One workout lasts until a long gap in actual pedal activity, including time asleep. */
class WorkoutSession(lastPedaledAtMs: Long? = null) {
    companion object {
        const val InactivityTimeoutMs = 60 * 60 * 1000L
        const val CadenceFreshMs = 10_000L

        fun isRecent(lastPedaledAtMs: Long?, nowMs: Long): Boolean =
            lastPedaledAtMs != null && nowMs - lastPedaledAtMs in 0 until InactivityTimeoutMs
    }

    var lastPedaledAtMs: Long? = lastPedaledAtMs
        private set
    private var lastPedaledElapsedMs: Long? = null
    private var latestCadenceAtMs: Long? = null
    private var latestCadence = 0f

    val active: Boolean get() = lastPedaledAtMs != null

    /** Call before accepting a new sample: a returning rider must not revive yesterday's ride. */
    fun expire(nowMs: Long, elapsedMs: Long): Boolean {
        if (!active) return false
        val recent = lastPedaledElapsedMs?.let { elapsedMs - it in 0 until InactivityTimeoutMs }
            ?: isRecent(lastPedaledAtMs, nowMs)
        if (recent) return false
        lastPedaledAtMs = null
        lastPedaledElapsedMs = null
        latestCadenceAtMs = null
        latestCadence = 0f
        return true
    }

    fun onCadence(rpm: Float, nowMs: Long, elapsedMs: Long) {
        latestCadence = rpm
        latestCadenceAtMs = elapsedMs
        if (rpm.isFinite() && rpm >= 1f) {
            lastPedaledAtMs = nowMs
            lastPedaledElapsedMs = elapsedMs
        }
    }

    fun isMoving(elapsedMs: Long): Boolean = latestCadence.isFinite() && latestCadence >= 1f &&
        latestCadenceAtMs?.let { elapsedMs - it in 0..CadenceFreshMs } == true
}
