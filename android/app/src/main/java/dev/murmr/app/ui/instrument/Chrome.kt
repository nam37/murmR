package dev.murmr.app.ui.instrument

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.murmr.app.R
import dev.murmr.app.hid.HidKeyboard
import kotlin.math.roundToInt

/** Brand on the left, the status pill on the right. */
@Composable
fun Header(
    hid: HidKeyboard.State,
    onOpenConnection: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 5.dp)
            .heightIn(min = 54.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Brand()
        StatusPill(hid, onOpenConnection, onOpenSettings)
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // Mark and wordmark share the chassis off-white: the teal mark lacked contrast on the
        // phone. Sizes are the mockup's less 20 percent, which read too large on a Pixel 11 Pro.
        Image(
            painter = painterResource(R.drawable.murmr_mark),
            contentDescription = "murmr",
            modifier = Modifier.size(width = 52.dp, height = 27.dp),
            contentScale = ContentScale.Fit,
            colorFilter = ColorFilter.tint(Palette.wordmark, BlendMode.SrcIn),
        )
        Wordmark(Modifier.size(width = 96.dp, height = 18.dp))
    }
}

/** The wordmark region of the logo, recoloured for the chassis as the mockup does. */
@Composable
private fun Wordmark(modifier: Modifier) {
    val logo = ImageBitmap.imageResource(R.drawable.murmr_logo)
    Canvas(modifier) {
        drawImage(
            image = logo,
            srcOffset = IntOffset(98, 838),
            srcSize = IntSize(1076, 193),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            colorFilter = ColorFilter.tint(Palette.wordmark, BlendMode.SrcIn),
        )
    }
}

/**
 * One line: LED, connection state, a divider, the settings gear. The state is what gets
 * glanced at; the computer's name is one tap away in the connection sheet. Two tap targets in
 * one piece of chrome, so the header stays uncrowded and the screen needs no footer.
 */
@Composable
private fun StatusPill(hid: HidKeyboard.State, onOpenConnection: () -> Unit, onOpenSettings: () -> Unit) {
    val connected = hid is HidKeyboard.State.Connected
    val stateText = when (hid) {
        is HidKeyboard.State.Connected -> "Connected"
        is HidKeyboard.State.Connecting -> "Connecting"
        HidKeyboard.State.Registered -> "Disconnected"
        is HidKeyboard.State.Unavailable -> "Unavailable"
        HidKeyboard.State.Starting -> "Starting"
        HidKeyboard.State.Unregistered -> "Not ready"
    }
    val radius = 21.dp
    Row(
        Modifier
            .clip(RoundedCornerShape(radius))
            .drawBehind {
                // The pill is the one chrome element the mockup itself styles in CSS rather than
                // paints, so it is reproduced from that rule: fill, dark border, inset top
                // shadow, one-pixel lower highlight.
                val r = CornerRadius(radius.toPx())
                drawRoundRect(Palette.pillBg, cornerRadius = r)
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0f to Palette.pillShadow.copy(alpha = 0.7f),
                        1f to Color.Transparent,
                        endY = 4.dp.toPx(),
                    ),
                    cornerRadius = r,
                )
                drawRoundRect(Palette.pillBorder, cornerRadius = r, style = Stroke(1.dp.toPx()))
                drawLine(
                    color = Palette.pillHighlight.copy(alpha = 0.55f),
                    start = Offset(radius.toPx(), size.height - 1f),
                    end = Offset(size.width - radius.toPx(), size.height - 1f),
                    strokeWidth = 1f,
                )
            }
            .height(42.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .fillMaxHeight()
                .clickable(onClick = onOpenConnection)
                .padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Led(on = connected, size = 12.dp)
            Text(
                text = stateText,
                color = if (connected) Palette.pillState else Palette.pillText,
                fontSize = 12.sp,
                letterSpacing = 0.7.sp,
            )
        }
        // Divider engraved the same way as the rest of the chrome: dark line, light line beside it.
        Box(Modifier.width(1.dp).height(22.dp).background(Palette.pillBorder))
        Box(Modifier.width(1.dp).height(22.dp).background(Palette.pillHighlight.copy(alpha = 0.35f)))
        Box(
            Modifier
                .fillMaxHeight()
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Settings",
                tint = Palette.pillText,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun Led(on: Boolean, size: Dp) {
    Canvas(Modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        if (on) drawCircle(Palette.ledGlow.copy(alpha = 0.45f), radius = radius + 3.dp.toPx())
        drawCircle(if (on) Palette.ledOn else Palette.ledOff, radius = radius)
    }
}
