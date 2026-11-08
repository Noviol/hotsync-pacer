package fit.hotsync.pacer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import fit.hotsync.pacer.sensors.ExerciseSensors
import fit.hotsync.pacer.workout.TargetStatus
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
 * A started FOREGROUND SERVICE that houses the master clock loop for whichever
 * mode is running: the race pacer ([PacerEngine]) or a structured workout
 * ([WorkoutSession]). Started on demand from the UI, stopped when the run ends.
 *
 * Why a foreground service + wake lock?
 *   Wear OS aggressively dozes the CPU to save battery. For a long run we need
 *   the clock to keep ticking even while the screen is off / in ambient. A
 *   foreground service exempts us from background limits, and a
 *   PARTIAL_WAKE_LOCK keeps the CPU awake so the loop is never suspended.
 *
 * All timing maths are anchored to elapsedRealtime inside the engines, so a
 * delayed tick self-corrects on the next one (no accumulated drift).
 */
class PacerService : Service(), WorkoutSession.Feedback {

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var tone: ToneGenerator? = null

    companion object {
        private const val CHANNEL_ID = "race_pacer_clock"
        private const val NOTIFICATION_ID = 1001

        /** Interactive refresh: 5 Hz -> 200 ms between frames. */
        private const val INTERVAL_ACTIVE_MS = 200L

        /** Ambient / Always-On refresh: 1 Hz -> 1000 ms between frames. */
        private const val INTERVAL_AMBIENT_MS = 1_000L

        const val ACTION_START_RACE = "fit.hotsync.pacer.START_RACE"
        const val ACTION_START_WORKOUT = "fit.hotsync.pacer.START_WORKOUT"

        fun startRace(context: Context) = launch(context, ACTION_START_RACE)
        fun startWorkout(context: Context) = launch(context, ACTION_START_WORKOUT)

        fun stop(context: Context) {
            context.stopService(Intent(context, PacerService::class.java))
        }

        private fun launch(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context, Intent(context, PacerService::class.java).setAction(action)
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null // not a bound service

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RacePacer::MasterClockWakeLock"
        ).apply {
            setReferenceCounted(false)
            // Timeout guards against a runaway lock; 5h comfortably covers a long run.
            acquire(5 * 60 * 60 * 1000L /* 5 hours */)
        }

        WorkoutSession.feedback = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RACE -> {
                startForeground(
                    NOTIFICATION_ID, buildNotification("Race Pacer Active", "Race clock running"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
                if (!PacerEngine.isRunning) PacerEngine.start()
            }
            ACTION_START_WORKOUT -> {
                val name = WorkoutSession.ui.value.workout?.name ?: "Workout"
                startForeground(NOTIFICATION_ID, buildNotification("Workout Active", name), workoutServiceType())
                WorkoutSession.start()
                val gps = granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
                scope.launch { ExerciseSensors.start(gpsAllowed = gps) }
            }
            else -> {
                // Restarted by the system after the process died: the in-memory run is gone.
                startForeground(
                    NOTIFICATION_ID, buildNotification("HotSync", "Stopping"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startClockLoop()
        return START_STICKY
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Health + location types keep sensors flowing with the screen off; each needs its permission. */
    private fun workoutServiceType(): Int {
        var type = 0
        val health = granted(android.Manifest.permission.ACTIVITY_RECOGNITION) ||
            granted(android.Manifest.permission.BODY_SENSORS) ||
            granted("android.permission.health.READ_HEART_RATE")
        if (health) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        if (granted(android.Manifest.permission.ACCESS_FINE_LOCATION)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        return if (type == 0) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else type
    }

    /** The MASTER CLOCK LOOP: ticks whichever mode is running. */
    private fun startClockLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                if (PacerEngine.isRunning) PacerEngine.tick()
                WorkoutSession.tick()
                delay(if (PacerEngine.isAmbient) INTERVAL_AMBIENT_MS else INTERVAL_ACTIVE_MS)
            }
        }
    }

    override fun onDestroy() {
        PacerEngine.stop()
        if (WorkoutSession.feedback === this) WorkoutSession.feedback = null
        loopJob?.cancel()
        scope.cancel()
        tone?.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------------
    // Workout feedback (haptic / sound). The visual bubble is drawn by the UI.
    // ------------------------------------------------------------------------

    override fun outOfRange(status: TargetStatus, settings: WorkoutSession.AlertSettings) {
        // Two long pulses = too high/fast, three short = too low/slow
        if (settings.haptic) vibrate(
            if (status == TargetStatus.ABOVE) longArrayOf(0, 400, 200, 400)
            else longArrayOf(0, 150, 120, 150, 120, 150)
        )
        if (settings.sound) beep(ToneGenerator.TONE_PROP_BEEP2)
    }

    override fun segmentChanged(settings: WorkoutSession.AlertSettings) {
        if (settings.haptic) vibrate(longArrayOf(0, 600))
        if (settings.sound) beep(ToneGenerator.TONE_PROP_ACK)
    }

    private fun vibrate(pattern: LongArray) {
        val vm = getSystemService(VibratorManager::class.java) ?: return
        vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun beep(toneType: Int) {
        val generator = tone ?: runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }
            .getOrNull()?.also { tone = it } ?: return
        generator.startTone(toneType, 300)
    }

    // ------------------------------------------------------------------------
    // Foreground notification plumbing
    // ------------------------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Run Clock",
            NotificationManager.IMPORTANCE_LOW // silent, no heads-up
        ).apply {
            description = "Keeps the run clock running precisely in the background."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setOngoing(true)          // user cannot swipe it away mid-run
            .setSilent(true)

        // Ongoing activity: a tappable icon on the watch face, and Wear OS returns
        // to the run (instead of the watch face) when the screen wakes.
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_launcher_foreground)
            .setTouchIntent(contentIntent)
            .setStatus(Status.Builder().addTemplate(text).build())
            .build()
            .apply(applicationContext)

        return builder.build()
    }
}
