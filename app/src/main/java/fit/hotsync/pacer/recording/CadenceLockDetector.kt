package fit.hotsync.pacer.recording

/**
 * Wrist optical HR can "lock onto" running cadence: the arm-swing rhythm is read
 * as the pulse, so HR sits at the step rate (e.g. 167 bpm at 167 spm). On a real
 * tempo run the Pixel's HR matched cadence (±2) 49% of the time vs 12% for Garmin.
 *
 * Feed one (HR, steps-per-minute) reading per second. The HR is flagged suspect
 * when it has tracked cadence for most of the last minute while running, and
 * cleared only once it clearly hasn't (hysteresis, so the flag doesn't flicker).
 */
class CadenceLockDetector(
    private val windowSeconds: Int = 60,
    private val tolerance: Int = 3,
    private val lockOn: Double = 0.70,
    private val lockOff: Double = 0.40,
    private val minRunningSpm: Int = 140,
) {
    private val window = ArrayDeque<Boolean>()
    var locked = false
        private set

    /** Returns whether HR is currently suspect. */
    fun update(hr: Int?, spm: Int?): Boolean {
        if (hr == null || spm == null || spm < minRunningSpm) {
            // Walking or no data: cadence lock isn't plausible, let the window drain
            window.addLast(false)
        } else {
            window.addLast(kotlin.math.abs(hr - spm) <= tolerance)
        }
        while (window.size > windowSeconds) window.removeFirst()
        if (window.size >= windowSeconds / 2) {
            val share = window.count { it }.toDouble() / window.size
            locked = if (locked) share > lockOff else share >= lockOn
        }
        return locked
    }

    fun reset() {
        window.clear()
        locked = false
    }
}
