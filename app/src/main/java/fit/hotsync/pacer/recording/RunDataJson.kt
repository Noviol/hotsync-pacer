package fit.hotsync.pacer.recording

import org.json.JSONArray
import org.json.JSONObject

/**
 * The run's full data for HotSync (kept on hotsync.fit, never sent to Strava):
 * heart rate, cadence, GPS, elevation and running dynamics, one value per second.
 *
 * Shaped like Strava's streams (keyed by type, aligned arrays, "time" in seconds
 * since start) so the Stats code can use it exactly like Strava data. Missing
 * values are null.
 */
object RunDataJson {

    fun write(recording: RunRecording, laps: List<LapInfo>): String {
        val points = recording.points
        val t0 = points.firstOrNull()?.epochMs ?: recording.startEpochMs
        fun <T> stream(get: (TrackPoint) -> T?): JSONArray = JSONArray().apply {
            points.forEach { put(get(it) ?: JSONObject.NULL) }
        }
        val streams = JSONObject()
            .put("time", stream { (it.epochMs - t0) / 1000.0 })
            .put("distance", stream { it.distanceM })
            .put("heartrate", stream { it.heartRate })
            .put("altitude", stream { it.altitudeM })
            .put("cadence", stream { it.cadenceSpm?.div(2) })          // strides/min, like Strava
            .put("steps_per_minute", stream { it.cadenceSpm })
            .put("latlng", stream { p -> if (p.lat != null && p.lon != null) JSONArray().put(p.lat).put(p.lon) else null })
            .put("ground_contact_ms", stream { it.groundContactMs })
            .put("vertical_oscillation_cm", stream { it.verticalOscillationCm })
            .put("vertical_ratio_pct", stream { it.verticalRatioPct })
            .put("stride_length_m", stream { it.strideLengthM })
            .put("segment", stream { it.segment })

        val lapArray = JSONArray()
        laps.forEach { lap ->
            lapArray.put(JSONObject().put("segment", lap.segment).put("label", lap.label)
                .put("duration_ms", lap.durationMs ?: JSONObject.NULL))
        }
        return JSONObject()
            .put("version", 1)
            .put("start_epoch_ms", t0)
            .put("laps", lapArray)
            .put("streams", streams)
            .toString()
    }
}
