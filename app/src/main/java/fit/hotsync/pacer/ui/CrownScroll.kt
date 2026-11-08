package fit.hotsync.pacer.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import kotlinx.coroutines.launch

/** Lets the crown scroll a list. Apply to each screen's ScalingLazyColumn. */
@Composable
fun Modifier.crownScroll(state: ScalingLazyListState): Modifier {
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    return this
        .onRotaryScrollEvent {
            scope.launch { state.scrollBy(it.verticalScrollPixels) }
            true
        }
        .focusRequester(focus)
        .focusable()
}
