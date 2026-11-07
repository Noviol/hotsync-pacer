package fit.hotsync.pacer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * PacerService
 * ----------------------------------------------------------------------------
 * A started FOREGROUND SERVICE that houses the master race-clock loop.
 *
 * Why a foreground service + wake lock?
 *   Wear OS aggressively dozes the CPU to save battery. For a 4+ hour race we
 *   need the clock thread to keep ticking with absolute precision even while the
 *   screen is off / in ambient. A foreground service earns us an exemption from
 *   background execution limits, and a PARTIAL_WAKE_LOCK keeps the CPU awake so
 *   the loop is never throttled or suspended.
 *
 * The loop itself does almost nothing expensive: it just calls
 * [PacerEngine.tick] on a cadence and sleeps. All timing maths are anchored to
 * elapsedRealtime inside the engine, so even if a tick is momentarily delayed
 * the displayed values self-correct on the next tick (no accumulated drift).
 */
class PacerService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    // ------------------------------------------------------------------------
    // Tick cadence — 5 Hz interactive, 1 Hz ambient (see requirement).
    // ------------------------------------------------------------------------
    companion object {
        private const val CHANNEL_ID = "race_pacer_clock"
        private const val NOTIFICATION_ID = 1001

        /** Interactive refresh: 5 Hz -> 200 ms between frames. */
        private const val INTERVAL_ACTIVE_MS = 200L

        /** Ambient / Always-On refresh: 1 Hz -> 1000 ms between frames. */
        private const val INTERVAL_AMBIENT_MS = 1_000L
    }

    override fun onBind(intent: Intent?): IBinder? = null // not a bound service

    override fun onCreate() {
        super.onCreate()

        // 1) Promote to foreground with a persistent notification (required by the OS).
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // 2) Acquire a CPU wake lock so the loop can never be throttled/suspended.
        //    PARTIAL_WAKE_LOCK keeps the CPU running while allowing the screen off.
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RacePacer::MasterClockWakeLock"
        ).apply {
            setReferenceCounted(false)
            // Timeout guards against a runaway lock; 5h comfortably covers a 4h+ race.
            acquire(5 * 60 * 60 * 1000L /* 5 hours */)
        }

        // 3) Arm the race clock and start the master loop.
        PacerEngine.start()
        startClockLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if the system ever kills us under memory pressure, restart
        // the service so the race clock resumes as soon as possible.
        return START_STICKY
    }

    /**
     * The MASTER CLOCK LOOP. Runs off the main thread on the Default dispatcher.
     * Each iteration recomputes the race frame and sleeps for the cadence that
     * matches the current display mode (ambient vs interactive).
     */
    private fun startClockLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                PacerEngine.tick()
                val interval =
                    if (PacerEngine.isAmbient) INTERVAL_AMBIENT_MS else INTERVAL_ACTIVE_MS
                delay(interval)
            }
        }
    }

    override fun onDestroy() {
        PacerEngine.stop()
        loopJob?.cancel()
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------------
    // Foreground notification plumbing
    // ------------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Race Pacer Clock",
                NotificationManager.IMPORTANCE_LOW // silent, no heads-up
            ).apply {
                description = "Keeps the race clock running precisely in the background."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // Tapping the notification returns to the pacer screen.
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Race Pacer Active")
            .setContentText("Master clock running")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(contentIntent)
            .setOngoing(true)          // user cannot swipe it away mid-race
            .setSilent(true)
            .build()
    }
}
