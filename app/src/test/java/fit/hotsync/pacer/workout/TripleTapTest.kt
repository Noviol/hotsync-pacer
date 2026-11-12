package fit.hotsync.pacer.workout

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripleTapTest {

    @Test
    fun threeQuickTapsTrigger() {
        val t = TripleTapDetector()
        assertFalse(t.tap(1000))
        assertFalse(t.tap(1200))
        assertTrue(t.tap(1400))
    }

    @Test
    fun slowTapsDoNot() {
        val t = TripleTapDetector()
        t.tap(1000); t.tap(1500)                  // 500 ms gap: too slow, so counting restarts at 1500
        assertFalse(t.tap(1800))                   // 1500, 1800: two taps
        assertTrue(t.tap(2000))                    // 1500, 1800, 2000: three quick taps
    }

    @Test
    fun spreadOutTapsDoNot() {
        val t = TripleTapDetector(windowMs = 800, maxGapMs = 400)
        t.tap(0); t.tap(390)
        assertFalse(t.tap(780 + 100))              // gaps ok-ish but total 880 > 800
    }

    @Test
    fun longPressResets() {
        val t = TripleTapDetector()
        t.tap(1000); t.tap(1150)
        t.reset()                                  // sleeve resting on the screen
        assertFalse(t.tap(1300))
    }

    @Test
    fun afterTriggerNeedsThreeNewTaps() {
        val t = TripleTapDetector()
        t.tap(0); t.tap(100); assertTrue(t.tap(200))
        assertFalse(t.tap(300))
        assertFalse(t.tap(400))
        assertTrue(t.tap(500))
    }
}
