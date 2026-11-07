package fit.hotsync.pacer.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutEngineTest {

    private val hr = Target.Hr("Z1", 126, 139)

    private fun workout(vararg steps: Step) = Workout("w", "Test", TargetMode.HR, steps.toList())

    @Test
    fun expandsRepeatsWithSetCounts() {
        val segs = SampleWorkouts.rp12.expand()
        assertEquals(1 + 4 * 2 + 1, segs.size)
        assertEquals(listOf("Warm-up", "Fast", "Recover", "Fast"), segs.take(4).map { it.label })
        assertEquals("1/4", segs[1].setText)
        assertEquals("1/4", segs[2].setText)
        assertEquals("2/4", segs[3].setText)
        assertEquals("4/4", segs[8].setText)
        assertNull(segs[0].setText)
        assertEquals((0 until segs.size).toList(), segs.map { it.index })
    }

    @Test
    fun timeSegmentEndsExactlyOnBoundaryDespiteLateTick() {
        val e = WorkoutEngine(workout(
            Step.Segment("A", Duration.Time(60), hr),
            Step.Segment("B", Duration.Time(60), hr),
        ))
        e.start(nowMs = 1_000)
        var s = e.update(31_000)
        assertEquals("A", s.segment?.label)
        assertEquals(30_000L, s.remainingMs)

        s = e.update(61_700) // tick arrives 700 ms late
        assertEquals("B", s.segment?.label)
        assertEquals(60_000L, e.completed[0].durationMs)
        assertEquals(700L, s.segmentElapsedMs) // B started on the boundary, not the tick

        s = e.update(121_000)
        assertTrue(s.isComplete)
        assertNull(s.segment)
        assertEquals(120_000L, s.totalActiveMs)
    }

    @Test
    fun distanceSegmentEndsOnDistance() {
        val e = WorkoutEngine(workout(
            Step.Segment("Fast", Duration.Distance(1000.0), hr),
            Step.Segment("Jog", Duration.Open, hr),
        ))
        e.start(0, distanceM = 50.0) // sensors may already report some distance
        var s = e.update(100_000, 800.0)
        assertEquals("Fast", s.segment?.label)
        assertEquals(250.0, s.remainingM!!, 1e-9)
        s = e.update(200_000, 1_070.0)
        assertEquals("Jog", s.segment?.label)
        assertEquals(1000.0, e.completed[0].distanceM, 1e-9)
        assertEquals(20.0, s.segmentDistanceM, 1e-9) // overshoot carried into the next segment
        assertEquals(1_020.0, s.totalDistanceM, 1e-9)
    }

    @Test
    fun openSegmentWaitsForLap() {
        val e = WorkoutEngine(workout(Step.Segment("Cool-down", Duration.Open, hr)))
        e.start(0)
        var s = e.update(10_000_000)
        assertFalse(s.isComplete)
        assertNull(s.remainingMs)
        assertEquals(10_000_000L, s.segmentElapsedMs)
        s = e.lap(10_000_500)
        assertTrue(s.isComplete)
        assertEquals(WorkoutEngine.EndReason.LAP, e.completed.single().endedBy)
    }

    @Test
    fun lapEndsTimeSegmentEarly() {
        val e = WorkoutEngine(workout(
            Step.Segment("A", Duration.Time(600), hr),
            Step.Segment("B", Duration.Time(60), hr),
        ))
        e.start(0)
        val s = e.lap(200_000, 500.0)
        assertEquals("B", s.segment?.label)
        assertEquals(200_000L, e.completed[0].durationMs)
        assertEquals(60_000L, s.remainingMs)
    }

    @Test
    fun pauseExcludesTimeFromSegments() {
        val e = WorkoutEngine(workout(Step.Segment("A", Duration.Time(60), hr), Step.Segment("B", Duration.Open)))
        e.start(0)
        e.update(30_000)
        e.pause(30_000)
        var s = e.update(500_000) // long pause
        assertEquals("A", s.segment?.label)
        assertTrue(s.isPaused)
        assertEquals(30_000L, s.totalActiveMs)
        e.resume(500_000)
        s = e.update(529_000)
        assertEquals("A", s.segment?.label)
        s = e.update(530_000)
        assertEquals("B", s.segment?.label)
        assertEquals(60_000L, e.completed[0].durationMs)
    }

    @Test
    fun nothingHappensBeforeStartOrAfterComplete() {
        val e = WorkoutEngine(workout(Step.Segment("A", Duration.Time(10))))
        assertEquals("A", e.update(99_000).segment?.label)
        assertEquals(0L, e.update(99_000).totalActiveMs)
        e.start(100_000)
        e.update(200_000)
        assertTrue(e.isComplete)
        e.lap(210_000)
        assertEquals(1, e.completed.size)
    }

    @Test
    fun distanceNeverGoesBackwards() {
        val e = WorkoutEngine(workout(Step.Segment("A", Duration.Distance(1000.0))))
        e.start(0)
        e.update(1, 600.0)
        val s = e.update(2, 550.0) // sensor glitch
        assertEquals(400.0, s.remainingM!!, 1e-9)
    }

    @Test
    fun oneLateTickCanCrossSeveralShortSegments() {
        val e = WorkoutEngine(workout(Step.Repeat(3, listOf(Step.Segment("S", Duration.Time(1))))))
        e.start(0)
        val s = e.update(5_000)
        assertTrue(s.isComplete)
        assertEquals(listOf(1000L, 1000L, 1000L), e.completed.map { it.durationMs })
    }
}

class TargetTrackingTest {

    private val hr = Target.Hr("Z3", 150, 160)
    private val pace = Target.Pace("Easy", slowSecPerKm = 420, fastSecPerKm = 390)

    @Test
    fun hrStatus() {
        assertEquals(TargetStatus.BELOW, TargetMath.status(hr, 149.0))
        assertEquals(TargetStatus.IN, TargetMath.status(hr, 150.0))
        assertEquals(TargetStatus.IN, TargetMath.status(hr, 160.0))
        assertEquals(TargetStatus.ABOVE, TargetMath.status(hr, 161.0))
        assertEquals(TargetStatus.UNKNOWN, TargetMath.status(hr, null))
        assertEquals(TargetStatus.UNKNOWN, TargetMath.status(null, 150.0))
    }

    @Test
    fun paceStatusTreatsSlowAsBelow() {
        assertEquals(TargetStatus.BELOW, TargetMath.status(pace, 430.0))
        assertEquals(TargetStatus.IN, TargetMath.status(pace, 400.0))
        assertEquals(TargetStatus.ABOVE, TargetMath.status(pace, 380.0))
    }

    @Test
    fun gaugeCentresTargetAndPutsFasterOnTheRight() {
        assertEquals(0.5, TargetMath.gaugePosition(hr, 155.0)!!, 1e-9)
        assertEquals(1.0 / 3, TargetMath.gaugePosition(hr, 150.0)!!, 1e-9)
        assertEquals(2.0 / 3, TargetMath.gaugePosition(hr, 160.0)!!, 1e-9)
        assertEquals(0.0, TargetMath.gaugePosition(hr, 100.0)!!, 1e-9)
        assertEquals(1.0, TargetMath.gaugePosition(hr, 200.0)!!, 1e-9)
        assertEquals(1.0 / 3, TargetMath.gaugePosition(pace, 420.0)!!, 1e-9)
        assertEquals(2.0 / 3, TargetMath.gaugePosition(pace, 390.0)!!, 1e-9)
        assertTrue(TargetMath.gaugePosition(pace, 370.0)!! > 2.0 / 3)
        assertNull(TargetMath.gaugePosition(pace, null))
    }
}

class AlertTrackerTest {

    @Test
    fun firesOnceAfterDelay() {
        val a = AlertTracker(delayMs = 10_000)
        assertEquals(AlertTracker.Update(null, false), a.update(TargetStatus.ABOVE, 0, 0))
        assertEquals(AlertTracker.Update(null, false), a.update(TargetStatus.ABOVE, 9_999, 0))
        assertEquals(AlertTracker.Update(TargetStatus.ABOVE, true), a.update(TargetStatus.ABOVE, 10_000, 0))
        assertEquals(AlertTracker.Update(TargetStatus.ABOVE, false), a.update(TargetStatus.ABOVE, 20_000, 0))
        assertEquals(AlertTracker.Update(null, false), a.update(TargetStatus.IN, 21_000, 0))
    }

    @Test
    fun briefExcursionDoesNotAlert() {
        val a = AlertTracker(10_000)
        a.update(TargetStatus.BELOW, 0, 0)
        a.update(TargetStatus.IN, 5_000, 0)
        assertEquals(null, a.update(TargetStatus.BELOW, 12_000, 0).active)
    }

    @Test
    fun newSegmentRestartsGracePeriod() {
        val a = AlertTracker(10_000)
        a.update(TargetStatus.BELOW, 0, 0)
        assertEquals(null, a.update(TargetStatus.BELOW, 11_000, 1).active)
        assertTrue(a.update(TargetStatus.BELOW, 21_000, 1).fire)
    }

    @Test
    fun switchingSidesRestartsClock() {
        val a = AlertTracker(10_000)
        a.update(TargetStatus.BELOW, 0, 0)
        a.update(TargetStatus.ABOVE, 8_000, 0)
        assertEquals(null, a.update(TargetStatus.ABOVE, 12_000, 0).active)
        assertTrue(a.update(TargetStatus.ABOVE, 18_000, 0).fire)
    }

    @Test
    fun zeroDelayAlertsImmediately() {
        assertTrue(AlertTracker(0).update(TargetStatus.ABOVE, 5, 0).fire)
    }

    @Test
    fun unknownReadingClearsAlert() {
        val a = AlertTracker(0)
        a.update(TargetStatus.ABOVE, 0, 0)
        assertEquals(AlertTracker.Update(null, false), a.update(TargetStatus.UNKNOWN, 1, 0))
    }
}
