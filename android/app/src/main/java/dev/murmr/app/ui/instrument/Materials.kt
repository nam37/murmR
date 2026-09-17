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
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.murmr.app.R
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * Surfaces are the approved artwork, not drawings of it (see CLAUDE.md, "Visual target").
 * ORIGINAL variants are the mockup's own assets. The other variants are the production-assets-v1
 * candidates, offered for on-phone side-by-side comparison as that pack's README asks. Nothing
 * here paints a material procedurally.
 */

/** Which artwork paints the chassis. */
enum class ChassisArt {
    /** Approved mockup chassis image, crop-to-cover. */
    ORIGINAL,

    /** Candidate: uniform brushed tile with mirrored addressing, plus the soft lighting overlay. */
    TILED_METAL,
}

/** Which artwork paints the glass panels. Both are nine-sliced so the rim never distorts. */
enum class GlassArt {
    /** Approved mockup panel image. */
    ORIGINAL,

    /** Candidate: the production-assets-v1 blank recessed panel. */
    CANDIDATE,
}

/**
 * Nine-slice geometry for one panel image: source boundaries in image pixels (right and bottom
 * exclusive) and the on-screen size of the corner regions. Only the flat interior stretches.
 */
private class NineSlice(val sx: IntArray, val sy: IntArray, val cornerW: Dp, val cornerH: Dp)

/**
 * Original glass: boundaries verified against the pixels (rim 32-57 px, corners 77-90 px along
 * the edges); these sit comfortably inside the flat interior. 140 px source corners at 28 dp.
 */
private val ORIGINAL_GLASS = NineSlice(
    sx = intArrayOf(22, 162, 1400, 1540),
    sy = intArrayOf(98, 238, 750, 890),
    cornerW = 28.dp,
    cornerH = 28.dp,
)

/** Candidate glass: boundaries from design/production-assets-v1/README.md. */
private val CANDIDATE_GLASS = NineSlice(
    sx = intArrayOf(8, 148, 1300, 1440),
    sy = intArrayOf(88, 218, 860, 990),
    cornerW = 28.dp,
    cornerH = 26.dp,
)

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
        GlassArt.CANDIDATE -> ImageBitmap.imageResource(R.drawable.art_glass_empty)
    }
    val slices = when (art) {
        GlassArt.ORIGINAL -> ORIGINAL_GLASS
        GlassArt.CANDIDATE -> CANDIDATE_GLASS
    }
    Column(
        modifier
            .drawBehind { drawNineSlice(image, slices) }
            .padding(contentPadding),
        content = content,
    )
}

/**
 * Draws the nine regions of [image] into this size: corners at their native proportion, edges
 * stretched along one axis, the interior along both. Corners shrink if the panel is smaller
 * than two of them, so nothing ever overlaps.
 */
private fun DrawScope.drawNineSlice(image: ImageBitmap, s: NineSlice) {
    val cw = s.cornerW.toPx().coerceAtMost(size.width / 2f)
    val ch = s.cornerH.toPx().coerceAtMost(size.height / 2f)
    val dx = intArrayOf(0, cw.roundToInt(), (size.width - cw).roundToInt(), size.width.roundToInt())
    val dy = intArrayOf(0, ch.roundToInt(), (size.height - ch).roundToInt(), size.height.roundToInt())
    for (y in 0 until 3) {
        for (x in 0 until 3) {
            drawImage(
                image,
                srcOffset = IntOffset(s.sx[x], s.sy[y]),
                srcSize = IntSize(s.sx[x + 1] - s.sx[x], s.sy[y + 1] - s.sy[y]),
                dstOffset = IntOffset(dx[x], dy[y]),
                dstSize = IntSize(dx[x + 1] - dx[x], dy[y + 1] - dy[y]),
            )
        }
    }
}
