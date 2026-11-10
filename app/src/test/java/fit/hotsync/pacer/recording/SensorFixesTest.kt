package fit.hotsync.pacer.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackResolverTest {

    private fun point(sec: Int, hr: Int? = 150) = TrackPoint(
        epochMs = 1_000_000L + sec * 1000L, lat = null, lon = null, altitudeM = null,
        distanceM = sec * 3.0, heartRate = hr, cadenceSpm = 166, segment = 0,
    )

    @Test
    fun batchedFixesFillEverySecond() {
        // Live recording only had positions at the end of each 30 s batch (the bug)
        val points = (0 until 90).map { point(it) }
        // ...but every fix was delivered, one per second, in the batches
        val fixes = (0 until 90).map { LocationSample(1_000_000L + it * 1000L + 120, 45.0 + it * 1e-5, -122.0, 40.0) }
        val out = TrackResolver.resolve(points, fixes, emptyList())
        assertEquals(90, out.count { it.lat != null })
        assertEquals(45.0 + 42 * 1e-5, out[42].lat!!, 1e-9)
        assertEquals(40.0, out[42].altitudeM!!, 0.0)
    }

    @Test
    fun noFixNearbyStaysEmpty() {
        val points = (0 until 20).map { point(it) }
        val fixes = listOf(LocationSample(1_000_000L, 45.0, -122.0, null))  // only at second 0
        val out = TrackResolver.resolve(points, fixes, emptyList())
        assertEquals(45.0, out[0].lat!!, 0.0)
        assertEquals(45.0, out[3].lat!!, 0.0)       // within 3 s
        assertNull(out[4].lat)                       // too far from any fix: no invented position
    }

    @Test
    fun heartRateUsesMeasuredSamplesNotHeldValues() {
        val points = (0 until 10).map { point(it, hr = 170) }   // stale value held during a batch
        val hrs = (0 until 10).map { HrSample(1_000_000L + it * 1000L, 150 + it) }
        val out = TrackResolver.resolve(points, emptyList(), hrs)
        assertEquals((150 until 160).toList(), out.map { it.heartRate })
    }

    @Test
    fun nothingToResolveReturnsInput() {
        val points = listOf(point(0))
        assertTrue(TrackResolver.resolve(points, emptyList(), emptyList()) === points)
    }
}

class CadenceLockDetectorTest {

    @Test
    fun flagsHrSittingOnCadence() {
        val d = CadenceLockDetector()
        repeat(29) { assertFalse(d.update(167, 167)) }   // needs half a window of evidence
        repeat(60) { d.update(166 + it % 3, 167) }        // 166-168 at 167 spm
        assertTrue(d.locked)
    }

    @Test
    fun normalTempoHrIsNotFlagged() {
        val d = CadenceLockDetector()
        repeat(300) { d.update(155 + it / 60, 167) }       // HR 10+ below cadence, drifting up
        assertFalse(d.locked)
    }

    @Test
    fun walkingCannotLock() {
        val d = CadenceLockDetector()
        repeat(120) { d.update(115, 115) }                 // HR equal to a walking cadence
        assertFalse(d.locked)
    }

    @Test
    fun hysteresisClearsOnlyWhenClearlyUnlocked() {
        val d = CadenceLockDetector()
        repeat(60) { d.update(167, 167) }
        assertTrue(d.locked)
        repeat(20) { d.update(150, 167) }                  // a few clean seconds: still suspect
        assertTrue(d.locked)
        repeat(40) { d.update(150, 167) }                  // a clean minute: cleared
        assertFalse(d.locked)
    }
}
