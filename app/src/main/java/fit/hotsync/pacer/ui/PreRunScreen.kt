package fit.hotsync.pacer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import fit.hotsync.pacer.sensors.ExerciseSensors
import fit.hotsync.pacer.workout.Format
import fit.hotsync.pacer.workout.TargetMode

/** Permissions a run needs. Heart rate moved to a health permission in Android 16 (API 36). */
fun runPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACTIVITY_RECOGNITION)
    add(Manifest.permission.POST_NOTIFICATIONS)
    add(if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS)
}.toTypedArray()

/**
 * Pre-run: workout preview, GPS/HR lock (sensors warm up here), alert toggles
 * (website defaults pre-filled), Start. You can start before GPS locks; distance
 * then begins once it does.
 */
@Composable
fun PreRunScreen(onStart: () -> Unit) {
    val context = LocalContext.current
    val ui by WorkoutSession.ui.collectAsStateWithLifecycle()
    val sensors by ExerciseSensors.status.collectAsStateWithLifecycle()
    val workout = ui.workout ?: return
    val listState = rememberScalingLazyListState()
    var permissionsAsked by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsAsked = true
    }
    LaunchedEffect(Unit) { launcher.launch(runPermissions()) }
    LaunchedEffect(permissionsAsked) {
        if (permissionsAsked) {
            val gps = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            ExerciseSensors.prepare(gpsAllowed = gps)
        }
    }

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
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LockText("GPS", sensors.gps, null)
                    LockText("♥", sensors.hr, sensors.heartRate?.toString())
                }
            }
            sensors.error?.let { err ->
                item { Text(err, color = HsColors.Red, fontSize = 12.sp, textAlign = TextAlign.Center) }
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onStart,
                    label = { Text("Start", fontWeight = FontWeight.Bold) },
                    secondaryLabel = if (sensors.gps == ExerciseSensors.Lock.SEARCHING) {
                        { Text("GPS still searching") }
                    } else null,
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
private fun LockText(label: String, lock: ExerciseSensors.Lock, value: String?) {
    val (text, color) = when (lock) {
        ExerciseSensors.Lock.READY -> (value ?: "✓") to HsColors.Green
        ExerciseSensors.Lock.SEARCHING -> "…" to HsColors.Orange
        ExerciseSensors.Lock.UNAVAILABLE -> "✕" to HsColors.Red
        ExerciseSensors.Lock.OFF -> "–" to HsColors.Dim
    }
    Row {
        Text("$label ", fontSize = 14.sp, color = Color.White)
        Text(text, fontSize = 14.sp, color = color, fontWeight = FontWeight.Bold)
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
