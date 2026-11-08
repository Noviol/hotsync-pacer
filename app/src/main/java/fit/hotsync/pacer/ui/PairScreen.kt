package fit.hotsync.pacer.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Text
import fit.hotsync.pacer.sync.HotSyncApi
import fit.hotsync.pacer.sync.SyncRepository
import kotlinx.coroutines.delay

/**
 * Pairing: the watch shows a code, the user types it into the Watch card on
 * hotsync.fit (Workouts page), and the watch polls until it receives its token.
 */
@Composable
fun PairScreen(onPaired: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        code = null
        message = null
        try {
            val start = SyncRepository.api.pairStart(Build.MODEL ?: "Watch")
            code = start.code
            while (true) {
                delay(start.pollSeconds * 1000L)
                val token = try {
                    SyncRepository.api.pairPoll(start.deviceId, start.secret)
                } catch (e: HotSyncApi.HttpError) {
                    if (e.code == 404 || e.code == 410) {
                        code = null
                        message = "Code expired"
                        return@LaunchedEffect
                    }
                    null // Server hiccup: keep polling
                } catch (e: Exception) {
                    null // Connection blip: keep polling
                }
                if (token != null) {
                    syncing = true
                    SyncRepository.savePairing(token)
                    onPaired()
                    return@LaunchedEffect
                }
            }
        } catch (e: Exception) {
            message = "Couldn't reach HotSync. Check Wi-Fi or phone connection."
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            syncing -> {
                CircularProgressIndicator()
                Text("Paired! Syncing…", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            }
            code != null -> {
                Text("hotsync.fit → Workouts → Watch", color = HsColors.Dim, fontSize = 12.sp, textAlign = TextAlign.Center)
                Text(
                    code!!.chunked(3).joinToString(" "),
                    fontSize = 40.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace,
                    color = HsColors.Orange, modifier = Modifier.padding(vertical = 6.dp),
                )
                Text("Enter this code on the website", fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            message != null -> {
                Text(message!!, fontSize = 14.sp, textAlign = TextAlign.Center)
                Chip(
                    onClick = { attempt++ },
                    label = { Text("Try again") },
                    colors = ChipDefaults.primaryChipColors(backgroundColor = HsColors.Orange),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            else -> CircularProgressIndicator()
        }
    }
}
