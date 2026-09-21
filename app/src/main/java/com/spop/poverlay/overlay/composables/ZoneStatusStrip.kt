package com.spop.poverlay.overlay.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
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
fun ZoneStatusStrip(snapshot: EnforcementSnapshot, compact: Boolean = false) {
    val status = snapshot.ridingStatus() ?: return
    val accent = when (status.tone) {
        ZoneStatusTone.URGENT -> ZonePenaltyScrimColor
        ZoneStatusTone.CAUTION -> ZoneWarnScrimColor
        ZoneStatusTone.GOOD -> Color(0xFF81C784)
        ZoneStatusTone.NORMAL -> Color.White
    }
    Column(
        modifier = Modifier.width(if (compact) 490.dp else 310.dp)
            .background(accent.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = if (compact) 3.dp else 6.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp),
    ) {
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(status.title, color = accent, fontSize = 15.sp, lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text(snapshot.goalTimeLabel(), color = Color(0xFFE0E0E0), fontSize = 12.sp,
                    lineHeight = 14.sp, maxLines = 1)
            }
            Text(status.detail, color = Color(0xFFE0E0E0), fontSize = 12.sp, lineHeight = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Text(status.title, color = accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(status.detail, color = Color(0xFFE0E0E0), fontSize = 13.sp)
            Text(snapshot.goalTimeLabel(), color = Color(0xFFCCCCCC), fontSize = 12.sp)
        }
        LinearProgressIndicator(
            progress = snapshot.progress,
            color = zoneColor(snapshot.targetZone),
            backgroundColor = Color(0xFF444444),
            modifier = Modifier.fillMaxWidth().height(3.dp),
        )
    }
}
