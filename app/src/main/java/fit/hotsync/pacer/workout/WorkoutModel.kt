package fit.hotsync.pacer.workout

/**
 * Structured workout model — mirrors the step format served by hotsync.fit
 * (`GET /watch/sync`), where zones have already been resolved into target ranges.
 *
 * Rules (WATCH_PLAN.md):
 *  - A workout targets EITHER heart rate OR pace, never both.
 *  - Steps are time, distance, or open (ends on lap press), plus one-level repeats.
 *  - Every segment has a user-given label, shown on the watch.
 */

enum class TargetMode { HR, PACE }

/** Target range for a segment. */
sealed interface Target {
    val zoneName: String

    /** Heart-rate range in bpm. */
    data class Hr(override val zoneName: String, val lowBpm: Int, val highBpm: Int) : Target

    /**
     * Pace range in seconds per km. [slowSecPerKm] is the larger number
     * (e.g. 7:00 /km = 420), [fastSecPerKm] the smaller (6:30 /km = 390).
     */
    data class Pace(override val zoneName: String, val slowSecPerKm: Int, val fastSecPerKm: Int) : Target
}

/** How a segment ends. */
sealed interface Duration {
    data class Time(val seconds: Int) : Duration
    data class Distance(val meters: Double) : Duration
    /** Runs until the lap button is pressed. */
    data object Open : Duration
}

sealed interface Step {
    data class Segment(val label: String, val duration: Duration, val target: Target? = null) : Step
    data class Repeat(val count: Int, val steps: List<Segment>) : Step
}

data class Workout(
    val id: String,
    val name: String,
    val mode: TargetMode,
    val steps: List<Step>,
)

/**
 * One segment as it will actually be run, with repeats expanded.
 * [set] / [setCount] are filled inside repeat blocks so the watch can show "2/3".
 */
data class PlannedSegment(
    val index: Int,
    val label: String,
    val duration: Duration,
    val target: Target?,
    val set: Int? = null,
    val setCount: Int? = null,
) {
    val setText: String? get() = if (set != null && setCount != null) "$set/$setCount" else null
}

/** Expand repeats into the flat list of segments the engine walks through. */
fun Workout.expand(): List<PlannedSegment> {
    val out = mutableListOf<PlannedSegment>()
    for (step in steps) {
        when (step) {
            is Step.Segment -> out += PlannedSegment(out.size, step.label, step.duration, step.target)
            is Step.Repeat -> for (set in 1..step.count) {
                for (seg in step.steps) {
                    out += PlannedSegment(out.size, seg.label, seg.duration, seg.target, set, step.count)
                }
            }
        }
    }
    return out
}

/** Hardcoded sample used until sync exists (WATCH_PLAN.md example, RP12). */
object SampleWorkouts {
    private val z1 = Target.Hr("Zone 1", 126, 139)
    private val z3 = Target.Hr("Zone 3", 150, 160)

    val rp12 = Workout(
        id = "sample-rp12",
        name = "RP12 (Progression Run)",
        mode = TargetMode.HR,
        steps = listOf(
            Step.Segment("Warm-up", Duration.Time(600), z1),
            Step.Repeat(
                4, listOf(
                    Step.Segment("Fast", Duration.Distance(1000.0), z3),
                    Step.Segment("Recover", Duration.Time(120), z1),
                )
            ),
            Step.Segment("Cool-down", Duration.Open, z1),
        ),
    )
}
