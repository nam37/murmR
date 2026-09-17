package dev.murmr.app.ui.instrument

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.murmr.app.R
import dev.murmr.app.macros.HostOs
import dev.murmr.app.macros.Macro
import kotlinx.coroutines.delay

/**
 * A square metal keycap: the two approved masters (idle, lit) cross-fading, with symbol and
 * caption as live layers on top. Tap sends; long-press edits (and works even when sending is
 * disabled, since assignment does not need the computer). A tap lights the key for a moment.
 *
 * With [interactive] false it is a static preview (the editor's WYSIWYG keycap); [lit] then
 * forces the pressed art, used to show a selected choice.
 */
@Composable
fun MacroKey(
    macro: Macro,
    os: HostOs?,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    enabled: Boolean = true,
    interactive: Boolean = true,
    lit: Boolean = false,
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
) {
    val currentTap by rememberUpdatedState(onTap)
    val currentLongPress by rememberUpdatedState(onLongPress)
    val currentEnabled by rememberUpdatedState(enabled)
    val haptics = LocalHapticFeedback.current

    var pressed by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(flash) {
        if (flash) {
            delay(FLASH_MS)
            flash = false
        }
    }
    val isLit = lit || pressed || flash
    val litAlpha by animateFloatAsState(if (isLit) 1f else 0f, tween(100), label = "keyArt")
    val desaturated = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.3f) }) }
    val artFilter = if (enabled || !interactive) null else desaturated
    val pressPx = with(LocalDensity.current) { 1.dp.toPx() }
    val scale = size / 72.dp

    Box(
        modifier
            .size(size)
            .alpha(if (enabled || !interactive) 1f else 0.45f)
            .then(
                if (interactive) {
                    // Keyed on Unit so an enabled/disabled flip mid-gesture never restarts it.
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                if (currentEnabled) pressed = true
                                tryAwaitRelease()
                                pressed = false
                            },
                            onTap = {
                                if (currentEnabled) {
                                    flash = true
                                    currentTap()
                                }
                            },
                            onLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                currentLongPress()
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.art_macro_idle),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().alpha(1f - litAlpha),
            colorFilter = artFilter,
        )
        Image(
            painter = painterResource(R.drawable.art_macro_pressed),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().alpha(litAlpha),
            colorFilter = artFilter,
        )
        if (macro.isAssigned) {
            Column(
                modifier = Modifier.graphicsLayer { if (isLit) translationY = pressPx },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(1.dp * scale),
            ) {
                val shadow = Shadow(Palette.buttonTextShadow, Offset(0f, 1f), 0f)
                Text(
                    text = macro.symbol(os),
                    color = Palette.keyText,
                    fontSize = 22.sp * scale,
                    lineHeight = 23.sp * scale,
                    fontWeight = FontWeight.Medium,
                    style = TextStyle(shadow = shadow),
                )
                Text(
                    text = macro.caption,
                    color = Palette.keyText,
                    fontSize = 9.sp * scale,
                    lineHeight = 11.sp * scale,
                    letterSpacing = 0.7.sp,
                    fontWeight = FontWeight.Medium,
                    style = TextStyle(shadow = shadow),
                )
            }
        }
    }
}

/** How long a tapped key stays lit, matching the mockup. */
private const val FLASH_MS = 220L
