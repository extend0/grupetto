package com.spop.poverlay.zone

/** A coaching hint only; it never changes credit or triggers a penalty. */
internal class EarlyEffortWarning {
    private var fallingSince: Long? = null
    private var improvingSince: Long? = null
    private var visible = false

    fun reset() {
        fallingSince = null
        improvingSince = null
        visible = false
    }

    fun update(nowMs: Long, eligible: Boolean, powerRatio: Float?, trend: Float?, headroom: Long?): Boolean {
        if (!eligible) {
            reset()
            return false
        }
        val falling = (powerRatio != null && powerRatio < 0.85f) ||
            (trend != null && trend < -1f && headroom != null && headroom <= 45L)
        if (falling) {
            improvingSince = null
            val since = fallingSince ?: nowMs.also { fallingSince = it }
            if (nowMs - since >= 5_000L) visible = true
        } else {
            fallingSince = null
            val since = improvingSince ?: nowMs.also { improvingSince = it }
            if (nowMs - since >= 3_000L) visible = false
        }
        return visible
    }
}
