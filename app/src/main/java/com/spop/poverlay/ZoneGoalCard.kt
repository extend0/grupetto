package com.spop.poverlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spop.poverlay.media.MediaCapabilities
import com.spop.poverlay.ui.theme.ErrorColor
import com.spop.poverlay.ui.theme.zoneColor
import com.spop.poverlay.zone.EnforcementSnapshot
import com.spop.poverlay.zone.EnforcementState
import com.spop.poverlay.zone.SuspendReason
import com.spop.poverlay.zone.zoneBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private val CardColor = Color(0xFF1E1E1E)
private val HeadingColor = Color(0xFFF2F2F2)
private val BodyColor = Color(0xFFD0D0D0)
private val MutedColor = Color(0xFF8A8A8A)
private val SwitchOnColor = Color(0xFF22C55E)

/** How long to wait after the last keystroke before committing a typed number. */
private const val CommitDelayMs = 400L

/** How often to re-check whether media control is available. */
private const val ProbeIntervalMs = 3_000L

/**
 * Settings for the zone goal and its penalty.
 *
 * Built only from the controls the rest of the app already uses - switches, buttons and digit
 * fields - so it does not introduce a new visual language on a screen the user already knows.
 */
@Composable
internal fun ZoneGoalCard(
    viewModel: ConfigurationViewModel,
    uiScale: UiScale,
    onOpenHeartRateSettings: () -> Unit,
) {
    val enabled by viewModel.zoneEnforcementEnabled.collectAsStateWithLifecycle(initialValue = false)
    val penaltyEnabled by viewModel.zonePenaltyEnabled.collectAsStateWithLifecycle(initialValue = false)
    val targetZone by viewModel.zoneTargetZone.collectAsStateWithLifecycle(
        initialValue = ConfigurationRepository.DefaultTargetZone
    )
    val goalMinutes by viewModel.zoneGoalMinutes.collectAsStateWithLifecycle(
        initialValue = ConfigurationRepository.DefaultGoalMinutes
    )
    val graceSeconds by viewModel.zoneGraceSeconds.collectAsStateWithLifecycle(
        initialValue = ConfigurationRepository.DefaultGraceSeconds
    )
    val zones by viewModel.hrZones.collectAsStateWithLifecycle(initialValue = null)
    val snapshot by viewModel.zoneGoalSnapshot.collectAsStateWithLifecycle(initialValue = null)

    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = CardColor,
        elevation = uiScale.dp(4f),
    ) {
        Column(modifier = Modifier.padding(uiScale.dp(14f))) {
            Text(
                text = "Zone Goal",
                fontSize = uiScale.sp(18f),
                fontWeight = FontWeight.Bold,
                color = HeadingColor,
            )
            Spacer(Modifier.height(uiScale.dp(8f)))

            ToggleRow(
                label = "Track a heart rate zone goal",
                checked = enabled,
                onCheckedChange = viewModel::onZoneEnforcementEnabledClicked,
                uiScale = uiScale,
            )

            if (zones == null) {
                Spacer(Modifier.height(uiScale.dp(6f)))
                Text(
                    text = "Set your zone transitions first - without them there is nothing to enforce.",
                    fontSize = uiScale.sp(13f),
                    color = ErrorColor,
                )
                OutlinedButton(onClick = onOpenHeartRateSettings) {
                    Text("Open heart rate settings")
                }
            }

            if (enabled) {
                Spacer(Modifier.height(uiScale.dp(10f)))
                Text("Target zone", fontSize = uiScale.sp(14f), color = BodyColor)
                Spacer(Modifier.height(uiScale.dp(6f)))
                Row(horizontalArrangement = Arrangement.spacedBy(uiScale.dp(8f))) {
                    (ConfigurationRepository.MinTargetZone..ConfigurationRepository.MaxTargetZone)
                        .forEach { zone ->
                            ZoneButton(
                                zone = zone,
                                selected = zone == targetZone,
                                onClick = { viewModel.onZoneTargetZoneSelected(zone) },
                            )
                        }
                }
                Spacer(Modifier.height(uiScale.dp(6f)))
                Text(
                    text = bandCaption(targetZone, zones),
                    fontSize = uiScale.sp(14f),
                    color = zoneColor(targetZone),
                )
                if (targetZone == ConfigurationRepository.MinTargetZone) {
                    Spacer(Modifier.height(uiScale.dp(4f)))
                    Text(
                        text = "Zone 1 has no lower limit, so you can never drop below it - " +
                            "the video will never pause. Pick Zone 2 or higher to enforce.",
                        fontSize = uiScale.sp(12f),
                        color = ErrorColor,
                    )
                }

                Spacer(Modifier.height(uiScale.dp(12f)))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(uiScale.dp(12f)),
                ) {
                    NumberField(
                        label = "Goal (min)",
                        value = goalMinutes,
                        onCommit = viewModel::onZoneGoalMinutesChanged,
                        uiScale = uiScale,
                    )
                    NumberField(
                        label = "Grace (s)",
                        value = graceSeconds,
                        onCommit = viewModel::onZoneGraceSecondsChanged,
                        uiScale = uiScale,
                    )
                }
                Spacer(Modifier.height(uiScale.dp(4f)))
                Text(
                    text = "Credit only builds while your heart rate is inside the band. " +
                        "Grace is how long you can drift before a warning starts - heart rate " +
                        "lags effort by 20-30s, so short values punish physiology, not slacking.",
                    fontSize = uiScale.sp(12f),
                    color = MutedColor,
                )

                Spacer(Modifier.height(uiScale.dp(12f)))
                Divider(color = BodyColor.copy(alpha = 0.25f))
                Spacer(Modifier.height(uiScale.dp(10f)))

                ToggleRow(
                    label = "Pause the video when I drop out",
                    checked = penaltyEnabled,
                    onCheckedChange = viewModel::onZonePenaltyEnabledClicked,
                    uiScale = uiScale,
                )
                Text(
                    text = "Riding above the zone only warns - it never pauses.",
                    fontSize = uiScale.sp(12f),
                    color = MutedColor,
                )

                if (penaltyEnabled) {
                    Spacer(Modifier.height(uiScale.dp(10f)))
                    MediaControlSection(viewModel, uiScale)
                }
            }

            snapshot?.takeIf { it.state != EnforcementState.IDLE }?.let { live ->
                Spacer(Modifier.height(uiScale.dp(12f)))
                Divider(color = BodyColor.copy(alpha = 0.25f))
                Spacer(Modifier.height(uiScale.dp(10f)))
                Text(
                    text = "${elapsed(live.creditSeconds)} / ${elapsed(live.goalSeconds)}" +
                        " · ${statusLabel(live)}",
                    fontSize = uiScale.sp(14f),
                    color = BodyColor,
                )
                Spacer(Modifier.height(uiScale.dp(6f)))
                LinearProgressIndicator(
                    progress = live.progress,
                    color = zoneColor(live.targetZone),
                    backgroundColor = Color(0xFF333333),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun MediaControlSection(viewModel: ConfigurationViewModel, uiScale: UiScale) {
    // Probing means a Settings lookup and a binder call, so it runs off the composition thread
    // and on a timer rather than on every recomposition. Polling because the notification grant
    // can be flipped in Settings while this screen is open, with nothing to notify us.
    var capabilities by remember { mutableStateOf(MediaCapabilities(false, emptyList(), emptyList())) }
    LaunchedEffect(Unit) {
        while (true) {
            capabilities = withContext(Dispatchers.IO) { viewModel.mediaCapabilities() }
            delay(ProbeIntervalMs)
        }
    }

    Text(
        text = if (capabilities.notificationListenerGranted) {
            val playing = capabilities.playingPackages.firstOrNull()
            if (playing != null) {
                "Media control ready · $playing is playing"
            } else {
                "Media control ready · nothing playing right now"
            }
        } else {
            "Notification access not granted - falling back to media keys, which cannot tell " +
                "whether you paused the video yourself."
        },
        fontSize = uiScale.sp(13f),
        color = if (capabilities.notificationListenerGranted) BodyColor else MutedColor,
    )
    Spacer(Modifier.height(uiScale.dp(8f)))
    Row(horizontalArrangement = Arrangement.spacedBy(uiScale.dp(10f))) {
        if (!capabilities.notificationListenerGranted) {
            OutlinedButton(onClick = viewModel::onGrantNotificationAccessClicked) {
                Text("Grant access")
            }
        }
        Button(onClick = viewModel::onTestMediaPauseClicked) {
            Text("Test pause")
        }
    }
    Spacer(Modifier.height(uiScale.dp(6f)))
    Text(
        text = "Start a video, then tap Test pause to confirm this tablet honours it before " +
            "relying on it mid-ride.",
        fontSize = uiScale.sp(12f),
        color = MutedColor,
    )
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    uiScale: UiScale,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = uiScale.sp(15f), color = BodyColor)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = SwitchOnColor),
        )
    }
}

@Composable
private fun ZoneButton(zone: Int, selected: Boolean, onClick: () -> Unit) {
    val accent = zoneColor(zone)
    if (selected) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(backgroundColor = accent, contentColor = Color.Black),
        ) {
            Text("Z$zone", fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
        ) {
            Text("Z$zone")
        }
    }
}

/**
 * A digit field that commits after a pause in typing rather than on every keystroke - the
 * existing zone-transition fields write to SharedPreferences per character typed.
 */
@Composable
private fun NumberField(
    label: String,
    value: Int,
    onCommit: (Int) -> Unit,
    uiScale: UiScale,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    LaunchedEffect(text) {
        val parsed = text.toIntOrNull() ?: return@LaunchedEffect
        if (parsed == value) return@LaunchedEffect
        delay(CommitDelayMs)
        onCommit(parsed)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = uiScale.sp(13f), color = BodyColor)
        TextField(
            value = text,
            onValueChange = { text = it.filter { ch -> ch.isDigit() }.take(3) },
            modifier = Modifier.width(uiScale.dp(96f)),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

/**
 * "suspended" on its own tells the rider nothing actionable - the reason is what they can act on.
 */
private fun statusLabel(snapshot: EnforcementSnapshot): String {
    if (snapshot.state != EnforcementState.SUSPENDED) {
        return snapshot.state.name.lowercase().replace('_', ' ')
    }
    return when (snapshot.suspendReason) {
        SuspendReason.NOT_MOVING -> "paused - not pedalling"
        SuspendReason.NO_SIGNAL -> "waiting for heart rate"
        SuspendReason.STALE -> "heart rate signal lost"
        SuspendReason.NO_ZONES -> "set your zone transitions"
        SuspendReason.DISABLED -> "off"
        null -> "suspended"
    }
}

private fun bandCaption(zone: Int, zones: List<Int>?): String {
    val bounds = zoneBounds(zone, zones) ?: return "Zone $zone"
    val floor = bounds.floor
    val ceiling = bounds.ceiling
    return when {
        floor != null && ceiling != null -> "Zone $zone · $floor–${ceiling - 1} bpm"
        floor != null -> "Zone $zone · $floor+ bpm"
        ceiling != null -> "Zone $zone · under $ceiling bpm"
        else -> "Zone $zone"
    }
}

private fun elapsed(seconds: Long): String {
    val minutes = TimeUnit.SECONDS.toMinutes(seconds)
    val remainder = seconds - TimeUnit.MINUTES.toSeconds(minutes)
    return "%d:%02d".format(minutes, remainder)
}
