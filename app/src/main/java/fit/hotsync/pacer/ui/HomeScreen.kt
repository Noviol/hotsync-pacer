package fit.hotsync.pacer.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.compose.foundation.layout.fillMaxWidth
import fit.hotsync.pacer.PacerEngine
import fit.hotsync.pacer.workout.Workout

/** Home: pick a mode. More entries (Library, Free Run, Settings) arrive with later steps. */
@Composable
fun HomeScreen(todaysWorkout: Workout, onWorkout: () -> Unit, onRace: () -> Unit) {
    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(state = listState) {
            item { ListHeader { Text("HotSync") } }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onWorkout,
                    label = { Text("Today's Workout") },
                    secondaryLabel = {
                        Text(todaysWorkout.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    colors = ChipDefaults.primaryChipColors(backgroundColor = HsColors.Orange),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRace,
                    label = { Text("Race Pacer") },
                    secondaryLabel = {
                        Text("${PacerEngine.TARGET_DISTANCE} markers · ${PacerEngine.TARGET_TIME_MINUTES.toInt()} min")
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}
