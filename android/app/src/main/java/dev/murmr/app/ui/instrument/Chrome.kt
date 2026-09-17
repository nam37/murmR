package dev.murmr.app.ui.instrument

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.murmr.app.R
import dev.murmr.app.hid.HidKeyboard
import kotlin.math.roundToInt

/** Brand on the left, computer connection pill on the right. */
@Composable
fun Header(
    hid: HidKeyboard.State,
    lastHost: String?,
    onOpenConnection: () -> Unit,
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
        StatusPill(hid, lastHost, onOpenConnection)
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(
            painter = painterResource(R.drawable.murmr_mark),
            contentDescription = "murmr",
            modifier = Modifier.size(width = 65.dp, height = 34.dp),
            contentScale = ContentScale.Fit,
        )
        Wordmark(Modifier.size(width = 120.dp, height = 22.dp))
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

@Composable
private fun StatusPill(hid: HidKeyboard.State, lastHost: String?, onClick: () -> Unit) {
    val connected = hid is HidKeyboard.State.Connected
    val host = when (hid) {
        is HidKeyboard.State.Connected -> hid.hostName
        is HidKeyboard.State.Connecting -> hid.hostName
        else -> lastHost ?: "No computer"
    }
    val stateText = when (hid) {
        is HidKeyboard.State.Connected -> "Connected"
        is HidKeyboard.State.Connecting -> "Connecting"
        HidKeyboard.State.Registered -> "Disconnected"
        is HidKeyboard.State.Unavailable -> "Unavailable"
        HidKeyboard.State.Starting -> "Starting"
        HidKeyboard.State.Unregistered -> "Not ready"
    }
    val shape = RoundedCornerShape(19.dp)
    Row(
        Modifier
            .clip(shape)
            .clickable(onClick = onClick)
            .drawBehind {
                // The pill is the one chrome element the mockup itself styles in CSS rather than
                // paints, so it is reproduced from that rule: fill, dark border, inset top
                // shadow, one-pixel lower highlight.
                val r = CornerRadius(19.dp.toPx())
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
                    start = Offset(19.dp.toPx(), size.height - 1f),
                    end = Offset(size.width - 19.dp.toPx(), size.height - 1f),
                    strokeWidth = 1f,
                )
            }
            .heightIn(min = 55.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Led(on = connected, size = 14.dp)
        Column {
            Text(
                text = host,
                color = Palette.pillText,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 1.3.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stateText,
                color = Palette.pillState,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                letterSpacing = 0.7.sp,
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
