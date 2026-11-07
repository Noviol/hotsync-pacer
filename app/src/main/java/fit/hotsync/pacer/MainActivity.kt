package fit.hotsync.pacer

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

/**
 * MainActivity
 * ----------------------------------------------------------------------------
 * Hosts the Jetpack-Compose-for-Wear UI and wires up Always-On (ambient) mode.
 *
 * The Activity holds NO race state. It merely:
 *   - starts the [PacerService] (which owns the clock),
 *   - observes [PacerEngine.snapshot],
 *   - reports ambient transitions back to the engine so the service loop can
 *     drop from 5 Hz to 1 Hz.
 */
class MainActivity : ComponentActivity() {

    // Tracks ambient (Always-On Display) state; drives the high-contrast theme.
    private var ambientState by mutableStateOf(false)

    // AmbientLifecycleObserver delivers AOD enter/exit/update callbacks.
    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                // Entered Always-On: pure-black theme + throttle redraw to 1 Hz.
                ambientState = true
                PacerEngine.isAmbient = true
            }

            override fun onExitAmbient() {
                // Back to interactive: full-contrast theme + 5 Hz redraw.
                ambientState = false
                PacerEngine.isAmbient = false
            }

            override fun onUpdateAmbient() {
                // Fired ~once/min by the system to let us refresh the AOD frame.
                PacerEngine.tick()
            }
        }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register the ambient observer for this Activity's lifecycle.
        lifecycle.addObserver(ambientObserver)

        // Launch the foreground service that runs the master race clock.
        val serviceIntent = Intent(this, PacerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }

        setContent {
            MaterialTheme {
                PacerScreen(ambient = ambientState)
            }
        }
    }
}

/**
 * The entire circular surface. One massive touch target; a tap anywhere fires
 * the manual course-sync. Layout is three zones over a full-bleed background:
 *   - Top-left  : upcoming milestone marker (e.g. "M12")
 *   - Center    : massive MM:SS countdown to the next marker
 *   - Bottom    : HH:MM:SS master race clock
 * A transient delta flash (+0:04 / -0:02) overlays the center on sync.
 */
@Composable
fun PacerScreen(ambient: Boolean) {
    val snapshot by PacerEngine.snapshot.collectAsStateWithLifecycle()
    val interactionSource = remember { MutableInteractionSource() }

    // ---- High-contrast palette --------------------------------------------
    // Ambient: pure black bg + stark white text (max battery + legibility).
    // Interactive: near-black bg; countdown turns red when behind schedule.
    val background = if (ambient) Color.Black else Color(0xFF050505)
    val primaryText = Color.White
    val accentText = when {
        ambient -> Color.White                        // no colour in AOD
        snapshot.behindPace -> Color(0xFFFF3B30)      // behind pace -> red
        else -> Color(0xFF30FF6A)                     // on/ahead -> green
    }
    val flashColor =
        if (snapshot.flashText.startsWith("+")) Color(0xFFFF3B30) else Color(0xFF30FF6A)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            // Whole surface = tap target. No ripple indication — we render our
            // own +/- delta flash as confirmation. Disabled in ambient (AOD
            // isn't touch-interactive and we want zero extra redraws).
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !ambient
            ) {
                PacerEngine.syncLap()
            },
        contentAlignment = Alignment.Center
    ) {
        // ---- TOP-LEFT : upcoming milestone marker ("M12") ------------------
        Text(
            text = snapshot.markerLabel,
            color = primaryText,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 22.dp, top = 20.dp)
        )

        // ---- CENTER : massive countdown OR the transient delta flash -------
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center)
        ) {
            if (snapshot.flashActive) {
                // Temporary confirmation flash after a manual sync.
                Text(
                    text = snapshot.flashText,
                    color = if (ambient) Color.White else flashColor,
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = snapshot.countdownText,
                    color = if (ambient) Color.White else accentText,
                    fontSize = 72.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
            }
        }

        // ---- BOTTOM : master race clock (HH:MM:SS) -------------------------
        Text(
            text = snapshot.elapsedText,
            color = primaryText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp)
        )
    }
}
