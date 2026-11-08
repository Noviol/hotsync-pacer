package fit.hotsync.pacer.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class PaceSmootherTest {

    @Test
    fun steadyPace() {
        val p = PaceSmoother()
        // 3.0 m/s = 5:33 /km
        for (s in 0..30) p.add(s * 1000L, s * 3.0)
        assertEquals(333.3, p.paceSecPerKm(30_000)!!, 0.5)
    }

    @Test
    fun smoothsGpsJitter() {
        val p = PaceSmoother()
        // 3 m/s on average, but alternating 1 m and 5 m per second
        var d = 0.0
        for (s in 0..30) { p.add(s * 1000L, d); d += if (s % 2 == 0) 1.0 else 5.0 }
        assertEquals(333.3, p.paceSecPerKm(30_000)!!, 20.0)
    }

    @Test
    fun windowFollowsChangeOfPace() {
        val p = PaceSmoother(windowMs = 20_000)
        var d = 0.0
        for (s in 0..60) { p.add(s * 1000L, d); d += if (s < 30) 2.5 else 4.0 }
        assertEquals(250.0, p.paceSecPerKm(60_000)!!, 1.0) // only the fast part is in the window
    }

    @Test
    fun noPaceWhenStoppedOrTooEarly() {
        val p = PaceSmoother()
        p.add(0, 0.0); p.add(3_000, 9.0)
        assertNull(p.paceSecPerKm(3_000)) // under the minimum window
        val q = PaceSmoother()
        for (s in 0..20) q.add(s * 1000L, 100.0) // standing still
        assertNull(q.paceSecPerKm(20_000))
        val r = PaceSmoother()
        for (s in 0..20) r.add(s * 1000L, s * 3.0)
        assertNull(r.paceSecPerKm(31_000)) // distance stopped arriving
    }

    @Test
    fun displayPaceDampsASuddenJump() {
        val p = PaceSmoother()
        var d = 0.0
        var shown = 0.0
        for (s in 0..60) {
            p.add(s * 1000L, d)
            d += if (s == 45) 40.0 else 3.0 // one bad GPS reading: 40 m in a second
            p.displayPace(s * 1000L)?.let { shown = it }
        }
        val raw = p.paceSecPerKm(60_000)!!
        // The steady pace is 333 s/km; the spike pulls the raw window pace far lower,
        // the displayed pace moves only part of the way
        assertTrue(raw < 300)
        assertTrue(shown > raw + 5)
        assertTrue(shown < 334)
    }
}

class TcxWriterTest {

    private fun point(sec: Int, dist: Double, seg: Int, hr: Int? = 140, gps: Boolean = true) = TrackPoint(
        epochMs = 1_762_590_000_000L + sec * 1000L,
        lat = if (gps) 37.0 + sec * 1e-5 else null,
        lon = if (gps) -122.0 else null,
        altitudeM = 12.5,
        distanceM = dist,
        heartRate = hr,
        cadenceSpm = 170,
        segment = seg,
    )

    @Test
    fun segmentsBecomeLapsInValidTcx() {
        val rec = RunRecording(1_762_590_000_000L)
        for (s in 0 until 10) rec.add(point(s, s * 3.0, 0, hr = 130 + s))
        for (s in 10 until 15) rec.add(point(s, s * 3.0, 1, hr = null, gps = false))
        for (s in 15 until 18) rec.add(point(s, s * 3.0, 2)) // after the workout completed
        val laps = listOf(LapInfo(0, "Warm-up", 10_000), LapInfo(1, "Fast 1/4 & <go>", null))

        val xml = TcxWriter.write(rec, laps, "Pixel Watch 3")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream())

        val lapEls = doc.getElementsByTagName("Lap")
        assertEquals(3, lapEls.length)
        val lap0 = lapEls.item(0) as Element
        assertEquals("2025-11-08T08:20:00Z", lap0.getAttribute("StartTime"))
        assertEquals("10.0", text(lap0, "TotalTimeSeconds"))
        assertEquals("27.0", text(lap0, "DistanceMeters").let { it }) // 0 -> 27 m
        assertEquals("135", text(lap0, "Value")) // average HR 130..139 = 134.5, rounded
        assertEquals("Warm-up", text(lap0, "Notes"))

        val lap1 = lapEls.item(1) as Element
        assertEquals("15.0", text(lap1, "DistanceMeters")) // 27 -> 42 m
        assertEquals("5.0", text(lap1, "TotalTimeSeconds")) // no engine duration: point span
        assertEquals(0, lap1.getElementsByTagName("HeartRateBpm").length + lap1.getElementsByTagName("AverageHeartRateBpm").length)
        assertEquals(0, lap1.getElementsByTagName("Position").length)
        assertEquals("Fast 1/4 & <go>", text(lap1, "Notes")) // escaped and parsed back

        assertEquals(18, doc.getElementsByTagName("Trackpoint").length)
        assertEquals("85", doc.getElementsByTagNameNS("*", "RunCadence").item(0).textContent) // 170 spm -> 85
        assertEquals("Pixel Watch 3", text(doc.documentElement, "Name"))
        assertTrue(xml.contains("<Activity Sport=\"Running\">"))
    }

    @Test
    fun emptyRecordingStillValid() {
        val xml = TcxWriter.write(RunRecording(0), emptyList(), "Watch")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals(1, doc.getElementsByTagName("Lap").length)
    }

    private fun text(el: Element, tag: String) = el.getElementsByTagName(tag).item(0).textContent
}
