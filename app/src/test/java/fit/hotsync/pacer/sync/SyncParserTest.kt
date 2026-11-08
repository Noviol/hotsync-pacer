package fit.hotsync.pacer.sync

import fit.hotsync.pacer.workout.Duration
import fit.hotsync.pacer.workout.Step
import fit.hotsync.pacer.workout.Target
import fit.hotsync.pacer.workout.TargetMode
import fit.hotsync.pacer.workout.expand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** sync_sample.json is a real /watch/sync response generated from the HotSync backend. */
class SyncParserTest {

    private val json = javaClass.classLoader!!.getResource("sync_sample.json")!!.readText()
    private val data = SyncParser.parse(json)

    @Test
    fun alertDefaults() {
        assertEquals(AlertDefaults(haptic = true, sound = true, visual = true, delaySeconds = 15), data.alerts)
    }

    @Test
    fun workoutsWithResolvedTargets() {
        assertEquals(listOf("Easy 45", "Free strides", "RP12 (Progression Run)"), data.workouts.map { it.name })
        val rp12 = data.workouts[2]
        assertEquals(TargetMode.HR, rp12.mode)
        val warmup = rp12.steps[0] as Step.Segment
        assertEquals(Duration.Time(600), warmup.duration)
        assertEquals(Target.Hr("Zone 1", 126, 139), warmup.target)
        val repeat = rp12.steps[1] as Step.Repeat
        assertEquals(4, repeat.count)
        assertEquals(Duration.Distance(1000.0), repeat.steps[0].duration)
        assertEquals(Duration.Open, (rp12.steps[2] as Step.Segment).duration)
        assertEquals(10, rp12.expand().size)

        val easy = data.workouts[0]
        assertEquals(TargetMode.PACE, easy.mode)
        assertEquals(Target.Pace("Easy", 420, 390), (easy.steps[0] as Step.Segment).target)

        val strides = data.workouts[1].steps[0] as Step.Segment
        assertNull(strides.target)
        assertEquals(Duration.Distance(100.5), strides.duration)
    }

    @Test
    fun scheduleLinksWorkoutsByDay() {
        val sun = LocalDate.parse("2026-11-08")
        val today = data.linkedOn(sun)
        assertEquals(1, today.size) // "Strength" has no workout
        assertEquals("RP12 Progression Run", today[0].first.title)
        assertEquals("RP12 (Progression Run)", today[0].second.name)
        assertEquals("e1", today[0].first.eventId)
        assertEquals("AM", today[0].first.block)
        assertEquals("Easy 45", data.linkedOn(sun.plusDays(1)).single().second.name)
        assertTrue(data.linkedOn(sun.plusDays(2)).isEmpty())
        assertNull(data.schedule.first { it.title == "Strength" }.workoutId)
    }

    @Test
    fun unknownStepKindSkipsOnlyThatWorkout() {
        val root = org.json.JSONObject(json)
        root.getJSONArray("workouts").getJSONObject(0).getJSONArray("steps").getJSONObject(0).put("kind", "swim")
        val parsed = SyncParser.parse(root.toString())
        assertEquals(listOf("Free strides", "RP12 (Progression Run)"), parsed.workouts.map { it.name })
        // The schedule entry still exists; it just has no runnable workout
        assertTrue(parsed.linkedOn(LocalDate.parse("2026-11-09")).isEmpty())
    }
}
