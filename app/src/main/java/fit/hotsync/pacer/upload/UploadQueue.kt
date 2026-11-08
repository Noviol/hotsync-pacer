package fit.hotsync.pacer.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * Finished runs waiting to go to hotsync.fit (and on to Strava). Each run is saved as
 *   files/uploads/<id>.fit           FIT file for Strava (no heart rate)
 *   files/uploads/<id>.data.json.gz  full data kept by HotSync (HR, cadence, dynamics)
 *   files/uploads/<id>.json          metadata
 * so nothing is lost if the watch is offline or the app restarts; [UploadWorker]
 * sends it when there's a connection. (Older builds queued <id>.tcx.gz; still sent.)
 */
object UploadQueue {

    enum class State { WAITING, UPLOADING, DONE, FAILED }

    data class Item(
        val id: String,
        val name: String,
        val state: State,
        val message: String? = null,
        val activityId: Long? = null,
    )

    data class Meta(val name: String, val eventId: String?, val eventDate: String?)

    private val _items = MutableStateFlow<Map<String, Item>>(emptyMap())
    val items: StateFlow<Map<String, Item>> = _items.asStateFlow()

    fun dir(context: Context) = File(context.filesDir, "uploads").apply { mkdirs() }
    /** The file for Strava: FIT, or a legacy TCX queued by an older build. */
    fun file(context: Context, id: String): File {
        val fit = File(dir(context), "$id.fit")
        return if (fit.exists()) fit else File(dir(context), "$id.tcx.gz").takeIf { it.exists() } ?: fit
    }
    fun dataFile(context: Context, id: String) = File(dir(context), "$id.data.json.gz")
    private fun metaFile(context: Context, id: String) = File(dir(context), "$id.json")

    /** Save a finished run and schedule its upload. */
    fun enqueue(context: Context, id: String, fit: ByteArray, dataJson: String, meta: Meta) {
        File(dir(context), "$id.fit").writeBytes(fit)
        GZIPOutputStream(dataFile(context, id).outputStream()).use { it.write(dataJson.toByteArray()) }
        metaFile(context, id).writeText(
            JSONObject().put("name", meta.name).put("event_id", meta.eventId).put("event_date", meta.eventDate).toString()
        )
        update(id, meta.name, State.WAITING, "Waiting for connection")
        schedule(context, id)
    }

    /** On app start: re-schedule anything left over (e.g. after a reboot). */
    fun resumePending(context: Context) {
        dir(context).listFiles { f -> f.name.endsWith(".json") }?.forEach { f ->
            val id = f.name.removeSuffix(".json")
            if (!file(context, id).exists()) return@forEach
            val name = runCatching { JSONObject(f.readText()).optString("name") }.getOrDefault("Run")
            if (_items.value[id] == null) update(id, name, State.WAITING, "Waiting for connection")
            schedule(context, id)
        }
    }

    fun meta(context: Context, id: String): Meta? = runCatching {
        val o = JSONObject(metaFile(context, id).readText())
        Meta(
            name = o.optString("name", "Run"),
            eventId = if (o.isNull("event_id")) null else o.optString("event_id"),
            eventDate = if (o.isNull("event_date")) null else o.optString("event_date"),
        )
    }.getOrNull()

    /** Uploaded (or permanently rejected): remove the local copy. */
    fun remove(context: Context, id: String) {
        file(context, id).delete()
        dataFile(context, id).delete()
        metaFile(context, id).delete()
    }

    fun update(id: String, name: String?, state: State, message: String? = null, activityId: Long? = null) {
        val current = _items.value[id]
        _items.value = _items.value + (id to Item(id, name ?: current?.name ?: "Run", state, message, activityId))
    }

    private fun schedule(context: Context, id: String) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("upload-$id", ExistingWorkPolicy.KEEP, request)
    }
}
