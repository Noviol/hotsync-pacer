package fit.hotsync.pacer.recording

import kotlin.math.exp

/**
 * Smoothed pace from cumulative distance, in two stages:
 *  1. Window: distance covered over the last [windowMs] (using the time each
 *     distance was MEASURED, not when the batch arrived; Health Services
 *     delivers in batches, and arrival times made the pace jump).
 *  2. Display: an exponential moving average over that, so one odd GPS
 *     reading nudges the number instead of swinging it.
 * Raw GPS pace jumps by a minute/km or more; this settles it while still
 * following a real change of effort within ~15–20 s.
 */
class PaceSmoother(
    private val windowMs: Long = 30_000,
    private val minWindowMs: Long = 8_000,
    private val emaTauMs: Double = 8_000.0,
) {

    private val samples = ArrayDeque<Pair<Long, Double>>() // (measured-at elapsedMs, cumulative meters)
    private var ema: Double? = null
    private var emaAtMs = 0L

    fun add(atMs: Long, distanceM: Double) {
        if (samples.isNotEmpty() && atMs <= samples.last().first) return
        samples.addLast(atMs to distanceM)
        while (samples.size > 2 && atMs - samples[1].first >= windowMs) samples.removeFirst()
    }

    /** Window pace in seconds per km, or null when standing still / not enough data. */
    fun paceSecPerKm(nowMs: Long): Double? {
        if (samples.size < 2) return null
        val (t0, d0) = samples.first()
        val (t1, d1) = samples.last()
        if (nowMs - t1 > 10_000) return null           // distance has stopped updating: stopped
        val dt = t1 - t0
        val dd = d1 - d0
        if (dt < minWindowMs || dd < 3.0) return null  // too little to judge, or standing
        val pace = (dt / 1000.0) / (dd / 1000.0)
        return pace.takeIf { it in 90.0..1800.0 }      // 1:30–30:00 /km; outside is noise
    }

    /** Pace to show and alert on: the window pace, further smoothed over time. */
    fun displayPace(nowMs: Long): Double? {
        val raw = paceSecPerKm(nowMs)
        if (raw == null) {
            ema = null
            return null
        }
        val prev = ema
        ema = if (prev == null) raw else {
            val alpha = 1 - exp(-(nowMs - emaAtMs).coerceAtLeast(0) / emaTauMs)
            prev + alpha * (raw - prev)
        }
        emaAtMs = nowMs
        return ema
    }

    fun reset() {
        samples.clear()
        ema = null
    }
}
