package fit.hotsync.pacer.workout

import kotlin.math.abs
import kotlin.math.roundToInt

/** Display formatting shared by the watch screens. Distances in km for now (units setting comes later). */
object Format {

    /** 75_000 ms -> "1:15", 3_725_000 -> "1:02:05". Negative values get a leading "-". */
    fun duration(ms: Long): String {
        val sign = if (ms < 0) "-" else ""
        val total = abs(ms) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%s%d:%02d:%02d".format(sign, h, m, s) else "%s%d:%02d".format(sign, m, s)
    }

    /** Remaining-time display rounds UP so the segment never shows 0:00 before it ends. */
    fun countdown(ms: Long): String = duration(((ms.coerceAtLeast(0) + 999) / 1000) * 1000)

    fun seconds(sec: Int): String = duration(sec * 1000L)

    /** 450.0 -> "450 m", 1234.0 -> "1.23 km" */
    fun distance(meters: Double): String =
        if (meters < 1000) "${meters.coerceAtLeast(0.0).roundToInt()} m" else "%.2f km".format(meters / 1000)

    /** Seconds per km -> "6:45" */
    fun pace(secPerKm: Double?): String =
        if (secPerKm == null || secPerKm <= 0 || secPerKm > 3600) "--" else seconds(secPerKm.roundToInt())

    fun durationOf(d: Duration): String = when (d) {
        is Duration.Time -> seconds(d.seconds)
        is Duration.Distance -> distance(d.meters)
        Duration.Open -> "Open"
    }

    private fun zone(target: Target?): String = target?.zoneName?.let { " $it" } ?: ""

    /** One-line summary, same style as the website: "10:00 Zone 1 · 4×(1.00 km Zone 3, 2:00 Zone 1) · Open Zone 1" */
    fun summary(workout: Workout): String = workout.steps.joinToString(" · ") { step ->
        when (step) {
            is Step.Segment -> durationOf(step.duration) + zone(step.target)
            is Step.Repeat -> "${step.count}×(" +
                step.steps.joinToString(", ") { durationOf(it.duration) + zone(it.target) } + ")"
        }
    }

    /** "45:00", "8.00 km", or "40:00 + 2.00 km + open" for mixed workouts */
    fun total(workout: Workout): String {
        var ms = 0L
        var meters = 0.0
        var open = 0
        for (seg in workout.expand()) when (val d = seg.duration) {
            is Duration.Time -> ms += d.seconds * 1000L
            is Duration.Distance -> meters += d.meters
            Duration.Open -> open++
        }
        return listOfNotNull(
            if (ms > 0) duration(ms) else null,
            if (meters > 0) distance(meters) else null,
            if (open > 0) "open" else null,
        ).joinToString(" + ")
    }
}
