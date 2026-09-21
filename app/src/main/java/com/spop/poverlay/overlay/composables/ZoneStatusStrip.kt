package com.spop.poverlay.overlay.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.ui.theme.ZonePenaltyScrimColor
import com.spop.poverlay.ui.theme.ZoneWarnScrimColor
import com.spop.poverlay.ui.theme.zoneColor
import com.spop.poverlay.zone.*

@Composable
fun ZoneStatusStrip(snapshot: EnforcementSnapshot) {
    val status = snapshot.ridingStatus() ?: return
    val accent = when (status.tone) {
        ZoneStatusTone.URGENT -> ZonePenaltyScrimColor
        ZoneStatusTone.CAUTION -> ZoneWarnScrimColor
        ZoneStatusTone.GOOD -> Color(0xFF81C784)
        ZoneStatusTone.NORMAL -> Color.White
    }
    Column(
        modifier = Modifier.width(310.dp)
            .background(accent.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(status.title, color = accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(status.detail, color = Color(0xFFE0E0E0), fontSize = 13.sp)
        Text(snapshot.goalTimeLabel(), color = Color(0xFFCCCCCC), fontSize = 12.sp)
        LinearProgressIndicator(
            progress = snapshot.progress,
            color = zoneColor(snapshot.targetZone),
            backgroundColor = Color(0xFF444444),
            modifier = Modifier.fillMaxWidth().height(3.dp),
        )
    }
}
