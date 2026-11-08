package fit.hotsync.pacer.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal client for the hotsync.fit watch API (no extra libraries needed). */
class HotSyncApi(private val baseUrl: String = "https://hotsync.fit") {

    class HttpError(val code: Int, message: String) : IOException(message)

    data class PairStart(val deviceId: String, val code: String, val secret: String, val pollSeconds: Int)

    suspend fun pairStart(name: String): PairStart {
        val o = JSONObject(request("POST", "/watch/pair/start", JSONObject().put("name", name)))
        return PairStart(
            deviceId = o.getString("device_id"),
            code = o.getString("pair_code"),
            secret = o.getString("pair_secret"),
            pollSeconds = o.optInt("poll_interval_seconds", 3),
        )
    }

    /** Returns the watch token once the user has confirmed the code, or null while still pending. */
    suspend fun pairPoll(deviceId: String, secret: String): String? {
        val o = JSONObject(
            request("POST", "/watch/pair/poll", JSONObject().put("device_id", deviceId).put("pair_secret", secret))
        )
        return if (o.optString("status") == "paired") o.getString("token") else null
    }

    /** Raw JSON, so it can be cached as-is and parsed with [SyncParser]. */
    suspend fun sync(token: String): String = request("GET", "/watch/sync", token = token)

    private suspend fun request(method: String, path: String, body: JSONObject? = null, token: String? = null): String =
        withContext(Dispatchers.IO) {
            val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("Accept", "application/json")
                token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
                    throw HttpError(code, detail?.takeIf { it.isNotBlank() } ?: "HTTP $code")
                }
                text
            } finally {
                conn.disconnect()
            }
        }
}
