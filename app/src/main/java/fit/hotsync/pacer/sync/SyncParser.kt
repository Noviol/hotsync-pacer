package fit.hotsync.pacer.sync

import fit.hotsync.pacer.workout.Duration
import fit.hotsync.pacer.workout.Step
import fit.hotsync.pacer.workout.Target
import fit.hotsync.pacer.workout.TargetMode
import fit.hotsync.pacer.workout.Workout
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Alert defaults from the website's Workouts page. */
data class AlertDefaults(
    val haptic: Boolean = true,
    val sound: Boolean = false,
    val visual: Boolean = true,
    val delaySeconds: Int = 10,
)

/** One calendar event in the next 7 days, with its linked workout (if any). */
data class ScheduleEntry(
    val date: LocalDate,
    val eventId: String?,
    val title: String,
    val block: String,
    val workoutId: String?,
)

/** Everything from GET /watch/sync, kept on the watch so it works offline. */
data class SyncData(
    val alerts: AlertDefaults,
    val workouts: List<Workout>,
    val schedule: List<ScheduleEntry>,
) {
    private val byId = workouts.associateBy { it.id }
    fun workout(id: String?): Workout? = id?.let { byId[it] }

    /** Calendar events on [date] that have a workout linked, in calendar order. */
    fun linkedOn(date: LocalDate): List<Pair<ScheduleEntry, Workout>> =
        schedule.filter { it.date == date }.mapNotNull { e -> workout(e.workoutId)?.let { e to it } }
}

/** Parses the /watch/sync JSON. A workout the watch can't understand is skipped, not fatal. */
object SyncParser {

    fun parse(json: String): SyncData {
        val root = JSONObject(json)
        val p = root.getJSONObject("profile")
        val alerts = AlertDefaults(
            haptic = p.optBoolean("alert_haptic", true),
            sound = p.optBoolean("alert_sound", false),
            visual = p.optBoolean("alert_visual", true),
            delaySeconds = p.optInt("alert_delay_seconds", 10),
        )
        val workouts = root.getJSONArray("workouts").objects().mapNotNull { runCatching { workout(it) }.getOrNull() }
        val schedule = root.getJSONArray("schedule").objects().mapNotNull { e ->
            runCatching {
                ScheduleEntry(
                    date = LocalDate.parse(e.getString("date")),
                    eventId = e.optStringOrNull("event_id"),
                    title = e.optString("title", "Workout"),
                    block = e.optString("block", "ALL_DAY"),
                    workoutId = e.optStringOrNull("workout_id"),
                )
            }.getOrNull()
        }
        return SyncData(alerts, workouts, schedule)
    }

    private fun workout(o: JSONObject): Workout {
        val mode = if (o.getString("target_mode") == "pace") TargetMode.PACE else TargetMode.HR
        return Workout(
            id = o.getString("id"),
            name = o.getString("name"),
            mode = mode,
            steps = o.getJSONArray("steps").objects().map { step(it) },
        )
    }

    private fun step(o: JSONObject): Step =
        if (o.getString("kind") == "repeat") {
            Step.Repeat(o.getInt("count"), o.getJSONArray("steps").objects().map { segment(it) })
        } else segment(o)

    private fun segment(o: JSONObject): Step.Segment {
        val duration = when (val kind = o.getString("kind")) {
            "time" -> Duration.Time(o.getInt("seconds"))
            "distance" -> Duration.Distance(o.getDouble("meters"))
            "open" -> Duration.Open
            else -> throw IllegalArgumentException("Unknown step kind $kind")
        }
        val t = o.optJSONObject("target")
        val target = when {
            t == null -> null
            t.has("low_bpm") -> Target.Hr(t.getString("zone_name"), t.getInt("low_bpm"), t.getInt("high_bpm"))
            t.has("slow_sec_per_km") ->
                Target.Pace(t.getString("zone_name"), t.getInt("slow_sec_per_km"), t.getInt("fast_sec_per_km"))
            else -> null
        }
        return Step.Segment(o.getString("label"), duration, target)
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)
}
