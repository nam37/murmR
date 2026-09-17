package dev.murmr.app.ui.instrument

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Bar waveform on a faint grid, as in the mockup's input panel. [level] is the live microphone
 * level (0..1); bars scroll left while [active] and decay when not. A live layer: the one place
 * the artwork cannot carry the information, so it is drawn.
 */
@Composable
fun Waveform(level: Float, active: Boolean, modifier: Modifier = Modifier) {
    val bars = remember { mutableStateListOf<Float>().apply { repeat(BAR_COUNT) { add(0f) } } }
    val currentLevel by rememberUpdatedState(level)

    LaunchedEffect(active) {
        while (isActive) {
            if (active) {
                bars.removeAt(0)
                bars.add(currentLevel)
            } else {
                var anyLeft = false
                for (i in bars.indices) {
                    val v = bars[i] * 0.8f
                    bars[i] = if (v < 0.01f) 0f else v
                    if (v >= 0.01f) anyLeft = true
                }
                if (!anyLeft) break
            }
            delay(FRAME_MS)
        }
    }

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val step = 12.dp.toPx()
        val hairline = 0.5.dp.toPx()
        var x = 0f
        while (x < w) {
            drawLine(Palette.grid, Offset(x, 0f), Offset(x, h), strokeWidth = hairline)
            x += step
        }
        var y = 0f
        while (y < h) {
            drawLine(Palette.grid, Offset(0f, y), Offset(w, y), strokeWidth = hairline)
            y += step
        }

        val gap = w / BAR_COUNT
        val barW = maxOf(1.5.dp.toPx(), gap * 0.56f)
        val minH = 2.dp.toPx()
        val color = if (active) Palette.waveActive else Palette.waveIdle
        val glowPad = 1.5.dp.toPx()
        bars.forEachIndexed { i, v ->
            val barH = maxOf(minH, v * h * 0.88f)
            val left = i * gap + 1.dp.toPx()
            val top = (h - barH) / 2f
            if (active) {
                drawRoundRect(
                    color = Palette.waveGlow.copy(alpha = 0.35f),
                    topLeft = Offset(left - glowPad, top - glowPad),
                    size = Size(barW + glowPad * 2, barH + glowPad * 2),
                    cornerRadius = CornerRadius(glowPad),
                )
            }
            drawRect(color, Offset(left, top), Size(barW, barH))
        }
    }
}

private const val BAR_COUNT = 61
private const val FRAME_MS = 35L
