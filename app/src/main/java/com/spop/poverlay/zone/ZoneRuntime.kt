package com.spop.poverlay.zone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Read-only window onto the running enforcement, for UI that cannot reach the service.
 *
 * Mirrors the existing OverlayService.isRunning pattern. Commands still travel by Intent; this
 * carries state outward only.
 */
object ZoneRuntime {
    private val mutableSnapshot = MutableStateFlow<EnforcementSnapshot?>(null)
    val snapshot: StateFlow<EnforcementSnapshot?> = mutableSnapshot.asStateFlow()

    internal fun publish(snapshot: EnforcementSnapshot?) {
        mutableSnapshot.value = snapshot
    }
}
