package fit.hotsync.pacer.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import fit.hotsync.pacer.WorkoutSession
import fit.hotsync.pacer.workout.Format

/** Summary: totals + per-segment breakdown. Upload to HotSync arrives with sync. */
@Composable
fun SummaryScreen(onDone: () -> Unit) {
    val ui by WorkoutSession.ui.collectAsStateWithLifecycle()
    val snap = ui.snapshot
    val completed = WorkoutSession.completed
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState) {
            item {
                Text(
                    if (snap?.isComplete == true) "Workout complete" else "Workout ended",
                    color = HsColors.Orange, fontWeight = FontWeight.Bold,
                )
            }
            item { Text(ui.workout?.name ?: "", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            item {
                Text(
                    "${Format.duration(snap?.totalActiveMs ?: 0)}  ·  ${Format.distance(snap?.totalDistanceM ?: 0.0)}",
                    fontSize = 20.sp, fontWeight = FontWeight.Bold,
                )
            }
            items(completed) { c ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    val set = c.segment.setText?.let { " $it" } ?: ""
                    Text(
                        "${c.segment.label}$set", fontSize = 13.sp, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text("${Format.duration(c.durationMs)}  ${Format.distance(c.distanceM)}", fontSize = 13.sp, color = HsColors.Dim)
                }
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDone,
                    label = { Text("Done") },
                    colors = ChipDefaults.primaryChipColors(backgroundColor = HsColors.Orange),
                )
            }
        }
    }
}
