package fit.hotsync.pacer.ui

import androidx.compose.ui.graphics.Color

/** Shared colours. Black backgrounds throughout (AMOLED: saves battery, reads well outdoors). */
object HsColors {
    val Background = Color.Black
    val Orange = Color(0xFFFC4C02)
    val Green = Color(0xFF30D158)
    val Red = Color(0xFFFF453A)
    val Dim = Color(0xFF8E8E93)
    /** Secondary text during a run: bright enough to read at a glance while moving (never Dim). */
    val Light = Color(0xFFD9D9D9)
    val Track = Color(0xFF2C2C2E)
    val Bubble = Color(0xFF1C1C1E)
}
