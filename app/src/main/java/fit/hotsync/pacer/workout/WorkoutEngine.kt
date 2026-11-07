package fit.hotsync.pacer.workout

/**
 * WorkoutEngine
 * ----------------------------------------------------------------------------
 * Walks a structured workout segment by segment. Pure Kotlin with no Android
 * or sensor dependencies: the caller feeds it a monotonic clock
 * (SystemClock.elapsedRealtime) and the cumulative distance from the sensors,
 * which keeps it fully unit-testable.
 *
 *  - Time segments end automatically after their duration of ACTIVE time (pauses excluded).
 *  - Distance segments end automatically once the distance is covered.
 *  - Open segments end only on [lap]. A lap also ends any other segment early.
 *  - Auto-ended segments end exactly on their boundary (not on the late tick),
 *    so small tick delays never accumulate into drift.
 *
 * Not thread-safe; call it from one thread (the service's clock loop).
 */
class WorkoutEngine(val workout: Workout) {

    enum class EndReason { AUTO, LAP }

    /** A finished segment, kept for the summary screen and the activity file's laps. */
    data class CompletedSegment(
        val segment: PlannedSegment,
        val startActiveMs: Long,
        val endActiveMs: Long,
        val startDistanceM: Double,
        val endDistanceM: Double,
        val endedBy: EndReason,
    ) {
        val durationMs: Long get() = endActiveMs - startActiveMs
        val distanceM: Double get() = endDistanceM - startDistanceM
    }

    data class Snapshot(
        val segment: PlannedSegment?,       // null once the workout is complete
        val next: PlannedSegment?,
        val segmentNumber: Int,             // 1-based
        val segmentCount: Int,
        val segmentElapsedMs: Long,
        val segmentDistanceM: Double,
        val remainingMs: Long?,             // time segments
        val remainingM: Double?,            // distance segments
        val totalActiveMs: Long,
        val totalDistanceM: Double,
        val isStarted: Boolean,
        val isPaused: Boolean,
        val isComplete: Boolean,
    )

    val segments: List<PlannedSegment> = workout.expand()

    private val _completed = mutableListOf<CompletedSegment>()
    val completed: List<CompletedSegment> get() = _completed

    var index: Int = 0
        private set
    var isStarted = false
        private set
    var isPaused = false
        private set
    val isComplete: Boolean get() = index >= segments.size

    // Active-time bookkeeping (pauses excluded)
    private var activeBeforeResumeMs = 0L
    private var resumedAtMs = 0L

    // Where the current segment began
    private var segStartActiveMs = 0L
    private var segStartDistanceM = 0.0

    private var lastDistanceM = 0.0
    private var workoutStartDistanceM = 0.0

    fun activeMs(nowMs: Long): Long =
        if (!isStarted) 0L
        else if (isPaused) activeBeforeResumeMs
        else activeBeforeResumeMs + (nowMs - resumedAtMs)

    fun start(nowMs: Long, distanceM: Double = 0.0) {
        check(!isStarted) { "Already started" }
        isStarted = true
        resumedAtMs = nowMs
        segStartActiveMs = 0L
        segStartDistanceM = distanceM
        lastDistanceM = distanceM
        workoutStartDistanceM = distanceM
    }

    fun pause(nowMs: Long) {
        if (!isStarted || isPaused) return
        activeBeforeResumeMs = activeMs(nowMs)
        isPaused = true
    }

    fun resume(nowMs: Long) {
        if (!isStarted || !isPaused) return
        resumedAtMs = nowMs
        isPaused = false
    }

    /**
     * Advance auto-ending segments. Call every tick with the latest cumulative distance.
     * Returns the snapshot to display.
     */
    fun update(nowMs: Long, distanceM: Double = lastDistanceM): Snapshot {
        lastDistanceM = maxOf(lastDistanceM, distanceM)  // distance never goes backwards
        if (isStarted && !isPaused) {
            val active = activeMs(nowMs)
            // Loop in case one late tick crossed more than one boundary
            while (!isComplete) {
                val seg = segments[index]
                when (val d = seg.duration) {
                    is Duration.Time -> {
                        val boundary = segStartActiveMs + d.seconds * 1000L
                        if (active < boundary) break
                        endSegment(boundary, lastDistanceM, EndReason.AUTO)
                    }
                    is Duration.Distance -> {
                        val boundary = segStartDistanceM + d.meters
                        if (lastDistanceM < boundary) break
                        endSegment(active, boundary, EndReason.AUTO)
                    }
                    Duration.Open -> break
                }
            }
        }
        return snapshot(nowMs)
    }

    /** Lap button: ends the current segment now, whatever its kind. */
    fun lap(nowMs: Long, distanceM: Double = lastDistanceM): Snapshot {
        lastDistanceM = maxOf(lastDistanceM, distanceM)
        if (isStarted && !isComplete) {
            endSegment(activeMs(nowMs), lastDistanceM, EndReason.LAP)
        }
        return update(nowMs, lastDistanceM)
    }

    private fun endSegment(endActiveMs: Long, endDistanceM: Double, reason: EndReason) {
        _completed += CompletedSegment(
            segment = segments[index],
            startActiveMs = segStartActiveMs,
            endActiveMs = endActiveMs,
            startDistanceM = segStartDistanceM,
            endDistanceM = endDistanceM,
            endedBy = reason,
        )
        index++
        segStartActiveMs = endActiveMs
        segStartDistanceM = endDistanceM
    }

    fun snapshot(nowMs: Long): Snapshot {
        val active = activeMs(nowMs)
        val seg = segments.getOrNull(index)
        val segElapsed = active - segStartActiveMs
        val segDistance = lastDistanceM - segStartDistanceM
        return Snapshot(
            segment = seg,
            next = segments.getOrNull(index + 1),
            segmentNumber = minOf(index + 1, segments.size),
            segmentCount = segments.size,
            segmentElapsedMs = if (seg != null) segElapsed else 0L,
            segmentDistanceM = if (seg != null) segDistance else 0.0,
            remainingMs = (seg?.duration as? Duration.Time)?.let { it.seconds * 1000L - segElapsed },
            remainingM = (seg?.duration as? Duration.Distance)?.let { it.meters - segDistance },
            totalActiveMs = active,
            totalDistanceM = lastDistanceM - workoutStartDistanceM,
            isStarted = isStarted,
            isPaused = isPaused,
            isComplete = isComplete,
        )
    }
}
