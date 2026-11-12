package fit.hotsync.pacer.workout

/**
 * Three quick, distinct taps (e.g. to pause in the rain, when swipes fail on a
 * wet screen). A sleeve brushing the screen makes drags or long presses, which
 * the UI reports via [reset] instead of [tap].
 */
class TripleTapDetector(
    private val windowMs: Long = 800,   // all three taps within this
    private val maxGapMs: Long = 400,   // and no gap longer than this
) {
    private val taps = ArrayDeque<Long>()

    /** Register a tap at [atMs]; returns true when it completes a triple tap. */
    fun tap(atMs: Long): Boolean {
        if (taps.isNotEmpty() && atMs - taps.last() > maxGapMs) taps.clear()
        taps.addLast(atMs)
        while (taps.size > 3) taps.removeFirst()
        if (taps.size == 3 && taps.last() - taps.first() <= windowMs) {
            taps.clear()
            return true
        }
        return false
    }

    fun reset() = taps.clear()
}
