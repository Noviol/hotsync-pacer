package fit.hotsync.pacer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.currentBackStackEntryAsState
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import fit.hotsync.pacer.sync.SyncRepository
import fit.hotsync.pacer.ui.HomeScreen
import fit.hotsync.pacer.ui.LibraryScreen
import fit.hotsync.pacer.ui.PairScreen
import fit.hotsync.pacer.ui.PreRunScreen
import fit.hotsync.pacer.ui.RaceScreen
import fit.hotsync.pacer.ui.SummaryScreen
import fit.hotsync.pacer.ui.WorkoutRunScreen
import kotlinx.coroutines.launch

/**
 * MainActivity
 * ----------------------------------------------------------------------------
 * Hosts the Compose-for-Wear UI and wires up Always-On (ambient) mode.
 *
 * Modes:
 *   - Workouts synced from hotsync.fit (today's, coming up, library):
 *     pre-run -> structured workout -> summary
 *   - Race Pacer: the original marker-based race clock
 *
 * The Activity holds NO run state: [PacerEngine] and [WorkoutSession] do, and
 * [PacerService] (started only when a run begins) drives their clocks.
 */
class MainActivity : ComponentActivity() {

    // Tracks ambient (Always-On Display) state; drives the high-contrast theme.
    private var ambientState by mutableStateOf(false)

    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                ambientState = true
                PacerEngine.isAmbient = true  // service loop drops to 1 Hz
            }

            override fun onExitAmbient() {
                ambientState = false
                PacerEngine.isAmbient = false
            }

            override fun onUpdateAmbient() {
                // Fired ~once/min by the system to refresh the AOD frame.
                if (PacerEngine.isRunning) PacerEngine.tick()
                WorkoutSession.tick()
            }
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        SyncRepository.init(this)

        setContent {
            MaterialTheme {
                HotSyncApp(ambient = ambientState)
            }
        }
    }
}

private object Routes {
    const val HOME = "home"
    const val PRE_RUN = "prerun"
    const val WORKOUT = "workout"
    const val SUMMARY = "summary"
    const val RACE = "race"
    const val PAIR = "pair"
    const val LIBRARY = "library"
}

private fun alertsFrom(defaults: fit.hotsync.pacer.sync.AlertDefaults?) = defaults?.let {
    WorkoutSession.AlertSettings(it.haptic, it.sound, it.visual, it.delaySeconds)
} ?: WorkoutSession.AlertSettings()

@Composable
fun HotSyncApp(ambient: Boolean) {
    val context = LocalContext.current
    val nav = rememberSwipeDismissableNavController()
    val scope = rememberCoroutineScope()
    val syncState by SyncRepository.state.collectAsState()

    // Reopening the app mid-run goes straight back to the run
    val startRoute = remember {
        when {
            PacerEngine.isRunning -> Routes.RACE
            WorkoutSession.isActive -> Routes.WORKOUT
            WorkoutSession.ui.value.ended -> Routes.SUMMARY
            else -> Routes.HOME
        }
    }

    // No swipe-to-go-back during a run: it would fight the pager and risk leaving by accident
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val swipeEnabled = route != Routes.WORKOUT && route != Routes.RACE

    fun goHome() {
        if (!nav.popBackStack(Routes.HOME, inclusive = false)) {
            nav.navigate(Routes.HOME) { popUpTo(0) }
        }
    }

    SwipeDismissableNavHost(navController = nav, startDestination = startRoute, userSwipeEnabled = swipeEnabled) {
        composable(Routes.HOME) {
            LaunchedEffect(Unit) { SyncRepository.sync() }  // Refresh whenever home is shown (at most once a minute)
            HomeScreen(
                state = syncState,
                onPair = { nav.navigate(Routes.PAIR) },
                onScheduled = { entry, workout ->
                    val header = if (entry.date == java.time.LocalDate.now()) "TODAY'S WORKOUT" else "SCHEDULED"
                    WorkoutSession.prepare(workout, alertsFrom(syncState.data?.alerts), header, entry.eventId, entry.date)
                    nav.navigate(Routes.PRE_RUN)
                },
                onLibrary = { nav.navigate(Routes.LIBRARY) },
                onRace = {
                    PacerService.startRace(context)
                    nav.navigate(Routes.RACE)
                },
                onSync = { scope.launch { SyncRepository.sync(force = true) } },
                onUnpair = { SyncRepository.unpair() },
            )
        }
        composable(Routes.PAIR) {
            PairScreen(onPaired = { goHome() })
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(syncState.data?.workouts.orEmpty()) { workout ->
                WorkoutSession.prepare(workout, alertsFrom(syncState.data?.alerts), "LIBRARY")
                nav.navigate(Routes.PRE_RUN)
            }
        }
        composable(Routes.PRE_RUN) {
            PreRunScreen(onStart = {
                PacerService.startWorkout(context)
                nav.navigate(Routes.WORKOUT) { popUpTo(Routes.HOME) }
            })
        }
        composable(Routes.WORKOUT) {
            WorkoutRunScreen(ambient = ambient, onEnded = {
                PacerService.stop(context)
                nav.navigate(Routes.SUMMARY) { popUpTo(Routes.HOME) }
            })
        }
        composable(Routes.SUMMARY) {
            SummaryScreen(onDone = {
                WorkoutSession.clear()
                goHome()
            })
        }
        composable(Routes.RACE) {
            RaceScreen(ambient = ambient, onEnd = {
                PacerService.stop(context)
                goHome()
            })
        }
    }
}
