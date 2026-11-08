package fit.hotsync.pacer

import android.content.Context
import android.os.Build
import android.os.SystemClock
import fit.hotsync.pacer.recording.LapInfo
import fit.hotsync.pacer.recording.RunRecording
import fit.hotsync.pacer.recording.TcxWriter
import fit.hotsync.pacer.recording.TrackPoint
import fit.hotsync.pacer.sensors.ExerciseSensors
import fit.hotsync.pacer.upload.UploadQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import fit.hotsync.pacer.workout.AlertTracker
import fit.hotsync.pacer.workout.TargetMath
import fit.hotsync.pacer.workout.TargetMode
import fit.hotsync.pacer.workout.TargetStatus
import fit.hotsync.pacer.workout.Workout
import fit.hotsync.pacer.workout.WorkoutEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WorkoutSession
 * ----------------------------------------------------------------------------
 * Process-wide holder for the structured workout being run, the same pattern as
 * [PacerEngine]: [PacerService] drives [tick] from its clock loop, the UI reads
 * [ui] and calls the controls (lap, pause, end).
 *
 * Sensor inputs ([heartRate], [distanceM], [paceSecPerKm]) are written by the
 * sensor layer (next step); until then they stay empty and the gauge shows "--".
 */
object WorkoutSession {

    data class AlertSettings(
        val haptic: Boolean = true,
        val sound: Boolean = false,
        val visual: Boolean = true,
        val delaySeconds: Int = 10,
    )

    data class Ui(
        val workout: Workout? = null,
        val header: String = "WORKOUT",     // e.g. "TODAY'S WORKOUT" or "LIBRARY"
        val eventId: String? = null,         // Calendar event this run is for (used when uploading)
        val eventDate: java.time.LocalDate? = null,
        val snapshot: WorkoutEngine.Snapshot? = null,
        val heartRate: Int? = null,
        val paceSecPerKm: Double? = null,
        val cadenceSpm: Int? = null,         // steps per minute
        val status: TargetStatus = TargetStatus.UNKNOWN,
        val gauge: Double? = null,           // 0..1 pointer position, null = no reading
        val alert: TargetStatus? = null,     // BELOW/ABOVE while an alert is active
        val alerts: AlertSettings = AlertSettings(),
        val ended: Boolean = false,
        val uploadId: String? = null,        // Set once the run is saved and queued for Strava
    )

    /** Haptics/sound live in the service (it has a Context); the session only decides when. */
    interface Feedback {
        fun outOfRange(status: TargetStatus, settings: AlertSettings)
        fun segmentChanged(settings: AlertSettings)
    }

    @Volatile var feedback: Feedback? = null

    // Sensor inputs (written by the sensor layer)
    @Volatile var heartRate: Int? = null
    @Volatile var paceSecPerKm: Double? = null
    @Volatile var distanceM: Double = 0.0

    private var engine: WorkoutEngine? = null
    private var alertTracker = AlertTracker(10_000)
    private var lastCompletedCount = 0
    private var recording: RunRecording? = null
    private var lastPointAtMs = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    /** True from start until the runner ends the workout. */
    val isActive: Boolean
        get() = engine?.isStarted == true && !_ui.value.ended

    val completed: List<WorkoutEngine.CompletedSegment>
        get() = engine?.completed.orEmpty()

    /** Load a workout for the pre-run screen. */
    @Synchronized
    fun prepare(
        workout: Workout,
        alerts: AlertSettings,
        header: String = "WORKOUT",
        eventId: String? = null,
        eventDate: java.time.LocalDate? = null,
    ) {
        engine = WorkoutEngine(workout)
        alertTracker = AlertTracker(alerts.delaySeconds * 1000L)
        lastCompletedCount = 0
        heartRate = null
        paceSecPerKm = null
        distanceM = 0.0
        recording = null
        _ui.value = Ui(
            workout = workout, header = header, eventId = eventId, eventDate = eventDate,
            alerts = alerts, snapshot = engine!!.snapshot(now()),
        )
    }

    @Synchronized
    fun setAlerts(alerts: AlertSettings) {
        _ui.value = _ui.value.copy(alerts = alerts)
    }

    @Synchronized
    fun start() {
        val e = engine ?: return
        if (e.isStarted) return
        e.start(now(), distanceM)
        recording = RunRecording(System.currentTimeMillis())
        lastPointAtMs = 0L
        publish(e.snapshot(now()))
    }

    /** Called by the service loop every tick. */
    @Synchronized
    fun tick() {
        val e = engine ?: return
        if (!e.isStarted || _ui.value.ended) return
        publish(e.update(now(), distanceM))
        recordPoint(e)
    }

    /** One track point per second while running (not while paused). */
    private fun recordPoint(e: WorkoutEngine) {
        val rec = recording ?: return
        val t = now()
        if (e.isPaused || t - lastPointAtMs < 1_000) return
        lastPointAtMs = t
        val loc = ExerciseSensors.location?.takeIf { t - it.atElapsedMs < 5_000 }
        rec.add(
            TrackPoint(
                epochMs = System.currentTimeMillis(),
                lat = loc?.lat,
                lon = loc?.lon,
                altitudeM = ExerciseSensors.elevationM ?: loc?.altitudeM,
                distanceM = distanceM,
                heartRate = heartRate,
                cadenceSpm = ExerciseSensors.cadenceSpm,
                segment = e.index,
            )
        )
    }

    @Synchronized
    fun lap() {
        val e = engine ?: return
        if (!e.isStarted || e.isPaused || _ui.value.ended) return
        publish(e.lap(now(), distanceM))
    }

    @Synchronized
    fun togglePause() {
        val e = engine ?: return
        if (_ui.value.ended) return
        if (e.isPaused) {
            e.resume(now())
            scope.launch { ExerciseSensors.resume() }
        } else {
            e.pause(now())
            scope.launch { ExerciseSensors.pause() }
        }
        publish(e.snapshot(now()))
    }

    @Synchronized
    fun end() {
        val e = engine ?: return
        if (!e.isPaused) e.pause(now())  // freezes the totals
        _ui.value = _ui.value.copy(snapshot = e.snapshot(now()), alert = null, ended = true)
    }

    /**
     * After [end]: write the run as TCX and queue it for Strava.
     * Returns the upload id, or null if nothing was recorded.
     */
    @Synchronized
    fun save(context: Context): String? {
        val e = engine ?: return null
        val rec = recording ?: return null
        val ui = _ui.value
        if (ui.uploadId != null) return ui.uploadId
        if (rec.points.isEmpty()) return null
        val laps = e.segments.map { seg ->
            LapInfo(seg.index, seg.label + (seg.setText?.let { " $it" } ?: ""), e.completed.getOrNull(seg.index)?.durationMs)
        }
        val tcx = TcxWriter.write(rec, laps, Build.MODEL ?: "Wear OS watch")
        val id = "hotsync-${rec.startEpochMs}"
        UploadQueue.enqueue(
            context, id, tcx,
            UploadQueue.Meta(
                name = ui.workout?.name ?: "Run",
                eventId = ui.eventId,
                eventDate = ui.eventDate?.toString(),
            ),
        )
        _ui.value = ui.copy(uploadId = id)
        return id
    }

    @Synchronized
    fun clear() {
        engine = null
        recording = null
        _ui.value = Ui()
    }

    private fun publish(snap: WorkoutEngine.Snapshot) {
        val current = _ui.value
        val workout = current.workout ?: return
        val target = snap.segment?.target
        val value = when (workout.mode) {
            TargetMode.HR -> heartRate?.toDouble()
            TargetMode.PACE -> paceSecPerKm
        }
        val status = if (snap.isPaused || snap.isComplete) TargetStatus.UNKNOWN else TargetMath.status(target, value)
        val alert = alertTracker.update(status, now(), snap.segmentNumber)

        // Buzz whenever a segment ends (including the last one)
        val fb = feedback
        val completedCount = engine?.completed?.size ?: 0
        if (completedCount > lastCompletedCount) fb?.segmentChanged(current.alerts)
        lastCompletedCount = completedCount
        if (alert.fire && alert.active != null) fb?.outOfRange(alert.active, current.alerts)

        _ui.value = current.copy(
            snapshot = snap,
            heartRate = heartRate,
            paceSecPerKm = paceSecPerKm,
            cadenceSpm = ExerciseSensors.cadenceSpm?.takeIf { it > 0 },
            status = status,
            gauge = TargetMath.gaugePosition(target, value),
            alert = alert.active,
        )
    }

    private fun now() = SystemClock.elapsedRealtime()
}
