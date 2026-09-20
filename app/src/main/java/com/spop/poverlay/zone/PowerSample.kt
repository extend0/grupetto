package com.spop.poverlay.zone

/** Timestamp the sensor arrival, never the tick that happens to read it. */
internal data class PowerSample(val watts: Float, val receivedAtMs: Long) {
    fun freshWatts(nowMs: Long): Float? = watts.takeIf {
        it.isFinite() && it >= 0f && nowMs - receivedAtMs in 0..MaxAgeMs
    }

    companion object {
        const val MaxAgeMs = 10_000L
    }
}
