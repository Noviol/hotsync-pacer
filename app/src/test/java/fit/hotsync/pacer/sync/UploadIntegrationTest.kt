package fit.hotsync.pacer.sync

import fit.hotsync.pacer.recording.LapInfo
import fit.hotsync.pacer.recording.RunRecording
import fit.hotsync.pacer.recording.TcxWriter
import fit.hotsync.pacer.recording.TrackPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Uploads a real TCX through HotSyncApi to a running HotSync backend.
 * Skipped unless HOTSYNC_TEST_URL and HOTSYNC_TEST_TOKEN are set (point it at a
 * local server with Strava mocked, never production).
 */
class UploadIntegrationTest {

    @Test
    fun multipartUploadReachesServer() = runBlocking {
        val url = System.getenv("HOTSYNC_TEST_URL")
        val token = System.getenv("HOTSYNC_TEST_TOKEN")
        assumeTrue(url != null && token != null)

        val rec = RunRecording(1_762_590_000_000L)
        for (s in 0 until 30) rec.add(
            TrackPoint(1_762_590_000_000L + s * 1000L, 37.0, -122.0, 10.0, s * 3.0, 140, 170, if (s < 20) 0 else 1)
        )
        val tcx = TcxWriter.write(rec, listOf(LapInfo(0, "Warm-up", 20_000), LapInfo(1, "Fast", null)), "Test Watch")
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(tcx.toByteArray()) } }.toByteArray()

        val api = HotSyncApi(url!!)
        val result = api.uploadActivity(token!!, gz, "hotsync-test.tcx.gz", "hotsync-test", "Test run", null, null)
        assertEquals("processing", result.status)
        assertEquals(777L, result.uploadId)
        val status = api.uploadStatus(token, result.uploadId!!)
        assertEquals("ready", status.status)
        assertEquals(4242L, status.activityId)
    }
}
