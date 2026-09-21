package com.spop.poverlay.sensor

/** NaN means unavailable, not zero effort. All consumers must preserve that distinction. */
internal data class BikeReading(val power: Float, val cadence: Float, val resistance: Float) {
    companion object { val Unavailable = BikeReading(Float.NaN, Float.NaN, Float.NaN) }
}

/** Track the source packet, not repeated deliveries of the service's cached BikeData. */
internal class BikePacketTracker(private val timeoutMs: Long = 10_000) {
    private var packetTime: String? = null
    private var receivedAt: Long? = null
    private var available = false
    private var reading = BikeReading.Unavailable

    @Synchronized fun accept(time: String?, value: BikeReading, now: Long): Boolean {
        if (!value.power.isFinite() || value.power < 0 || !value.cadence.isFinite() || value.cadence < 0 ||
            !value.resistance.isFinite() || value.resistance < 0) {
            invalidate()
            return false
        }
        val marker = time?.takeIf { it.isNotBlank() }
        if (marker != null && marker == packetTime) return false
        packetTime = marker
        receivedAt = now
        reading = value
        available = true
        return true
    }

    @Synchronized fun current(now: Long): BikeReading =
        if (available && receivedAt?.let { now - it in 0 until timeoutMs } == true) reading else BikeReading.Unavailable

    @Synchronized fun age(now: Long): Long = receivedAt?.let { (now - it).coerceAtLeast(0) } ?: Long.MAX_VALUE

    // Keep the packet marker across rebinds: reconnecting must not make a cached packet fresh.
    @Synchronized fun invalidate() { available = false; reading = BikeReading.Unavailable }
}
