package com.spop.poverlay.workout

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** No fabricated position; distance is the bike's power-derived estimate. */
object TcxExporter {
    fun export(workout: Workout, samples: List<WorkoutSample>): String {
        require(workout.endedAt != null) { "Finish the workout before exporting." }
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun time(ms: Long) = format.format(Date(ms))
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            append("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\" xmlns:ns3=\"http://www.garmin.com/xmlschemas/ActivityExtension/v2\"><Activities><Activity Sport=\"Biking\">")
            append("<Id>${time(workout.startedAt)}</Id><Lap StartTime=\"${time(workout.startedAt)}\">")
            append("<TotalTimeSeconds>${(workout.endedAt - workout.startedAt).coerceAtLeast(0) / 1000.0}</TotalTimeSeconds>")
            append("<DistanceMeters>${workout.distanceMeters}</DistanceMeters><Calories>0</Calories><Intensity>Active</Intensity><TriggerMethod>Manual</TriggerMethod><Track>")
            samples.forEach { s ->
                append("<Trackpoint><Time>${time(s.timeMs)}</Time><DistanceMeters>${s.distanceMeters}</DistanceMeters>")
                s.heartRate?.let { append("<HeartRateBpm><Value>$it</Value></HeartRateBpm>") }
                s.cadence?.let { append("<Cadence>${it.roundToInt().coerceIn(0, 254)}</Cadence>") }
                if (s.speedMps != null || s.watts != null) {
                    append("<Extensions><ns3:TPX>")
                    s.speedMps?.let { append("<ns3:Speed>$it</ns3:Speed>") }
                    s.watts?.let { append("<ns3:Watts>${it.roundToInt().coerceIn(0, 65535)}</ns3:Watts>") }
                    append("</ns3:TPX></Extensions>")
                }
                append("</Trackpoint>")
            }
            append("</Track></Lap><Notes>Recorded by Grupetto. Indoor ride; distance is estimated from power.</Notes></Activity></Activities></TrainingCenterDatabase>")
        }
    }
}
