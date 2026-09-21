package com.spop.poverlay.zone

/** Shared wording for the riding strip and settings; independent of Android for testing. */
enum class ZoneStatusTone { NORMAL, GOOD, CAUTION, URGENT }

data class ZoneStatus(val title: String, val detail: String, val tone: ZoneStatusTone)

fun EnforcementSnapshot.ridingStatus(): ZoneStatus? {
    if (state == EnforcementState.IDLE || suspendReason == SuspendReason.DISABLED) return null
    fun status(title: String, detail: String, tone: ZoneStatusTone = ZoneStatusTone.NORMAL) =
        ZoneStatus(title, detail, tone)
    val mode = when {
        enforcementReleased -> "Enforcement ended · tracking continues"
        !penaltyEnabled || targetZone == 1 -> "Tracking only · video will not pause"
        !enforcementArmed -> "Warm-up · ${warmupRemainingSeconds}s in zone to arm"
        else -> "Enforcement active"
    }
    val target = "Zone $targetZone"
    if (state == EnforcementState.COMPLETE) return status("Goal complete", "Enjoy your cooldown · no more penalties", ZoneStatusTone.GOOD)
    if (state == EnforcementState.PENALTY) {
        val recovery = recoveryMessage()
        return status(recovery.title, recovery.detail, ZoneStatusTone.URGENT)
    }
    if (state == EnforcementState.SUSPENDED) return status(
        when (suspendReason) {
            SuspendReason.NO_SIGNAL -> "Waiting for heart rate"
            SuspendReason.STALE -> "Heart-rate signal lost"
            SuspendReason.NO_ZONES -> "Set your heart-rate zones"
            SuspendReason.NOT_MOVING -> "Tracking paused · not pedalling"
            else -> "Tracking paused"
        }, "Goal clock paused · $mode", ZoneStatusTone.CAUTION,
    )
    if (state == EnforcementState.RIDING_BLIND) return status(
        "Strap lost · counting from power", "Keep your effort steady · $mode", ZoneStatusTone.CAUTION,
    )
    if (enforcementReleased) return status("Enforcement ended", "Goal tracking continues · no more pauses")
    if (state == EnforcementState.WARMUP) return status("Warming up · reach $target", mode)
    if (secondsUntilPenalty != null) return status(
        "PAUSING IN ${secondsUntilPenalty}s", "Below $target · pick it up", ZoneStatusTone.URGENT,
    )
    if (drift == Drift.ABOVE) return status("Above $target · ease back", "No penalty above the zone · $mode", ZoneStatusTone.CAUTION)
    if (drift == Drift.BELOW) {
        val detail = when {
            !enforcementArmed -> mode
            powerVouches -> "Power is holding your effort · no pause pending"
            graceRemainingSeconds != null -> "${graceRemainingSeconds}s grace before the warning"
            else -> mode
        }
        return status("Below $target · pick it up", detail, ZoneStatusTone.CAUTION)
    }
    if (earlyEffortWarning) return status(
        "Effort dropping — pick it up", "Still in $target · keep your effort steady", ZoneStatusTone.CAUTION,
    )
    if (state == EnforcementState.RECOVERING) return status("Recovered · keep it steady", mode, ZoneStatusTone.GOOD)
    return status("In $target", mode, ZoneStatusTone.GOOD)
}

data class RecoveryMessage(val title: String, val detail: String, val target: String)

fun EnforcementSnapshot.recoveryMessage(): RecoveryMessage {
    val seconds = (holdRequiredMs + 999) / 1000
    val remaining = (holdRemainingMs + 999) / 1000
    val signalLost = suspendReason == SuspendReason.NO_SIGNAL || suspendReason == SuspendReason.STALE
    val target = recoveryFloorBpm?.let { "$it+ bpm for ${seconds}s" } ?: "Hold your effort for ${seconds}s"
    if (signalLost) return if (powerVouches) {
        RecoveryMessage("Keep your effort steady", "Strap lost · hold this output for ${remaining}s to resume", "Recovery is based on power")
    } else {
        RecoveryMessage(
            "Heart-rate signal lost",
            if (holdingWatts != null) "Reconnect your strap or hold your learned riding effort. You can also end enforcement."
            else "Reconnect your strap, or hold the button below to end enforcement.",
            "Video is waiting · recovery cannot be confirmed",
        )
    }
    return if (recoveryHolding) {
        RecoveryMessage("Hold it there", "${remaining}s of steady effort to resume", target)
    } else {
        RecoveryMessage("Pick it up to resume", "Hold at or above the recovery target for ${seconds}s", target)
    }
}

fun EnforcementSnapshot.goalTimeLabel(): String {
    fun time(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)
    return "Z$targetZone · ${time(creditSeconds)} / ${time(goalSeconds)}"
}
