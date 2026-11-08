package fit.hotsync.pacer.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import fit.hotsync.pacer.WorkoutSession
import fit.hotsync.pacer.workout.Format
import fit.hotsync.pacer.workout.TargetMode

/** Pre-run: workout preview, alert toggles (defaults pre-filled), Start. GPS/HR lock status arrives with sensors. */
@Composable
fun PreRunScreen(onStart: () -> Unit) {
    val ui by WorkoutSession.ui.collectAsStateWithLifecycle()
    val workout = ui.workout ?: return
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.crownScroll(listState)) {
            item {
                Text(
                    "${ui.header}  ·  ${if (workout.mode == TargetMode.HR) "HR" else "PACE"}",
                    color = HsColors.Orange, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                )
            }
            item {
                Text(workout.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
            item {
                Text(
                    Format.summary(workout), color = HsColors.Dim, fontSize = 12.sp, textAlign = TextAlign.Center,
                )
            }
            item { Text("Total ${Format.total(workout)}", fontSize = 13.sp) }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onStart,
                    label = { Text("Start", fontWeight = FontWeight.Bold) },
                    colors = ChipDefaults.primaryChipColors(backgroundColor = HsColors.Green),
                )
            }
            item { AlertToggle("Haptic", ui.alerts.haptic) { WorkoutSession.setAlerts(ui.alerts.copy(haptic = it)) } }
            item { AlertToggle("Sound", ui.alerts.sound) { WorkoutSession.setAlerts(ui.alerts.copy(sound = it)) } }
            item { AlertToggle("Visual", ui.alerts.visual) { WorkoutSession.setAlerts(ui.alerts.copy(visual = it)) } }
        }
    }
}

@Composable
fun AlertToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ToggleChip(
        modifier = Modifier.fillMaxWidth(),
        checked = checked,
        onCheckedChange = onChange,
        label = { Text("$label alert") },
        toggleControl = { Switch(checked = checked) },
    )
}
