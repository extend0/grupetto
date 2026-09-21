package com.spop.poverlay.zone

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.spop.poverlay.overlay.OverlayLocation
import com.spop.poverlay.overlay.composables.OverlayMinimizedContent
import com.spop.poverlay.overlay.penalty.PenaltyCurtain
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class ZoneUiTest {
    @get:Rule val compose = createComposeRule()

    private fun base() = ZoneEnforcer(EnforcementConfig(enabled = true, penaltyEnabled = true))
        .tick(TickInput(0, 120, 0, listOf(100, 140, 160, 180), true))

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // Compose semantics can be ready before SurfaceFlinger presents the first frame.
        android.os.SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.cacheDir, "zone-ui-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun minimizedControlsRemainIndependentlyTappableAtBothScreenEdges() {
        val minimized = mutableStateOf(true)
        val location = mutableStateOf(OverlayLocation.Bottom)
        var timerTaps = 0
        var settingsTaps = 0
        compose.setContent {
            MaterialTheme {
                OverlayMinimizedContent(
                    isMinimized = minimized.value, showTimerWhenMinimized = true,
                    location = location.value, powerLabel = "150", cadenceLabel = "80",
                    speedLabel = "18", resistanceLabel = "40", heartRateLabel = "120",
                    contentAlpha = 1f, timerLabel = "12:34", timerPaused = false,
                    zoneGoal = base(), onTap = { timerTaps++ }, onLongPress = {},
                    onOpenSettings = { settingsTaps++ },
                    onMinimizeToggle = { minimized.value = !minimized.value }, onLayout = {},
                )
            }
        }
        for (edge in OverlayLocation.values()) {
            compose.runOnIdle { location.value = edge }
            repeat(3) {
                compose.onNodeWithContentDescription("Expand").performTouchInput { click() }
                compose.onNodeWithContentDescription("Minimize").assertIsDisplayed()
                    .performTouchInput { click() }
                compose.onNodeWithContentDescription("Expand").assertIsDisplayed()
            }
            compose.onNodeWithContentDescription("Open settings").performTouchInput { click() }
        }
        compose.runOnIdle {
            assertEquals(0, timerTaps)
            assertEquals(2, settingsTaps)
        }
    }

    @Test fun ridingStatusesAreVisibleInTheMinimizedOverlay() {
        val state = mutableStateOf(base())
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().background(Color(0xFF18222C)).padding(16.dp)) {
                    OverlayMinimizedContent(
                        isMinimized = true, showTimerWhenMinimized = true,
                        location = OverlayLocation.Top,
                        powerLabel = "150", cadenceLabel = "80", speedLabel = "18",
                        resistanceLabel = "40", heartRateLabel = "120", contentAlpha = 0.5f,
                        timerLabel = "12:34", timerPaused = false, zoneGoal = state.value,
                        onTap = {}, onLongPress = {}, onOpenSettings = {}, onMinimizeToggle = {}, onLayout = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Warm-up", substring = true).assertIsDisplayed()
        capture("warmup")
        val armed = base().copy(enforcementArmed = true, warmupRemainingSeconds = 0, creditSeconds = 120)
        compose.runOnIdle { state.value = armed.copy(earlyEffortWarning = true) }
        compose.onNodeWithText("Effort dropping — pick it up").assertIsDisplayed()
        capture("early")
        compose.runOnIdle { state.value = armed.copy(state = EnforcementState.WARNING, drift = Drift.BELOW, secondsUntilPenalty = 12) }
        compose.onNodeWithText("PAUSING IN 12s").assertIsDisplayed()
        compose.onNodeWithText("Z2 · 2:00 / 45:00").assertIsDisplayed()
        capture("countdown")
        compose.runOnIdle { state.value = armed.copy(state = EnforcementState.RIDING_BLIND, suspendReason = SuspendReason.NO_SIGNAL) }
        compose.onNodeWithText("Strap lost · counting from power").assertIsDisplayed()
        capture("blind")
        compose.runOnIdle { state.value = armed.copy(state = EnforcementState.COMPLETE) }
        compose.onNodeWithText("Goal complete").assertIsDisplayed()
        capture("complete")
        compose.runOnIdle { state.value = armed.copy(enforcementReleased = true) }
        compose.onNodeWithText("Enforcement ended").assertIsDisplayed()
    }

    @Test fun curtainExplainsRecoveryAndAllowsReleaseWithMissingHeartRate() {
        val state = mutableStateOf(base().copy(state = EnforcementState.PENALTY,
            drift = Drift.ABOVE, recoveryHolding = true, holdRemainingMs = 3_000,
            bpm = 150, smoothedBpm = 150, currentZone = 3))
        var releases = 0
        compose.setContent {
            MaterialTheme { PenaltyCurtain(state.value, onRelease = { releases++ }) }
        }
        compose.onNodeWithText("Hold it there").assertIsDisplayed()
        compose.onNodeWithText("102+ bpm for 5s").assertIsDisplayed()
        compose.onNodeWithText("3s of steady effort to resume").assertIsDisplayed()
        capture("recovery")
        compose.runOnIdle { state.value = state.value.copy(suspendReason = SuspendReason.NO_SIGNAL,
            smoothedBpm = null, recoveryHolding = false, holdRemainingMs = state.value.holdRequiredMs) }
        compose.onNodeWithText("Heart-rate signal lost").assertIsDisplayed()
        capture("missing-strap")
        compose.onNodeWithText("Hold to end enforcement").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, releases) }
    }
}
