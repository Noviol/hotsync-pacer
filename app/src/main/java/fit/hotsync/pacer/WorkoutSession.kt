package fit.hotsync.pacer

import android.os.SystemClock
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
        val snapshot: WorkoutEngine.Snapshot? = null,
        val heartRate: Int? = null,
        val paceSecPerKm: Double? = null,
        val status: TargetStatus = TargetStatus.UNKNOWN,
        val gauge: Double? = null,           // 0..1 pointer position, null = no reading
        val alert: TargetStatus? = null,     // BELOW/ABOVE while an alert is active
        val alerts: AlertSettings = AlertSettings(),
        val ended: Boolean = false,
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

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    /** True from start until the runner ends the workout. */
    val isActive: Boolean
        get() = engine?.isStarted == true && !_ui.value.ended

    val completed: List<WorkoutEngine.CompletedSegment>
        get() = engine?.completed.orEmpty()

    /** Load a workout for the pre-run screen. */
    @Synchronized
    fun prepare(workout: Workout, alerts: AlertSettings) {
        engine = WorkoutEngine(workout)
        alertTracker = AlertTracker(alerts.delaySeconds * 1000L)
        lastCompletedCount = 0
        heartRate = null
        paceSecPerKm = null
        distanceM = 0.0
        _ui.value = Ui(workout = workout, alerts = alerts, snapshot = engine!!.snapshot(now()))
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
        publish(e.snapshot(now()))
    }

    /** Called by the service loop every tick. */
    @Synchronized
    fun tick() {
        val e = engine ?: return
        if (!e.isStarted || _ui.value.ended) return
        publish(e.update(now(), distanceM))
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
        if (e.isPaused) e.resume(now()) else e.pause(now())
        publish(e.snapshot(now()))
    }

    @Synchronized
    fun end() {
        val e = engine ?: return
        if (!e.isPaused) e.pause(now())  // freezes the totals
        _ui.value = _ui.value.copy(snapshot = e.snapshot(now()), alert = null, ended = true)
    }

    @Synchronized
    fun clear() {
        engine = null
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
            status = status,
            gauge = TargetMath.gaugePosition(target, value),
            alert = alert.active,
        )
    }

    private fun now() = SystemClock.elapsedRealtime()
}
