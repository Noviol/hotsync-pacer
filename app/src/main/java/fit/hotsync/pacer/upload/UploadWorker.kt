package fit.hotsync.pacer.upload

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import fit.hotsync.pacer.sync.HotSyncApi
import fit.hotsync.pacer.sync.SyncRepository
import kotlinx.coroutines.delay

/**
 * Sends one queued run to hotsync.fit, which uploads it to Strava, then waits
 * for Strava to finish processing. Network problems retry with backoff; the
 * file is only deleted once Strava has the activity (or rejected it for good).
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_ID = "id"
        private const val TAG = "UploadWorker"
        private const val POLLS = 20
        private const val POLL_MS = 3_000L
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val file = UploadQueue.file(ctx, id)
        if (!file.exists()) return Result.success()
        val meta = UploadQueue.meta(ctx, id) ?: UploadQueue.Meta("Run", null, null)

        SyncRepository.init(ctx)
        val token = SyncRepository.token()
        if (token == null) {
            UploadQueue.update(id, meta.name, UploadQueue.State.FAILED, "Watch not paired. Pair, then reopen the app.")
            return Result.failure() // Kept on disk; resumePending() retries after pairing
        }

        UploadQueue.update(id, meta.name, UploadQueue.State.UPLOADING, "Uploading…")
        val api = SyncRepository.api
        return try {
            var result = api.uploadActivity(
                token = token,
                file = file.readBytes(),
                filename = "$id." + if (file.name.endsWith(".fit")) "fit" else "tcx.gz",
                data = UploadQueue.dataFile(ctx, id).takeIf { it.exists() }?.readBytes(),
                externalId = id,
                // For calendar runs let the server use the event title (same as the webhook will)
                name = if (meta.eventId != null) null else meta.name,
                eventId = meta.eventId,
                eventDate = meta.eventDate,
            )
            var polls = 0
            while (result.status == "processing" && result.uploadId != null && polls < POLLS) {
                delay(POLL_MS)
                result = api.uploadStatus(token, result.uploadId!!)
                polls++
            }
            when (result.status) {
                "ready", "duplicate" -> {
                    UploadQueue.update(id, meta.name, UploadQueue.State.DONE, "On Strava", result.activityId)
                    UploadQueue.remove(ctx, id)
                    Result.success()
                }
                "processing" -> {
                    // Strava accepted it and is still processing; nothing left for the watch to do
                    UploadQueue.update(id, meta.name, UploadQueue.State.DONE, "Sent to Strava (processing)")
                    UploadQueue.remove(ctx, id)
                    Result.success()
                }
                else -> {
                    UploadQueue.update(id, meta.name, UploadQueue.State.FAILED, result.error ?: "Strava rejected the file")
                    UploadQueue.remove(ctx, id)
                    Result.failure()
                }
            }
        } catch (e: HotSyncApi.HttpError) {
            Log.w(TAG, "upload failed: ${e.code} ${e.message}")
            if (e.code >= 500 || e.code == 429) {
                UploadQueue.update(id, meta.name, UploadQueue.State.WAITING, "Server busy, will retry")
                Result.retry()
            } else if (e.code == 401) {
                UploadQueue.update(id, meta.name, UploadQueue.State.FAILED, "Watch not paired. Pair, then reopen the app.")
                Result.failure() // Kept on disk; retried after pairing
            } else {
                UploadQueue.update(id, meta.name, UploadQueue.State.FAILED, e.message)
                UploadQueue.remove(ctx, id) // Rejected (bad file etc.); retrying won't help
                Result.failure()
            }
        } catch (e: Exception) {
            Log.w(TAG, "upload failed", e)
            UploadQueue.update(id, meta.name, UploadQueue.State.WAITING, "No connection, will retry")
            Result.retry()
        }
    }
}
