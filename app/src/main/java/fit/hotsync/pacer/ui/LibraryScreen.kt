package fit.hotsync.pacer.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import fit.hotsync.pacer.workout.Format
import fit.hotsync.pacer.workout.TargetMode
import fit.hotsync.pacer.workout.Workout

/** Every workout in the website library; pick one to run it any day. */
@Composable
fun LibraryScreen(workouts: List<Workout>, onPick: (Workout) -> Unit) {
    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState, modifier = Modifier.crownScroll(listState)) {
            item { ListHeader { Text("Library") } }
            items(workouts) { w ->
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPick(w) },
                    label = { Text(w.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = {
                        Text("${if (w.mode == TargetMode.HR) "HR" else "Pace"} · ${Format.total(w)}", maxLines = 1)
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}
