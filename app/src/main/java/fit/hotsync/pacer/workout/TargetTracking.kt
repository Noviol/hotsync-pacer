package fit.hotsync.pacer.workout

/**
 * Where the runner sits relative to the segment target.
 * BELOW = easier than target (HR too low, or pace too slow); ABOVE = harder.
 */
enum class TargetStatus { BELOW, IN, ABOVE, UNKNOWN }

object TargetMath {

    /** [value] is bpm for HR targets and seconds per km for pace targets. */
    fun status(target: Target?, value: Double?): TargetStatus {
        if (target == null || value == null || value <= 0.0) return TargetStatus.UNKNOWN
        return when (target) {
            is Target.Hr -> when {
                value < target.lowBpm -> TargetStatus.BELOW
                value > target.highBpm -> TargetStatus.ABOVE
                else -> TargetStatus.IN
            }
            // Pace: a bigger number is slower, i.e. easier
            is Target.Pace -> when {
                value > target.slowSecPerKm -> TargetStatus.BELOW
                value < target.fastSecPerKm -> TargetStatus.ABOVE
                else -> TargetStatus.IN
            }
        }
    }

    /**
     * Pointer position on the workout gauge, 0.0 (far left) to 1.0 (far right).
     * The target range fills the green middle third; each red third covers one
     * more range-width below/above. Faster pace moves right, like higher HR.
     */
    fun gaugePosition(target: Target?, value: Double?): Double? {
        if (target == null || value == null || value <= 0.0) return null
        val (low, high, v) = when (target) {
            is Target.Hr -> Triple(target.lowBpm.toDouble(), target.highBpm.toDouble(), value)
            // Negate pace so "faster" (smaller sec/km) becomes a larger number
            is Target.Pace -> Triple(-target.slowSecPerKm.toDouble(), -target.fastSecPerKm.toDouble(), -value)
        }
        val width = (high - low).coerceAtLeast(1.0)
        val position = (v - (low - width)) / (3 * width)
        return position.coerceIn(0.0, 1.0)
    }
}

/**
 * Decides when to alert. An alert fires only after the runner has been out of
 * range continuously for [delayMs] (0 = immediately), and the grace period
 * restarts on every new segment so the start of a segment never alerts.
 *
 * The visual bubble stays up while the alert is active; [Update.fire] is true
 * only on the tick it starts, so haptic/sound play once per excursion.
 */
class AlertTracker(private val delayMs: Long) {

    data class Update(val active: TargetStatus?, val fire: Boolean)

    private var outSinceMs: Long? = null
    private var outStatus: TargetStatus? = null
    private var segmentIndex: Int = -1
    private var alerted = false

    fun update(status: TargetStatus, nowMs: Long, segmentIndex: Int): Update {
        if (segmentIndex != this.segmentIndex) {
            this.segmentIndex = segmentIndex
            reset()
        }
        if (status == TargetStatus.IN || status == TargetStatus.UNKNOWN) {
            reset()
            return Update(null, false)
        }
        // Crossing straight from below to above (or back) restarts the clock
        if (status != outStatus) {
            outStatus = status
            outSinceMs = nowMs
            alerted = false
        }
        if (nowMs - (outSinceMs ?: nowMs) < delayMs) return Update(null, false)
        val fire = !alerted
        alerted = true
        return Update(status, fire)
    }

    fun reset() {
        outSinceMs = null
        outStatus = null
        alerted = false
    }
}
