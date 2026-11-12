package fit.hotsync.pacer.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
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
import fit.hotsync.pacer.PacerEngine
import fit.hotsync.pacer.sync.ScheduleEntry
import fit.hotsync.pacer.sync.SyncRepository
import fit.hotsync.pacer.workout.Workout
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Home: today's workout(s) from the calendar, what's coming up, the library, and the race pacer. */
@Composable
fun HomeScreen(
    state: SyncRepository.State,
    onPair: () -> Unit,
    onScheduled: (ScheduleEntry, Workout) -> Unit,
    onLibrary: () -> Unit,
    onRace: () -> Unit,
    onSync: () -> Unit,
    onUnpair: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    val today = LocalDate.now()
    val data = state.data
    val todays = data?.linkedOn(today).orEmpty()
    val upcoming = (1L..6L).flatMap { data?.linkedOn(today.plusDays(it)).orEmpty() }
    var confirmUnpair by remember { mutableStateOf(false) }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.crownScroll(listState),
            autoCentering = AutoCenteringParams(itemIndex = 1),
        ) {
            item { ListHeader { Text("HotSync") } }

            if (!state.paired) {
                item {
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onPair,
                        label = { Text("Pair with HotSync") },
                        secondaryLabel = { Text("Get your workouts") },
                        colors = ChipDefaults.primaryChipColors(backgroundColor = HsColors.Orange),
                    )
                }
            } else {
                if (todays.isEmpty()) {
                    item {
                        Text(
                            if (data == null) "Syncing your workouts…" else "No workout linked today",
                            color = HsColors.Dim, fontSize = 13.sp, textAlign = TextAlign.Center,
                        )
                    }
                }
                items(todays) { (entry, workout) ->
                    WorkoutChip(workout, "Today · ${blockText(entry.block)}", primary = true) {
                        onScheduled(entry, workout)
                    }
                }
                if (upcoming.isNotEmpty()) {
                    item { ListHeader { Text("Coming up") } }
                    items(upcoming) { (entry, workout) ->
                        WorkoutChip(workout, "${dayText(entry.date, today)} · ${blockText(entry.block)}", primary = false) {
                            onScheduled(entry, workout)
                        }
                    }
                }
                if (!data?.workouts.isNullOrEmpty()) {
                    item {
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onLibrary,
                            label = { Text("Library") },
                            secondaryLabel = { Text("${data!!.workouts.size} workouts") },
                            colors = ChipDefaults.secondaryChipColors(),
                        )
                    }
                }
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

            if (state.paired) {
                item {
                    Text(
                        syncStatus(state), color = if (state.error != null) HsColors.Red else HsColors.Dim,
                        fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                item {
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onSync,
                        enabled = !state.syncing,
                        label = { Text("Sync now") },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
                item {
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { if (confirmUnpair) onUnpair() else confirmUnpair = true },
                        label = { Text(if (confirmUnpair) "Tap again to unpair" else "Unpair watch") },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            } else if (state.error != null) {
                item { Text(state.error, color = HsColors.Red, fontSize = 12.sp, textAlign = TextAlign.Center) }
            }
        }
    }
}

@Composable
private fun WorkoutChip(workout: Workout, secondary: String, primary: Boolean, onClick: () -> Unit) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        label = { Text(workout.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(secondary, maxLines = 1) },
        colors = if (primary) ChipDefaults.primaryChipColors(backgroundColor = HsColors.Orange)
        else ChipDefaults.secondaryChipColors(),
    )
}

private fun blockText(block: String) = if (block == "ALL_DAY") "All day" else block

private fun dayText(date: LocalDate, today: LocalDate): String =
    if (date == today.plusDays(1)) "Tomorrow"
    else date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

private fun syncStatus(state: SyncRepository.State): String {
    if (state.syncing) return "Syncing…"
    state.error?.let { return it }
    val at = state.lastSyncedAt ?: return "Not synced yet"
    val minutes = (System.currentTimeMillis() - at) / 60_000
    return when {
        minutes < 1 -> "Synced just now"
        minutes < 60 -> "Synced $minutes min ago"
        minutes < 24 * 60 -> "Synced ${minutes / 60} h ago"
        else -> "Synced ${minutes / (24 * 60)} d ago"
    }
}
