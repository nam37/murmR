package dev.murmr.app.ui.instrument

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.murmr.app.R
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * Surfaces are the approved artwork, not drawings of it (see CLAUDE.md, "Visual target").
 * ORIGINAL variants are the mockup's own assets, presented the way the mockup presents them.
 * The other variants are the production-assets-v1 candidates, offered for on-phone side-by-side
 * comparison as that pack's README asks. Nothing here paints a material procedurally.
 */

/** Which artwork paints the chassis. */
enum class ChassisArt {
    /** Approved mockup chassis image, crop-to-cover. */
    ORIGINAL,

    /** Candidate: uniform brushed tile with mirrored addressing, plus the soft lighting overlay. */
    TILED_METAL,
}

/** Which artwork paints the glass panels. */
enum class GlassArt {
    /** Approved mockup panel image, stretched the way the mockup's stylesheet does. */
    ORIGINAL,

    /** Candidate: blank recessed panel, nine-sliced so corner geometry never distorts. */
    NINE_SLICE,
}

/** Full-screen brushed chassis behind everything else. */
@Composable
fun Chassis(art: ChassisArt, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Palette.chassisBase)) {
        when (art) {
            ChassisArt.ORIGINAL -> Image(
                painter = painterResource(R.drawable.art_chassis),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            ChassisArt.TILED_METAL -> {
                MetalTile(Modifier.fillMaxSize())
                // Asset pack guidance: composite gently, 10-18% opacity, never tiled.
                Image(
                    painter = painterResource(R.drawable.art_lighting),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().alpha(0.18f),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
        content()
    }
}

/**
 * Mirrored tiling: every other column is flipped horizontally and every other row vertically,
 * so opposing tile edges always meet their own reflection and no seam can show. The tile is
 * not seamless under plain repeat (measured in the asset pack), which is why.
 */
@Composable
private fun MetalTile(modifier: Modifier) {
    val tile = ImageBitmap.imageResource(R.drawable.art_metal_tile)
    Canvas(modifier) {
        val tilePx = TILE_SIZE.roundToPx()
        val cols = ceil(size.width / tilePx).toInt()
        val rows = ceil(size.height / tilePx).toInt()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val flipX = col % 2 == 1
                val flipY = row % 2 == 1
                withTransform({
                    translate(left = col * tilePx.toFloat(), top = row * tilePx.toFloat())
                    if (flipX || flipY) {
                        scale(
                            scaleX = if (flipX) -1f else 1f,
                            scaleY = if (flipY) -1f else 1f,
                            pivot = Offset(tilePx / 2f, tilePx / 2f),
                        )
                    }
                }) {
                    drawImage(tile, dstSize = IntSize(tilePx, tilePx))
                }
            }
        }
    }
}

/** Grain scale from the asset pack's preview; tune on the phone. */
private val TILE_SIZE = 220.dp

/** An inset glass display. [content] is laid out over the artwork with [contentPadding]. */
@Composable
fun GlassPanel(
    art: GlassArt,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val image = when (art) {
        GlassArt.ORIGINAL -> ImageBitmap.imageResource(R.drawable.art_glass_original)
        GlassArt.NINE_SLICE -> ImageBitmap.imageResource(R.drawable.art_glass_empty)
    }
    Column(
        modifier
            .drawBehind {
                when (art) {
                    GlassArt.ORIGINAL -> drawStretchedGlass(image)
                    GlassArt.NINE_SLICE -> drawNineSliceGlass(image)
                }
            }
            .padding(contentPadding),
        content = content,
    )
}

/** The mockup's stylesheet rule for the original panel: `center / 100% 125% no-repeat`. */
private fun DrawScope.drawStretchedGlass(image: ImageBitmap) {
    val h = (size.height * 1.25f).roundToInt()
    val top = ((size.height - h) / 2f).roundToInt()
    clipRect {
        drawImage(
            image,
            dstOffset = IntOffset(0, top),
            dstSize = IntSize(size.width.roundToInt(), h),
        )
    }
}

/**
 * Nine-slice per design/production-assets-v1/README.md: source X = [8, 148, 1300, 1440],
 * Y = [88, 218, 860, 990]; corners land at 28 x 26 logical pixels. Only the flat interior is
 * stretched; the painted rim and corners keep their proportions at any panel size.
 */
private fun DrawScope.drawNineSliceGlass(image: ImageBitmap) {
    val sx = intArrayOf(8, 148, 1300, 1440)
    val sy = intArrayOf(88, 218, 860, 990)
    val cw = 28.dp.toPx().coerceAtMost(size.width / 2f)
    val ch = 26.dp.toPx().coerceAtMost(size.height / 2f)
    val dx = intArrayOf(0, cw.roundToInt(), (size.width - cw).roundToInt(), size.width.roundToInt())
    val dy = intArrayOf(0, ch.roundToInt(), (size.height - ch).roundToInt(), size.height.roundToInt())
    for (y in 0 until 3) {
        for (x in 0 until 3) {
            drawImage(
                image,
                srcOffset = IntOffset(sx[x], sy[y]),
                srcSize = IntSize(sx[x + 1] - sx[x], sy[y + 1] - sy[y]),
                dstOffset = IntOffset(dx[x], dy[y]),
                dstSize = IntSize(dx[x + 1] - dx[x], dy[y + 1] - dy[y]),
            )
        }
    }
}
