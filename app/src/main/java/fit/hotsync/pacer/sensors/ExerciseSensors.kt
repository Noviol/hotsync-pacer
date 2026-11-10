package fit.hotsync.pacer.sensors

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.concurrent.futures.await
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.LocationAvailability
import androidx.health.services.client.data.WarmUpConfig
import fit.hotsync.pacer.WorkoutSession
import fit.hotsync.pacer.recording.HrSample
import fit.hotsync.pacer.recording.LocationSample
import fit.hotsync.pacer.recording.PaceSmoother
import fit.hotsync.pacer.recording.SampleHistory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Health Services wrapper for a run: heart rate, GPS, distance, elevation and
 * cadence. Health Services records the exercise in its own process (so it keeps
 * going if our UI is in the background); we receive batched updates and copy the
 * latest values into [WorkoutSession] for the screen, alerts and recording.
 */
object ExerciseSensors {

    private const val TAG = "ExerciseSensors"

    enum class Lock { OFF, SEARCHING, READY, UNAVAILABLE }

    data class Status(
        val gps: Lock = Lock.OFF,
        val hr: Lock = Lock.OFF,
        val heartRate: Int? = null,
        val error: String? = null,
    )

    data class Location(val lat: Double, val lon: Double, val altitudeM: Double?, val atElapsedMs: Long)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    // Latest readings (read by the recorder once a second)
    @Volatile var location: Location? = null
        private set
    @Volatile var elevationM: Double? = null
        private set
    @Volatile var cadenceSpm: Int? = null
        private set

    /**
     * Running dynamics (Garmin-style form metrics). Health Services 1.0 has no
     * constants for these, so they're matched by name from the watch's supported
     * types. Units as reported by Health Services.
     */
    data class Dynamics(
        val groundContactMs: Double? = null,   // Ground contact time
        val verticalOscillationCm: Double? = null,
        val verticalRatioPct: Double? = null,
        val strideLengthM: Double? = null,
    )
    @Volatile var dynamics = Dynamics()
        private set

    private val DYNAMICS_NAMES = setOf("Ground Contact Time", "Vertical Oscillation", "Vertical Ratio", "Stride Length")
    private var loggedDynamics = false

    /** Every HR and GPS sample with its measurement time (used when the run is saved). */
    val history = SampleHistory()

    private lateinit var client: ExerciseClient
    private var dataTypes: Set<DataType<*, *>> = emptySet()
    private var gpsEnabled = false
    private val pace = PaceSmoother()
    @Volatile private var lastHrAtMs = 0L

    fun init(context: Context) {
        if (!::client.isInitialized) {
            client = HealthServices.getClient(context.applicationContext).exerciseClient
        }
    }

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val m = update.latestMetrics
            val now = SystemClock.elapsedRealtime()

            // Batches can hold many samples (screen off): keep them all, with measurement times
            val bootEpochMs = System.currentTimeMillis() - now
            val hrPoints = m.getData(DataType.HEART_RATE_BPM)
            for (p in hrPoints) {
                val bpm = p.value.toInt()
                if (bpm > 0) history.addHeartRate(HrSample(bootEpochMs + p.timeDurationFromBoot.toMillis(), bpm))
            }
            hrPoints.filter { it.value > 0 }.maxByOrNull { it.timeDurationFromBoot }?.let {
                val bpm = it.value.toInt()
                WorkoutSession.heartRate = bpm
                lastHrAtMs = now
                _status.value = _status.value.copy(heartRate = bpm, hr = Lock.READY)
            }
            m.getData(DataType.DISTANCE_TOTAL)?.let {
                WorkoutSession.distanceM = it.total
                // Time the distance was measured (updates arrive in batches), on the elapsedRealtime clock
                val measuredAt = now - (System.currentTimeMillis() - it.end.toEpochMilli())
                pace.add(measuredAt.coerceAtMost(now), it.total)
            }
            val locPoints = m.getData(DataType.LOCATION)
            for (p in locPoints) {
                val v = p.value
                history.addLocation(LocationSample(bootEpochMs + p.timeDurationFromBoot.toMillis(),
                    v.latitude, v.longitude, v.altitude.takeIf { a -> !a.isNaN() }))
            }
            locPoints.maxByOrNull { it.timeDurationFromBoot }?.let {
                val v = it.value
                location = Location(v.latitude, v.longitude, v.altitude.takeIf { a -> !a.isNaN() }, now)
            }
            m.getData(DataType.ABSOLUTE_ELEVATION).lastOrNull()?.let { elevationM = it.value }
            m.getData(DataType.STEPS_PER_MINUTE).lastOrNull()?.let { cadenceSpm = it.value.toInt() }

            // Running dynamics: generic sample points, matched by data type name
            var d = dynamics
            for (point in m.sampleDataPoints) {
                val v = (point.value as? Number)?.toDouble() ?: continue
                d = when (point.dataType.name) {
                    "Ground Contact Time" -> d.copy(groundContactMs = v)
                    "Vertical Oscillation" -> d.copy(verticalOscillationCm = v)
                    "Vertical Ratio" -> d.copy(verticalRatioPct = v)
                    "Stride Length" -> d.copy(strideLengthM = v)
                    else -> d
                }
            }
            if (d != dynamics) {
                if (!loggedDynamics) {
                    Log.i(TAG, "First running dynamics: $d")  // Confirms units on a real run
                    loggedDynamics = true
                }
                dynamics = d
            }

            WorkoutSession.paceSecPerKm = pace.displayPace(now)
            // Drop a stale HR (watch off-wrist / sensor gap) rather than showing an old number
            if (now - lastHrAtMs > 10_000) WorkoutSession.heartRate = null
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit

        override fun onRegistered() = Unit

        override fun onRegistrationFailed(throwable: Throwable) {
            Log.w(TAG, "Update callback registration failed", throwable)
            _status.value = _status.value.copy(error = "Sensors unavailable")
        }

        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            when {
                availability is LocationAvailability -> _status.value = _status.value.copy(
                    gps = when (availability) {
                        LocationAvailability.ACQUIRED_TETHERED, LocationAvailability.ACQUIRED_UNTETHERED -> Lock.READY
                        LocationAvailability.ACQUIRING -> Lock.SEARCHING
                        else -> Lock.UNAVAILABLE
                    }
                )
                dataType == DataType.HEART_RATE_BPM && availability is DataTypeAvailability -> _status.value =
                    _status.value.copy(
                        hr = when (availability) {
                            DataTypeAvailability.AVAILABLE -> Lock.READY
                            DataTypeAvailability.ACQUIRING -> Lock.SEARCHING
                            else -> Lock.UNAVAILABLE
                        }
                    )
            }
        }
    }

    private suspend fun chooseDataTypes(gpsAllowed: Boolean) {
        val caps = client.getCapabilitiesAsync().await()
        val supported = caps.getExerciseTypeCapabilities(ExerciseType.RUNNING).supportedDataTypes
        val wanted = mutableSetOf<DataType<*, *>>(
            DataType.HEART_RATE_BPM,
            DataType.DISTANCE_TOTAL,
            DataType.ABSOLUTE_ELEVATION,
            DataType.STEPS_PER_MINUTE,
        )
        if (gpsAllowed) wanted += DataType.LOCATION
        wanted += supported.filter { it.name in DYNAMICS_NAMES }
        dataTypes = wanted.filter { it in supported }.toSet()
        Log.i(TAG, "RUNNING supports: ${supported.joinToString { it.name }}")
        Log.i(TAG, "Using: ${dataTypes.joinToString { it.name }}")
        gpsEnabled = DataType.LOCATION in dataTypes
    }

    private fun resetReadings() {
        location = null
        elevationM = null
        cadenceSpm = null
        dynamics = Dynamics()
        loggedDynamics = false
        lastHrAtMs = 0L
        history.clear()
        pace.reset()
    }

    /** Warm up GPS and HR on the pre-run screen so they're locked by the time you press Start. */
    suspend fun prepare(gpsAllowed: Boolean) {
        try {
            resetReadings()
            chooseDataTypes(gpsAllowed)
            _status.value = Status(
                gps = if (gpsEnabled) Lock.SEARCHING else Lock.UNAVAILABLE,
                hr = Lock.SEARCHING,
            )
            client.setUpdateCallback(callback)
            val warmUp = dataTypes
                .filter { it == DataType.HEART_RATE_BPM || it == DataType.LOCATION }
                .filterIsInstance<DeltaDataType<*, *>>()
                .toSet()
            client.prepareExerciseAsync(WarmUpConfig(ExerciseType.RUNNING, warmUp)).await()
        } catch (e: Exception) {
            Log.w(TAG, "prepare failed", e)
            _status.value = _status.value.copy(error = e.message ?: "Sensors unavailable")
        }
    }

    /** Start recording the exercise. Returns false if Health Services refused. */
    suspend fun start(gpsAllowed: Boolean): Boolean = try {
        if (dataTypes.isEmpty()) chooseDataTypes(gpsAllowed)
        client.setUpdateCallback(callback)
        val config = ExerciseConfig.builder(ExerciseType.RUNNING)
            .setDataTypes(dataTypes)
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(gpsEnabled)
            .build()
        client.startExerciseAsync(config).await()
        pace.reset()
        true
    } catch (e: Exception) {
        Log.w(TAG, "start failed", e)
        _status.value = _status.value.copy(error = e.message ?: "Sensors unavailable")
        false
    }

    suspend fun pause() = runCatching { client.pauseExerciseAsync().await(); pace.reset() }
        .onFailure { Log.w(TAG, "pause failed", it) }

    suspend fun resume() = runCatching { client.resumeExerciseAsync().await(); pace.reset() }
        .onFailure { Log.w(TAG, "resume failed", it) }

    /** End the exercise (also used to cancel a warm-up that never started). */
    suspend fun end() {
        runCatching { client.endExerciseAsync().await() }.onFailure { Log.w(TAG, "end failed", it) }
        runCatching { client.clearUpdateCallbackAsync(callback).await() }
        dataTypes = emptySet()
        _status.value = Status()
        WorkoutSession.heartRate = null
        WorkoutSession.paceSecPerKm = null
        resetReadings()
    }
}
