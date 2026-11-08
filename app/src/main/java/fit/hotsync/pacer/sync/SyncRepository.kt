package fit.hotsync.pacer.sync

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the watch token and the last sync, persisted in SharedPreferences so
 * the watch works offline (e.g. out on a run with no connection).
 */
object SyncRepository {

    data class State(
        val paired: Boolean = false,
        val data: SyncData? = null,
        val lastSyncedAt: Long? = null,   // System.currentTimeMillis()
        val syncing: Boolean = false,
        val error: String? = null,
    )

    private const val PREFS = "hotsync_sync"
    private const val KEY_TOKEN = "token"
    private const val KEY_JSON = "sync_json"
    private const val KEY_SYNCED_AT = "synced_at"
    private const val AUTO_SYNC_MIN_INTERVAL_MS = 60_000L

    val api = HotSyncApi()

    private lateinit var prefs: SharedPreferences
    private val mutex = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cached = prefs.getString(KEY_JSON, null)?.let { runCatching { SyncParser.parse(it) }.getOrNull() }
        _state.value = State(
            paired = prefs.getString(KEY_TOKEN, null) != null,
            data = cached,
            lastSyncedAt = prefs.getLong(KEY_SYNCED_AT, 0L).takeIf { it > 0 },
        )
    }

    fun token(): String? = prefs.getString(KEY_TOKEN, null)

    /** Called when pairing completes. */
    suspend fun savePairing(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
        _state.value = _state.value.copy(paired = true, error = null)
        sync(force = true)
    }

    fun unpair() {
        prefs.edit().clear().apply()
        _state.value = State()
    }

    /** Fetch fresh data. Without [force], skips if a sync happened in the last minute. */
    suspend fun sync(force: Boolean = false) = mutex.withLock {
        val token = prefs.getString(KEY_TOKEN, null) ?: return@withLock
        val last = _state.value.lastSyncedAt
        if (!force && last != null && System.currentTimeMillis() - last < AUTO_SYNC_MIN_INTERVAL_MS) return@withLock

        _state.value = _state.value.copy(syncing = true, error = null)
        try {
            val json = api.sync(token)
            val data = SyncParser.parse(json)
            val now = System.currentTimeMillis()
            prefs.edit().putString(KEY_JSON, json).putLong(KEY_SYNCED_AT, now).apply()
            _state.value = _state.value.copy(data = data, lastSyncedAt = now, syncing = false)
        } catch (e: HotSyncApi.HttpError) {
            if (e.code == 401) {
                // Unpaired on the website: forget the token, keep nothing stale
                unpair()
                _state.value = _state.value.copy(error = "Watch was unpaired. Pair again.")
            } else {
                _state.value = _state.value.copy(syncing = false, error = e.message)
            }
        } catch (e: Exception) {
            // Offline etc.: keep the cached data
            _state.value = _state.value.copy(syncing = false, error = "Couldn't reach HotSync")
        }
    }
}
