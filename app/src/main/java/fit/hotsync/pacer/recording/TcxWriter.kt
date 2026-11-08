package fit.hotsync.pacer.recording

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Writes a run as TCX (Garmin Training Center XML), which Strava imports with
 * GPS, elevation, distance, heart rate, cadence and laps. Each workout segment
 * becomes one lap, so intervals show up in Strava's Laps tab.
 */
object TcxWriter {

    fun write(recording: RunRecording, laps: List<LapInfo>, creator: String): String {
        val points = recording.points
        val sb = StringBuilder(points.size * 260 + 2_000)
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2" """)
        sb.append("""xmlns:ns3="http://www.garmin.com/xmlschemas/ActivityExtension/v2" """)
        sb.append("""xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""").append('\n')
        sb.append("<Activities>\n<Activity Sport=\"Running\">\n")
        sb.append("<Id>").append(time(recording.startEpochMs)).append("</Id>\n")

        val lapInfo = laps.associateBy { it.segment }
        val groups = groupConsecutive(points)
        var lapStartDistance = points.firstOrNull()?.distanceM ?: 0.0
        if (groups.isEmpty()) {
            // A Lap is required; write an empty one so the file is still valid
            appendLap(sb, recording.startEpochMs, 0.0, 0.0, emptyList(), null)
        }
        for (group in groups) {
            val endDistance = group.last().distanceM
            val info = lapInfo[group.first().segment]
            val spanSeconds = (group.last().epochMs - group.first().epochMs) / 1000.0 + 1.0
            val seconds = info?.durationMs?.let { it / 1000.0 } ?: spanSeconds
            appendLap(sb, group.first().epochMs, seconds, endDistance - lapStartDistance, group, info?.label)
            lapStartDistance = endDistance
        }

        sb.append("<Creator xsi:type=\"Device_t\"><Name>").append(escape(creator))
            .append("</Name><UnitId>0</UnitId><ProductID>0</ProductID>")
            .append("<Version><VersionMajor>1</VersionMajor><VersionMinor>0</VersionMinor></Version></Creator>\n")
        sb.append("</Activity>\n</Activities>\n</TrainingCenterDatabase>\n")
        return sb.toString()
    }

    private fun groupConsecutive(points: List<TrackPoint>): List<List<TrackPoint>> {
        val out = mutableListOf<MutableList<TrackPoint>>()
        for (p in points) {
            if (out.isEmpty() || out.last().last().segment != p.segment) out += mutableListOf(p) else out.last() += p
        }
        return out
    }

    private fun appendLap(
        sb: StringBuilder, startMs: Long, seconds: Double, meters: Double, points: List<TrackPoint>, label: String?,
    ) {
        val hrs = points.mapNotNull { it.heartRate }
        sb.append("<Lap StartTime=\"").append(time(startMs)).append("\">\n")
        sb.append("<TotalTimeSeconds>").append(num(seconds)).append("</TotalTimeSeconds>\n")
        sb.append("<DistanceMeters>").append(num(meters.coerceAtLeast(0.0))).append("</DistanceMeters>\n")
        sb.append("<Calories>0</Calories>\n")
        if (hrs.isNotEmpty()) {
            sb.append("<AverageHeartRateBpm><Value>").append(hrs.average().roundToInt()).append("</Value></AverageHeartRateBpm>\n")
            sb.append("<MaximumHeartRateBpm><Value>").append(hrs.max()).append("</Value></MaximumHeartRateBpm>\n")
        }
        sb.append("<Intensity>Active</Intensity>\n<TriggerMethod>Manual</TriggerMethod>\n")
        if (points.isNotEmpty()) {
            sb.append("<Track>\n")
            for (p in points) appendPoint(sb, p)
            sb.append("</Track>\n")
        }
        if (label != null) sb.append("<Notes>").append(escape(label)).append("</Notes>\n")
        sb.append("</Lap>\n")
    }

    private fun appendPoint(sb: StringBuilder, p: TrackPoint) {
        sb.append("<Trackpoint><Time>").append(time(p.epochMs)).append("</Time>")
        if (p.lat != null && p.lon != null) {
            sb.append("<Position><LatitudeDegrees>").append(coord(p.lat))
                .append("</LatitudeDegrees><LongitudeDegrees>").append(coord(p.lon))
                .append("</LongitudeDegrees></Position>")
        }
        p.altitudeM?.let { sb.append("<AltitudeMeters>").append(num(it)).append("</AltitudeMeters>") }
        sb.append("<DistanceMeters>").append(num(p.distanceM)).append("</DistanceMeters>")
        p.heartRate?.let { sb.append("<HeartRateBpm><Value>").append(it).append("</Value></HeartRateBpm>") }
        // Garmin's RunCadence counts one foot (strides/min), i.e. half the steps per minute
        p.cadenceSpm?.let {
            sb.append("<Extensions><ns3:TPX><ns3:RunCadence>").append(it / 2)
                .append("</ns3:RunCadence></ns3:TPX></Extensions>")
        }
        sb.append("</Trackpoint>\n")
    }

    private fun time(epochMs: Long) = Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS).toString()
    private fun num(v: Double) = "%.1f".format(java.util.Locale.ROOT, v)
    private fun coord(v: Double) = "%.7f".format(java.util.Locale.ROOT, v)
    private fun escape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
