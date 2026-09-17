package dev.murmr.app.ui.instrument

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.murmr.app.R
import dev.murmr.app.service.PttPhase

/**
 * The push-to-talk control: the two approved button masters cross-fading (idle and lit), with
 * the microphone glyph, ready lamp and label as live layers on top, per the asset pack.
 */
@Composable
fun TalkButton(
    phase: PttPhase,
    enabled: Boolean,
    onDown: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listening = phase == PttPhase.LISTENING
    val currentDown by rememberUpdatedState(onDown)
    val currentUp by rememberUpdatedState(onUp)
    val currentEnabled by rememberUpdatedState(enabled)
    val litAlpha by animateFloatAsState(if (listening) 1f else 0f, tween(100), label = "talkArt")
    val desaturated = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.25f) }) }
    val artFilter = if (enabled) null else desaturated
    val pressPx = with(LocalDensity.current) { 2.dp.toPx() }

    Box(
        modifier
            .size(250.dp)
            // Hit-test as a circle, not the square bounds: the keycaps sit at the corners of the
            // same cluster and a tap near the ring must land on them, not here (the mockup's
            // clip-path). The art is circular, so nothing visible changes.
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.65f)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        // Enabled is checked at press time, not used as the gesture key: keying
                        // on it restarted the gesture when the link dropped mid-hold, which
                        // released the press and ended the dictation.
                        if (!currentEnabled) return@detectTapGestures
                        currentDown()
                        tryAwaitRelease()
                        currentUp()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.art_talk_idle),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().alpha(1f - litAlpha),
            colorFilter = artFilter,
        )
        Image(
            painter = painterResource(R.drawable.art_talk_active),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().alpha(litAlpha),
            colorFilter = artFilter,
        )
        Column(
            modifier = Modifier.graphicsLayer {
                if (listening) {
                    translationY = pressPx
                    scaleX = 0.98f
                    scaleY = 0.98f
                }
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MicGlyph(ready = enabled, listening = listening)
            Text(
                text = "HOLD TO TALK",
                color = Palette.buttonText,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Medium,
                style = TextStyle(shadow = Shadow(Palette.buttonTextShadow, Offset(0f, 1f), 0f)),
            )
        }
    }
}

/** Outline microphone with the ready lamp glowing through the capsule. */
@Composable
private fun MicGlyph(ready: Boolean, listening: Boolean) {
    val lamp = when {
        !ready -> Palette.lampOff
        listening -> Palette.lampListening
        else -> Palette.lampReady
    }
    Box(Modifier.size(52.dp)) {
        Box(
            Modifier
                .offset(x = 20.dp, y = 6.dp)
                .size(width = 12.dp, height = 24.dp)
                .drawBehind {
                    if (ready) {
                        val pad = (if (listening) 5.dp else 3.dp).toPx()
                        drawRoundRect(
                            color = lamp.copy(alpha = 0.55f),
                            topLeft = Offset(-pad, -pad),
                            size = Size(size.width + pad * 2, size.height + pad * 2),
                            cornerRadius = CornerRadius(10.dp.toPx()),
                        )
                    }
                    drawRoundRect(lamp, cornerRadius = CornerRadius(7.dp.toPx()))
                },
        )
        Icon(
            painter = painterResource(R.drawable.ic_mic),
            contentDescription = null,
            tint = Palette.buttonText,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
