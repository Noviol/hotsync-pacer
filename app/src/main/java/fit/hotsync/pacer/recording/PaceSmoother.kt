package fit.hotsync.pacer.recording

/**
 * Smoothed pace from cumulative distance: distance covered over the last
 * [windowMs]. Raw GPS pace jumps around by a minute/km or more, which would
 * make the gauge flicker and fire false alerts; a ~20 s window settles it
 * while still reacting within a few seconds to a real change of effort.
 */
class PaceSmoother(private val windowMs: Long = 20_000, private val minWindowMs: Long = 6_000) {

    private val samples = ArrayDeque<Pair<Long, Double>>() // (elapsedMs, cumulative meters)

    fun add(atMs: Long, distanceM: Double) {
        if (samples.isNotEmpty() && atMs <= samples.last().first) return
        samples.addLast(atMs to distanceM)
        while (samples.size > 2 && atMs - samples[1].first >= windowMs) samples.removeFirst()
    }

    /** Seconds per km, or null when standing still / not enough data. */
    fun paceSecPerKm(nowMs: Long): Double? {
        if (samples.size < 2) return null
        val (t0, d0) = samples.first()
        val (t1, d1) = samples.last()
        if (nowMs - t1 > 5_000) return null           // distance has stopped updating: stopped
        val dt = t1 - t0
        val dd = d1 - d0
        if (dt < minWindowMs || dd < 3.0) return null  // too little to judge, or standing
        val pace = (dt / 1000.0) / (dd / 1000.0)
        return pace.takeIf { it in 90.0..1800.0 }      // 1:30–30:00 /km; outside is noise
    }

    fun reset() = samples.clear()
}
