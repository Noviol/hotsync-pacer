package fit.hotsync.pacer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import fit.hotsync.pacer.PacerEngine

/**
 * Race pacer mode (unchanged behaviour). One massive touch target: a tap anywhere
 * fires the manual course sync. Long-press to end the race.
 *   - Top-left  : upcoming milestone marker (e.g. "M12")
 *   - Center    : massive MM:SS countdown to the next marker
 *   - Bottom    : HH:MM:SS master race clock
 * A transient delta flash (+0:04 / -0:02) overlays the center on sync.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RaceScreen(ambient: Boolean, onEnd: () -> Unit) {
    val snapshot by PacerEngine.snapshot.collectAsStateWithLifecycle()
    val interactionSource = remember { MutableInteractionSource() }
    var confirmEnd by remember { mutableStateOf(false) }

    // Ambient: pure black bg + stark white text. Interactive: countdown red when behind.
    val background = if (ambient) Color.Black else Color(0xFF050505)
    val primaryText = Color.White
    val accentText = when {
        ambient -> Color.White
        snapshot.behindPace -> Color(0xFFFF3B30)
        else -> Color(0xFF30FF6A)
    }
    val flashColor =
        if (snapshot.flashText.startsWith("+")) Color(0xFFFF3B30) else Color(0xFF30FF6A)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !ambient,
                onLongClick = { confirmEnd = true },
                onClick = { if (confirmEnd) confirmEnd = false else PacerEngine.syncLap() },
            ),
        contentAlignment = Alignment.Center
    ) {
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

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text(
                text = if (snapshot.flashActive) snapshot.flashText else snapshot.countdownText,
                color = when {
                    ambient -> Color.White
                    snapshot.flashActive -> flashColor
                    else -> accentText
                },
                fontSize = 72.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
        }

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

        // Long-press confirmation: tap "End race" to stop, tap elsewhere to cancel
        if (confirmEnd && !ambient) {
            Chip(
                onClick = onEnd,
                label = { Text("End race", fontWeight = FontWeight.Bold) },
                colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFFFF3B30)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 54.dp),
            )
        }
    }
}
