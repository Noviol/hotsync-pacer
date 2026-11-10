package fit.hotsync.pacer.recording

/** One recorded second of the run. */
data class TrackPoint(
    val epochMs: Long,
    val lat: Double?,
    val lon: Double?,
    val altitudeM: Double?,
    val distanceM: Double,
    val heartRate: Int?,
    val cadenceSpm: Int?,   // steps per minute
    val segment: Int,       // engine segment index; == segment count after the workout completes
    val groundContactMs: Double? = null,
    val verticalOscillationCm: Double? = null,
    val verticalRatioPct: Double? = null,
    val strideLengthM: Double? = null,
    val hrSuspect: Boolean = false,  // HR looked locked to cadence at this second
)

/** Lap info the writer needs from the workout engine. */
data class LapInfo(val segment: Int, val label: String, val durationMs: Long?)

class RunRecording(val startEpochMs: Long) {
    private val _points = ArrayList<TrackPoint>(12_000)
    val points: List<TrackPoint> get() = _points

    fun add(point: TrackPoint) {
        _points += point
    }
}
