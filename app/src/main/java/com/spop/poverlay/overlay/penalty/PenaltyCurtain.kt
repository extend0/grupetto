package com.spop.poverlay.overlay.penalty

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.spop.poverlay.ui.theme.zoneColor
import com.spop.poverlay.zone.Drift
import com.spop.poverlay.zone.EnforcementSnapshot
import java.util.concurrent.TimeUnit

/**
 * The full-screen penalty view: what the rider sees instead of their show.
 *
 * Everything here is derived from [snapshot] on every frame, so the curtain cannot get stuck
 * displaying state the machine has already left behind. [onRelease] is the escape hatch and is
 * always present - being locked out of your own television is not an acceptable failure mode.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PenaltyCurtain(
    snapshot: EnforcementSnapshot,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = zoneColor(snapshot.targetZone)
    val holdProgress = if (snapshot.holdRequiredMs <= 0) {
        0f
    } else {
        1f - (snapshot.holdRemainingMs.toFloat() / snapshot.holdRequiredMs).coerceIn(0f, 1f)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = instruction(snapshot),
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            snapshot.band?.let { band ->
                Text(
                    text = bandLabel(band.floor, band.ceiling),
                    color = accent,
                    fontSize = 20.sp,
                )
            }

            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(190.dp)) {
                    drawHoldRing(holdProgress, accent)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = snapshot.bpm?.toString() ?: "--",
                        color = zoneColor(snapshot.currentZone),
                        fontSize = 76.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(text = "bpm", color = Color(0xFFB0B0B0), fontSize = 16.sp)
                }
            }

            Text(
                text = holdLabel(snapshot, holdProgress),
                color = Color(0xFFD0D0D0),
                fontSize = 17.sp,
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "${elapsed(snapshot.creditSeconds)} / ${elapsed(snapshot.goalSeconds)}" +
                        " in Zone ${snapshot.targetZone}",
                    color = Color(0xFFD0D0D0),
                    fontSize = 16.sp,
                )
                LinearProgressIndicator(
                    progress = snapshot.progress,
                    color = accent,
                    backgroundColor = Color(0xFF333333),
                    modifier = Modifier
                        .size(width = 260.dp, height = 6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                )
            }

            // Deliberately a hold, not a tap: a stray knee should not end enforcement, but a
            // rider who genuinely wants out must never be more than a second away from it.
            Text(
                text = "Hold to end enforcement",
                color = Color(0xFF8A8A8A),
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                        onLongClick = onRelease,
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
}

private fun DrawScope.drawHoldRing(progress: Float, color: Color) {
    val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
    val inset = stroke.width / 2
    val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
    drawArc(
        color = Color(0xFF2A2A2A),
        startAngle = -90f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = arcSize,
        style = stroke,
    )
    if (progress > 0f) {
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = stroke,
        )
    }
}

private fun instruction(snapshot: EnforcementSnapshot) = when (snapshot.drift) {
    Drift.BELOW -> "Pick it up - get back to Zone ${snapshot.targetZone}"
    Drift.ABOVE -> "Ease off - back to Zone ${snapshot.targetZone}"
    null -> "Hold it there"
}

private fun bandLabel(floor: Int?, ceiling: Int?) = when {
    floor != null && ceiling != null -> "$floor–${ceiling - 1} bpm"
    floor != null -> "$floor+ bpm"
    ceiling != null -> "under $ceiling bpm"
    else -> ""
}

private fun holdLabel(snapshot: EnforcementSnapshot, holdProgress: Float): String {
    if (snapshot.drift != null) {
        return "Back in the zone for ${snapshot.holdRequiredMs / 1000}s to resume"
    }
    val remaining = ((snapshot.holdRemainingMs + 999) / 1000).coerceAtLeast(0)
    return if (holdProgress >= 1f) "Resuming…" else "Hold for ${remaining}s…"
}

private fun elapsed(seconds: Long): String {
    val minutes = TimeUnit.SECONDS.toMinutes(seconds)
    val remainder = seconds - TimeUnit.MINUTES.toSeconds(minutes)
    return "%d:%02d".format(minutes, remainder)
}
