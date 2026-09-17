package dev.murmr.app.ui.instrument

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.murmr.app.macros.HostOs
import dev.murmr.app.macros.Macro
import dev.murmr.app.service.PttPhase

/**
 * The control deck from the mockup: the talk button centred in a 282 dp cluster with four
 * keycaps pinned to its corners. Keys send only when a computer is connected and nothing is
 * being captured or sent, so keystrokes never interleave with dictation; long-press to edit
 * works regardless.
 */
@Composable
fun MacroCluster(
    phase: PttPhase,
    connected: Boolean,
    macros: List<Macro>,
    os: HostOs?,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    onMacro: (index: Int) -> Unit,
    onEditMacro: (index: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = phase == PttPhase.FINISHING || phase == PttPhase.TYPING || phase == PttPhase.ERASING
    val keysEnabled = connected && !busy && phase != PttPhase.LISTENING
    val corners = listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd)

    Box(modifier.fillMaxWidth().height(282.dp)) {
        TalkButton(
            phase = phase,
            enabled = connected && !busy,
            onDown = onPttDown,
            onUp = onPttUp,
            modifier = Modifier.align(Alignment.Center),
        )
        corners.forEachIndexed { index, corner ->
            val macro = macros.getOrNull(index) ?: return@forEachIndexed
            MacroKey(
                macro = macro,
                os = os,
                enabled = keysEnabled,
                onTap = { onMacro(index) },
                onLongPress = { onEditMacro(index) },
                modifier = Modifier.align(corner),
            )
        }
    }
}
