package com.spop.poverlay.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
val ErrorColor = Color(0.769f, 0.008f, 0.008f, 1.0f)

// Metric colors for overlay
val MetricPowerColor = Color(0xFFFFEB3B)      // Yellow
val MetricCadenceColor = Color(0xFF4CAF50)    // Green
val MetricSpeedColor = Color(0xFF2196F3)      // Blue
val MetricResistanceColor = Color(0xFFD9182B) // Red
val MetricHeartRateColor = Color(0xFFFF5252)  // Light red
val MetricCalorieColor = Color(color = 0xFFC0C0C0)    // light grey

// Heart rate zones, cool to hot
val Zone1Color = Color(0xFF9E9E9E)            // Grey
val Zone2Color = Color(0xFF29B6F6)            // Light blue
val Zone3Color = Color(0xFF66BB6A)            // Green
val Zone4Color = Color(0xFFFFA726)            // Orange
val Zone5Color = Color(0xFFEF5350)            // Red

// Escalation accents, shared by the overlay strip and the penalty curtain
val ZoneWarnScrimColor = Color(0xFFFFB300)    // Amber - you have drifted
val ZonePenaltyScrimColor = Color(0xFFFF5252) // Red - a pause is coming

/** Falls back to the plain heart rate colour when the zone is unknown. */
fun zoneColor(zone: Int?): Color = when (zone) {
    1 -> Zone1Color
    2 -> Zone2Color
    3 -> Zone3Color
    4 -> Zone4Color
    5 -> Zone5Color
    else -> MetricHeartRateColor
}
