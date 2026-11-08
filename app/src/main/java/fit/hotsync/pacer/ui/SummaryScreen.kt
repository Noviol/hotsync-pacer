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
import fit.hotsync.pacer.upload.UploadQueue
import fit.hotsync.pacer.workout.Format

/** Summary: totals + per-segment breakdown. Upload to HotSync arrives with sync. */
@Composable
fun SummaryScreen(onDone: () -> Unit) {
    val ui by WorkoutSession.ui.collectAsStateWithLifecycle()
    val snap = ui.snapshot
    val completed = WorkoutSession.completed
    val uploads by UploadQueue.items.collectAsStateWithLifecycle()
    val upload = ui.uploadId?.let { uploads[it] }
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.crownScroll(listState)) {
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
            item {
                val (text, color) = when (upload?.state) {
                    null -> (if (ui.uploadId == null) "Nothing recorded to upload" else "Saving…") to HsColors.Dim
                    UploadQueue.State.WAITING -> "Strava: ${upload.message ?: "waiting"}" to HsColors.Orange
                    UploadQueue.State.UPLOADING -> "Strava: uploading…" to HsColors.Orange
                    UploadQueue.State.DONE -> "✓ ${upload.message ?: "On Strava"}" to HsColors.Green
                    UploadQueue.State.FAILED -> "Upload failed: ${upload.message}" to HsColors.Red
                }
                Text(text, color = color, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
