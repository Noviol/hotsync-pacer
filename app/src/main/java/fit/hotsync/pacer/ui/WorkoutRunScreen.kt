package fit.hotsync.pacer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.Text
import fit.hotsync.pacer.WorkoutSession
import fit.hotsync.pacer.workout.Duration
import fit.hotsync.pacer.workout.Format
import fit.hotsync.pacer.workout.TargetMode
import fit.hotsync.pacer.workout.TargetStatus
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private const val PAGE_COUNT = 2  // 0 = workout gauge, 1 = controls (data pages come later)

/**
 * The in-run screen. Swipe left/right between pages. In ambient mode only the
 * gauge page is drawn, without colour.
 */
@Composable
fun WorkoutRunScreen(ambient: Boolean, onEnded: () -> Unit, onDiscard: () -> Unit) {
    val ui by WorkoutSession.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.ended) { if (ui.ended) onEnded() }

    val pager = rememberPagerState { PAGE_COUNT }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(HsColors.Background)) {
        if (ambient) {
            GaugePage(ui, ambient = true)
        } else {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> GaugePage(ui, ambient = false)
                    else -> ControlsPage(
                        ui,
                        onDiscard = onDiscard,
                        backToMain = { scope.launch { pager.animateScrollToPage(0) } },
                    )
                }
            }
            HorizontalPageIndicator(
                pageIndicatorState = pager.asIndicatorState(),
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}

private fun PagerState.asIndicatorState() = object : PageIndicatorState {
    override val pageOffset: Float get() = currentPageOffsetFraction
    override val selectedPage: Int get() = currentPage
    override val pageCount: Int get() = PAGE_COUNT
}

// ----------------------------------------------------------------------------
// Page 0: gauge + stacked big numbers + reserved alert bubble
//
//            ╭── gauge ──╮
//              ♥ 152              <- HR
//             5:48 /km            <- pace      (pace workouts: pace first, HR last)
//             4:12 left           <- time/distance left in the segment
//            Warm-up  1/3         <- segment + set
//          [ HR HIGH ↑ ]          <- reserved alert bubble
// Each number gets its own full-width row so it can be large.
// ----------------------------------------------------------------------------

@Composable
private fun GaugePage(ui: WorkoutSession.Ui, ambient: Boolean) {
    val snap = ui.snapshot ?: return
    val workout = ui.workout ?: return
    val hrMode = workout.mode == TargetMode.HR
    val dim = if (ambient) Color.Gray else HsColors.Dim
    val heart = if (ambient) dim else HsColors.Red
    val segment = snap.segment

    // Time/distance left in the segment, or elapsed for open segments
    val (leftValue, leftUnit) = when (segment?.duration) {
        is Duration.Time -> Format.countdown(snap.remainingMs ?: 0) to "left"
        is Duration.Distance -> {
            val m = (snap.remainingM ?: 0.0).coerceAtLeast(0.0)
            if (m < 1000) "${m.toInt()}" to "m left" else "%.2f".format(m / 1000) to "km left"
        }
        Duration.Open -> Format.duration(snap.segmentElapsedMs) to "lap"
        null -> Format.duration(snap.totalActiveMs) to "total"
    }
    val hr = ui.heartRate?.toString() ?: "--"
    val pace = Format.pace(ui.paceSecPerKm)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        WorkoutGauge(position = ui.gauge, ambient = ambient, hasTarget = segment?.target != null)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(top = 26.dp, bottom = 10.dp, start = 12.dp, end = 12.dp),
        ) {
            if (hrMode) {
                MetricRow(hr, prefix = "♥", prefixColor = heart, unit = null, size = 40, dim = dim)
                MetricRow(pace, prefix = null, prefixColor = dim, unit = "/km", size = 46, dim = dim)
                MetricRow(leftValue, prefix = null, prefixColor = dim, unit = leftUnit, size = 46, dim = dim)
            } else {
                MetricRow(pace, prefix = null, prefixColor = dim, unit = "/km", size = 46, dim = dim)
                MetricRow(leftValue, prefix = null, prefixColor = dim, unit = leftUnit, size = 46, dim = dim)
                MetricRow(hr, prefix = "♥", prefixColor = heart, unit = null, size = 30, dim = dim)
            }

            // Segment name + set count (or zone)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    segment?.label ?: "Complete",
                    color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false),
                )
                val sub = segment?.setText ?: segment?.target?.zoneName
                if (sub != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(sub, color = dim, fontSize = 14.sp, maxLines = 1)
                }
            }

            Spacer(Modifier.weight(1f))

            // Reserved alert bubble: the space is always kept so the layout never shifts
            AlertBubble(ui, ambient)
        }
    }
}

/** One big number with an optional small prefix (♥) and unit ("/km", "left"). */
@Composable
private fun MetricRow(value: String, prefix: String?, prefixColor: Color, unit: String?, size: Int, dim: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        if (prefix != null) {
            Text(prefix, color = prefixColor, fontSize = (size * 0.5).sp, modifier = Modifier.padding(bottom = (size * 0.12).dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            value, color = Color.White, fontSize = size.sp, fontWeight = FontWeight.Black,
            lineHeight = (size + 2).sp, maxLines = 1,
        )
        if (unit != null) {
            Spacer(Modifier.width(4.dp))
            Text(unit, color = dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = (size * 0.14).dp))
        }
    }
}

@Composable
private fun AlertBubble(ui: WorkoutSession.Ui, ambient: Boolean) {
    val snap = ui.snapshot
    val hrMode = ui.workout?.mode == TargetMode.HR
    val (text, color) = when {
        snap?.isPaused == true -> "PAUSED" to HsColors.Orange
        snap?.isComplete == true -> "DONE · SWIPE TO SAVE" to HsColors.Green
        ui.alerts.visual && ui.alert == TargetStatus.ABOVE ->
            (if (hrMode) "HR HIGH ↑" else "PACE FAST ↑") to HsColors.Red
        ui.alerts.visual && ui.alert == TargetStatus.BELOW ->
            (if (hrMode) "HR LOW ↓" else "PACE SLOW ↓") to HsColors.Red
        else -> null to Color.Transparent
    }
    Box(
        modifier = Modifier
            .height(24.dp)
            .background(if (text == null || ambient) Color.Transparent else HsColors.Bubble, RoundedCornerShape(13.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) {
            Text(text, color = if (ambient) Color.White else color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Arc across the top of the screen: red (below) | green (target) | red (above),
 * with a pointer showing where the current reading sits. Zones are never shown here.
 */
@Composable
private fun WorkoutGauge(position: Double?, ambient: Boolean, hasTarget: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val strokeWidth = 9.dp.toPx()
        val inset = strokeWidth / 2 + 3.dp.toPx()
        val arcSize = Size(size.minDimension - 2 * inset, size.minDimension - 2 * inset)
        val topLeft = Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)

        val start = 195f
        val sweep = 150f
        val third = sweep / 3
        val gap = 2f

        if (!hasTarget) {
            drawArc(HsColors.Track, start, sweep, false, topLeft, arcSize, style = Stroke(strokeWidth / 3))
        } else {
            // Always-on (wrist turned away): same gauge, thinner and dimmer to protect the screen
            val w = if (ambient) strokeWidth / 2 else strokeWidth
            val a = if (ambient) 0.6f else 1f
            drawArc(HsColors.Red.copy(alpha = a), start, third - gap, false, topLeft, arcSize, style = Stroke(w))
            drawArc(HsColors.Green.copy(alpha = a), start + third + gap / 2, third - gap, false, topLeft, arcSize, style = Stroke(w))
            drawArc(HsColors.Red.copy(alpha = a), start + 2 * third + gap, third - gap, false, topLeft, arcSize, style = Stroke(w))
        }

        if (position != null && hasTarget) {
            val angle = Math.toRadians((start + sweep * position).toDouble())
            val radius = arcSize.width / 2
            val center = Offset(size.width / 2, size.height / 2)
            val point = Offset(
                center.x + (radius * cos(angle)).toFloat(),
                center.y + (radius * sin(angle)).toFloat(),
            )
            drawCircle(Color.Black, radius = strokeWidth * 0.95f, center = point)
            drawCircle(Color.White, radius = strokeWidth * 0.7f, center = point)
        }
    }
}

// ----------------------------------------------------------------------------
// Page 1: controls
// ----------------------------------------------------------------------------

/**
 * Running: Pause and Lap. Paused: Resume, Save (ends -> summary) and Discard.
 * Saving only from pause means a stray tap can never end a run.
 */
@Composable
private fun ControlsPage(ui: WorkoutSession.Ui, onDiscard: () -> Unit, backToMain: () -> Unit) {
    val snap = ui.snapshot
    val paused = snap?.isPaused == true
    val complete = snap?.isComplete == true
    val listState = rememberScalingLazyListState()
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(paused) { confirmDiscard = false }

    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        if (!paused) {
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { WorkoutSession.togglePause() },
                    label = { Text("Pause", fontWeight = FontWeight.Bold) },
                    secondaryLabel = { Text(if (complete) "Then save your workout" else "Resume or save next") },
                    colors = ChipDefaults.primaryChipColors(
                        backgroundColor = if (complete) HsColors.Green else HsColors.Orange,
                    ),
                )
            }
            if (!complete) {
                item {
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { WorkoutSession.lap(); backToMain() },
                        label = { Text("Lap") },
                        secondaryLabel = { Text(snap?.next?.let { "Next: ${it.label}" } ?: "End segment") },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
        } else {
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { WorkoutSession.togglePause(); backToMain() },
                    label = { Text("Resume", fontWeight = FontWeight.Bold) },
                    colors = ChipDefaults.primaryChipColors(backgroundColor = HsColors.Green),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { WorkoutSession.end() },
                    label = { Text("Save", fontWeight = FontWeight.Bold) },
                    secondaryLabel = { Text("End workout") },
                    colors = ChipDefaults.primaryChipColors(backgroundColor = HsColors.Orange),
                )
            }
            item {
                // Two taps, so a stray touch can't throw a run away
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { if (confirmDiscard) onDiscard() else confirmDiscard = true },
                    label = { Text(if (confirmDiscard) "Tap again to discard" else "Discard") },
                    colors = ChipDefaults.primaryChipColors(backgroundColor = HsColors.Red),
                )
            }
        }
        item { AlertToggle("Haptic", ui.alerts.haptic) { WorkoutSession.setAlerts(ui.alerts.copy(haptic = it)) } }
        item { AlertToggle("Sound", ui.alerts.sound) { WorkoutSession.setAlerts(ui.alerts.copy(sound = it)) } }
        item { AlertToggle("Visual", ui.alerts.visual) { WorkoutSession.setAlerts(ui.alerts.copy(visual = it)) } }
    }
}
