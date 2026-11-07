package fit.hotsync.pacer

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * PacerEngine
 * ----------------------------------------------------------------------------
 * The single source of truth for the race. It is a process-wide singleton so
 * that both [PacerService] (which owns the master clock loop) and the UI in
 * [MainActivity] read/write the exact same state — no data is duplicated or
 * marshalled between them.
 *
 * The clock is driven by [SystemClock.elapsedRealtime] rather than
 * System.currentTimeMillis(). elapsedRealtime is monotonic and immune to wall-
 * clock adjustments (NTP sync, timezone, user changes), giving us drift-free
 * precision across a 4+ hour race.
 *
 *  ┌──────────────────────────────────────────────────────────────────────┐
 *  │  >>> RACE CONFIGURATION LIVES HERE <<<                                 │
 *  │  Change TARGET_DISTANCE / TARGET_TIME_MINUTES to re-target the race.   │
 *  └──────────────────────────────────────────────────────────────────────┘
 */
object PacerEngine {

    // ========================================================================
    // RACE CONFIGURATION  — edit these two values to re-target the whole race.
    // ========================================================================

    /** Number of milestone markers on the course (e.g. 21 = a half-marathon km grid). */
    const val TARGET_DISTANCE: Int = 21

    /** Total goal time, in minutes, to cover all [TARGET_DISTANCE] markers. */
    const val TARGET_TIME_MINUTES: Double = 88.0

    /** How long the +/- delta confirmation flash stays on screen after a tap. */
    private const val FLASH_DURATION_MS: Long = 2_000L

    // ------------------------------------------------------------------------
    // Derived pacing constants
    // ------------------------------------------------------------------------

    /** Ideal seconds allotted per marker (the pace budget for one segment). */
    val secondsPerMarker: Double
        get() = (TARGET_TIME_MINUTES * 60.0) / TARGET_DISTANCE

    // ------------------------------------------------------------------------
    // Live state
    // ------------------------------------------------------------------------

    /** Baseline timestamp captured at race start; all elapsed maths derive from it. */
    @Volatile private var startTimestampMs: Long = 0L

    /** True once [start] has been called and the clock is running. */
    @Volatile var isRunning: Boolean = false
        private set

    /** The next milestone marker we are racing toward (1-based, e.g. 12 -> "M12"). */
    @Volatile private var nextMarker: Int = 1

    /** Wall time (elapsedRealtime) until which the delta flash should be shown. */
    @Volatile private var flashUntilMs: Long = 0L

    /** Cached formatted delta text (e.g. "+0:04" / "-0:02") for the flash overlay. */
    @Volatile private var flashText: String = ""

    /**
     * Set by the UI (Activity) so the master loop in the service can choose its
     * tick cadence: 1 Hz in ambient / Always-On, 5 Hz when interactive.
     */
    @Volatile var isAmbient: Boolean = false

    // ------------------------------------------------------------------------
    // Snapshot stream consumed by Compose
    // ------------------------------------------------------------------------

    private val _snapshot = MutableStateFlow(RaceSnapshot())

    /** Immutable, recomposition-friendly view of the race, republished each tick. */
    val snapshot: StateFlow<RaceSnapshot> = _snapshot.asStateFlow()

    // ------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------

    /** Captures the baseline timestamp and arms the state machine. Idempotent-safe. */
    fun start() {
        startTimestampMs = SystemClock.elapsedRealtime()
        nextMarker = 1
        flashUntilMs = 0L
        flashText = ""
        isRunning = true
        tick() // publish an immediate frame so the UI isn't blank on launch
    }

    /** Stops the clock (service teardown). State is retained for a possible resume. */
    fun stop() {
        isRunning = false
    }

    // ------------------------------------------------------------------------
    // Master clock — called by the foreground service loop every tick.
    // ------------------------------------------------------------------------

    /**
     * Recomputes the current race frame and republishes [snapshot]. This is the
     * ONLY place the snapshot is produced, guaranteeing the UI and the clock are
     * never out of sync. Cheap and allocation-light so it is safe at 5 Hz.
     */
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        val elapsedMs = if (isRunning) now - startTimestampMs else 0L

        // Target elapsed time (ms) at which we *should* arrive at the next marker.
        val markerDeadlineMs = (nextMarker * secondsPerMarker * 1000.0).toLong()

        // Remaining window to the upcoming marker. Can go negative if we are late;
        // the countdown formatter clamps/handles the sign for display.
        val remainingMs = markerDeadlineMs - elapsedMs

        val raceComplete = nextMarker > TARGET_DISTANCE
        val flashActive = now < flashUntilMs

        _snapshot.value = RaceSnapshot(
            markerLabel = if (raceComplete) "DONE" else "M$nextMarker",
            countdownText = formatCountdown(remainingMs),
            behindPace = remainingMs < 0,               // drives colour when running late
            elapsedText = formatElapsed(elapsedMs),
            flashActive = flashActive,
            flashText = if (flashActive) flashText else "",
            raceComplete = raceComplete
        )
    }

    // ------------------------------------------------------------------------
    // Manual course sync — the single user interaction (tap anywhere).
    // ------------------------------------------------------------------------

    /**
     * MANUAL LAP SYNC. Called when the runner physically taps the screen as they
     * pass the upcoming course marker.
     *
     *  1. Captures the exact current elapsed duration.
     *  2. Computes the strict pacing delta = (actual arrival) - (ideal arrival).
     *       positive  -> arrived late  -> "+0:04" (behind schedule)
     *       negative  -> arrived early -> "-0:02" (ahead of schedule)
     *  3. Arms a temporary visual flash of that delta.
     *  4. Advances the state machine to track the NEXT sequence marker.
     */
    fun syncLap() {
        if (!isRunning) return
        if (nextMarker > TARGET_DISTANCE) return // race already finished

        val elapsedMs = SystemClock.elapsedRealtime() - startTimestampMs
        val idealArrivalMs = (nextMarker * secondsPerMarker * 1000.0).toLong()
        val deltaMs = elapsedMs - idealArrivalMs

        flashText = formatDelta(deltaMs)
        flashUntilMs = SystemClock.elapsedRealtime() + FLASH_DURATION_MS

        nextMarker++      // instantly retarget to the next marker in the sequence
        tick()            // republish immediately so the flash + new marker appear at once
    }

    // ------------------------------------------------------------------------
    // Formatting helpers
    // ------------------------------------------------------------------------

    /** Countdown window as MM:SS. Negative (running late) is shown with a leading '-'. */
    private fun formatCountdown(remainingMs: Long): String {
        val sign = if (remainingMs < 0) "-" else ""
        val totalSec = abs(remainingMs) / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return "%s%02d:%02d".format(sign, minutes, seconds)
    }

    /** Total race clock as HH:MM:SS. */
    private fun formatElapsed(elapsedMs: Long): String {
        val totalSec = elapsedMs / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    /** Pacing delta as +/-M:SS, e.g. "+0:04" (late) or "-0:02" (early). */
    private fun formatDelta(deltaMs: Long): String {
        val sign = if (deltaMs >= 0) "+" else "-"
        val totalSec = abs(deltaMs) / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return "%s%d:%02d".format(sign, minutes, seconds)
    }
}

/**
 * RaceSnapshot
 * Immutable value object the UI collects. Everything the screen needs is pre-
 * computed here so Compose does zero business logic during recomposition.
 */
data class RaceSnapshot(
    val markerLabel: String = "M1",        // Top-left milestone tracker (e.g. "M12")
    val countdownText: String = "00:00",   // Center MM:SS window to next marker
    val behindPace: Boolean = false,       // true -> runner is behind schedule
    val elapsedText: String = "00:00:00",  // Bottom HH:MM:SS master race clock
    val flashActive: Boolean = false,      // true while the sync confirmation shows
    val flashText: String = "",            // e.g. "+0:04" / "-0:02"
    val raceComplete: Boolean = false
)
