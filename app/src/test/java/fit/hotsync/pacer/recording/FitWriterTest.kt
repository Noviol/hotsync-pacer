package fit.hotsync.pacer.recording

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FitWriterTest {

    private fun recording(): Pair<RunRecording, List<LapInfo>> {
        val start = 1_762_617_531_000L // 2025-11-08T16:58:51Z
        val rec = RunRecording(start)
        for (s in 0 until 120) {
            rec.add(
                TrackPoint(
                    epochMs = start + s * 1000L,
                    lat = 45.6442652 + s * 1e-5, lon = -122.6750479,
                    altitudeM = 46.2 + s * 0.1,
                    distanceM = s * 3.2,
                    heartRate = 140 + s % 10,
                    cadenceSpm = 172,
                    segment = if (s < 60) 0 else 1,
                    groundContactMs = 245.0, verticalOscillationCm = 8.4,
                    verticalRatioPct = 7.6, strideLengthM = 1.12,
                )
            )
        }
        return rec to listOf(LapInfo(0, "Warm-up", 60_000), LapInfo(1, "Fast 1/4", null))
    }

    @Test
    fun headerSizeAndCrc() {
        val (rec, laps) = recording()
        val fit = FitWriter.write(rec, laps, includeHeartRate = false)
        assertEquals(14, fit[0].toInt())
        assertEquals(".FIT", String(fit, 8, 4))
        val dataSize = (fit[4].toInt() and 0xFF) or ((fit[5].toInt() and 0xFF) shl 8) or
            ((fit[6].toInt() and 0xFF) shl 16) or ((fit[7].toInt() and 0xFF) shl 24)
        assertEquals(fit.size, 14 + dataSize + 2)
        // A FIT file's CRC over everything including the trailing CRC is 0
        assertEquals(0, FitWriter.crc(fit, 0, fit.size, 0))
        assertEquals(0, FitWriter.crc(fit, 0, 14, 0))

        // Written for the external decoder check (python fitdecode)
        val dir = File("build/test-fit").apply { mkdirs() }
        File(dir, "no_hr.fit").writeBytes(fit)
        File(dir, "with_hr.fit").writeBytes(FitWriter.write(rec, laps, includeHeartRate = true))
    }

    @Test
    fun heartRateLeftOutWhenAsked() {
        val (rec, laps) = recording()
        val without = FitWriter.write(rec, laps, includeHeartRate = false)
        val with = FitWriter.write(rec, laps, includeHeartRate = true)
        assertTrue(with.size > without.size + rec.points.size) // 1 byte per record + lap/session HR
    }

    @Test
    fun fullDataJsonHasHrAndDynamics() {
        val (rec, laps) = recording()
        val o = JSONObject(RunDataJson.write(rec, laps))
        val st = o.getJSONObject("streams")
        assertEquals(120, st.getJSONArray("time").length())
        assertEquals(140, st.getJSONArray("heartrate").getInt(0))
        assertEquals(86, st.getJSONArray("cadence").getInt(0))      // strides/min
        assertEquals(172, st.getJSONArray("steps_per_minute").getInt(0))
        assertEquals(245.0, st.getJSONArray("ground_contact_ms").getDouble(5), 1e-9)
        assertEquals(1.12, st.getJSONArray("stride_length_m").getDouble(5), 1e-9)
        assertEquals(45.6442652, st.getJSONArray("latlng").getJSONArray(0).getDouble(0), 1e-9)
        assertEquals(2, o.getJSONArray("laps").length())
    }
}
