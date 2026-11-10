package fit.hotsync.pacer.recording

/**
 * Every sensor sample the watch delivered, with the time it was MEASURED.
 *
 * With the screen off, Health Services delivers data in batches (often 30 s+).
 * Keeping only the latest value per batch threw away most GPS fixes and held
 * stale HR values; keeping every sample and matching by timestamp fixes both.
 */
data class LocationSample(val epochMs: Long, val lat: Double, val lon: Double, val altitudeM: Double?)

data class HrSample(val epochMs: Long, val bpm: Int)

class SampleHistory {
    private val locations = ArrayList<LocationSample>(20_000)
    private val heartRates = ArrayList<HrSample>(20_000)

    @Synchronized fun addLocation(s: LocationSample) { locations += s }
    @Synchronized fun addHeartRate(s: HrSample) { heartRates += s }
    @Synchronized fun snapshot(): Pair<List<LocationSample>, List<HrSample>> =
        locations.sortedBy { it.epochMs } to heartRates.sortedBy { it.epochMs }
    @Synchronized fun clear() { locations.clear(); heartRates.clear() }
}

object TrackResolver {

    /** Max distance in time between a recorded second and the sample used for it. */
    private const val LOCATION_WINDOW_MS = 3_000L
    private const val HR_WINDOW_MS = 5_000L

    /**
     * Fill each track point's position and heart rate from the samples measured
     * closest to that second. Points with no sample nearby keep what was recorded
     * live (position null if there was none).
     */
    fun resolve(points: List<TrackPoint>, locations: List<LocationSample>, heartRates: List<HrSample>): List<TrackPoint> {
        if (locations.isEmpty() && heartRates.isEmpty()) return points
        val locTimes = LongArray(locations.size) { locations[it].epochMs }
        val hrTimes = LongArray(heartRates.size) { heartRates[it].epochMs }
        return points.map { p ->
            val loc = nearest(locTimes, p.epochMs, LOCATION_WINDOW_MS)?.let { locations[it] }
            val hr = nearest(hrTimes, p.epochMs, HR_WINDOW_MS)?.let { heartRates[it] }
            p.copy(
                lat = loc?.lat ?: p.lat,
                lon = loc?.lon ?: p.lon,
                altitudeM = p.altitudeM ?: loc?.altitudeM,
                heartRate = hr?.bpm ?: p.heartRate,
            )
        }
    }

    /** Index of the time closest to [t] within [window], via binary search on sorted times. */
    private fun nearest(times: LongArray, t: Long, window: Long): Int? {
        if (times.isEmpty()) return null
        var lo = 0
        var hi = times.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (times[mid] < t) lo = mid + 1 else hi = mid
        }
        val candidates = listOf(lo - 1, lo).filter { it in times.indices }
        val best = candidates.minByOrNull { kotlin.math.abs(times[it] - t) } ?: return null
        return if (kotlin.math.abs(times[best] - t) <= window) best else null
    }
}
