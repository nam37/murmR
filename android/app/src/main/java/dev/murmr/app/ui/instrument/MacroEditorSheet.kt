package dev.murmr.app.ui.instrument

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.murmr.app.hid.TextTyper
import dev.murmr.app.macros.HostOs
import dev.murmr.app.macros.Keys
import dev.murmr.app.macros.Macro
import dev.murmr.app.macros.MacroAction
import dev.murmr.app.macros.Mod
import dev.murmr.app.macros.Preset
import dev.murmr.app.transport.KeyChord

private enum class Mode { KEY, TEXT }

/**
 * Assigns one keycap. Top to bottom: WYSIWYG preview, caption, mode, the assignment controls
 * for that mode, then Clear / Cancel / Save. Saving never sends anything.
 *
 * Key mode offers the common keys and the OS-resolved shortcuts as tappable keycaps, then a
 * picker for any other key with modifier toggles. Text mode types a snippet, optionally with
 * Enter after, and previews it exactly as the US-layout typer will deliver it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroEditorSheet(
    index: Int,
    initial: Macro,
    hostName: String,
    os: HostOs?,
    onSave: (Macro) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialAction = initial.action
    var mode by remember { mutableStateOf(if (initialAction is MacroAction.Text) Mode.TEXT else Mode.KEY) }
    var chord by remember { mutableStateOf((initialAction as? MacroAction.Key)?.chord) }
    var preset by remember { mutableStateOf((initialAction as? MacroAction.Shortcut)?.preset) }
    var text by remember { mutableStateOf((initialAction as? MacroAction.Text)?.text.orEmpty()) }
    var enterAfter by remember { mutableStateOf((initialAction as? MacroAction.Text)?.enterAfter ?: false) }
    var caption by remember { mutableStateOf(initial.caption) }
    var captionEdited by remember { mutableStateOf(initial.caption.isNotEmpty() && initialAction != MacroAction.None) }

    fun autoCaption(value: String) {
        if (!captionEdited) caption = value
    }

    val draftAction: MacroAction = when (mode) {
        Mode.KEY -> when {
            preset != null -> MacroAction.Shortcut(preset!!)
            chord != null -> MacroAction.Key(chord!!)
            else -> MacroAction.None
        }
        Mode.TEXT -> if (text.isNotBlank()) MacroAction.Text(text, enterAfter) else MacroAction.None
    }
    val draft = Macro(caption.take(CAPTION_MAX).uppercase(), draftAction)
    val valid = draftAction != MacroAction.None && draft.caption.isNotBlank()
    val mac = os == HostOs.MACOS

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Key ${index + 1}", style = MaterialTheme.typography.titleLarge)

            // WYSIWYG preview: the real keycap at its real size on a swatch of chassis.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.chassisBase)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                MacroKey(macro = draft, os = os, interactive = false)
            }

            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it.take(CAPTION_MAX); captionEdited = true },
                label = { Text("Caption") },
                singleLine = true,
                supportingText = { Text("Up to $CAPTION_MAX characters, shown under the symbol.") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == Mode.KEY, onClick = { mode = Mode.KEY }, label = { Text("Key") })
                FilterChip(selected = mode == Mode.TEXT, onClick = { mode = Mode.TEXT }, label = { Text("Text") })
            }

            when (mode) {
                Mode.KEY -> {
                    Text("Common keys", style = MaterialTheme.typography.labelLarge)
                    KeycapRow(
                        items = Keys.COMMON.map { Macro(it.caption, MacroAction.Key(KeyChord(it.usage, 0))) },
                        os = os,
                        isSelected = { m -> preset == null && chord == (m.action as MacroAction.Key).chord },
                        onPick = { m ->
                            preset = null
                            chord = (m.action as MacroAction.Key).chord
                            autoCaption(m.caption)
                        },
                    )

                    Text("Shortcuts", style = MaterialTheme.typography.labelLarge)
                    if (os == null) {
                        Text(
                            "Set this computer's OS (tap its name on the main screen) so shortcuts know " +
                                "whether Paste is Ctrl+V or ⌘V.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    KeycapRow(
                        items = Preset.entries.map { Macro(it.caption, MacroAction.Shortcut(it)) },
                        os = os,
                        isSelected = { m -> preset == (m.action as MacroAction.Shortcut).preset },
                        onPick = { m ->
                            chord = null
                            preset = (m.action as MacroAction.Shortcut).preset
                            autoCaption(m.caption)
                        },
                    )

                    Text("Any other key", style = MaterialTheme.typography.labelLarge)
                    val mods = chord?.modifiers ?: 0
                    fun toggle(bit: Int) {
                        val base = chord ?: return
                        preset = null
                        chord = base.copy(modifiers = base.modifiers xor bit)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = mods and Mod.CTRL != 0, onClick = { toggle(Mod.CTRL) }, label = { Text(if (mac) "⌃ Control" else "Ctrl") })
                        FilterChip(selected = mods and Mod.ALT != 0, onClick = { toggle(Mod.ALT) }, label = { Text(if (mac) "⌥ Option" else "Alt") })
                        FilterChip(selected = mods and Mod.SHIFT != 0, onClick = { toggle(Mod.SHIFT) }, label = { Text(if (mac) "⇧ Shift" else "Shift") })
                        FilterChip(selected = mods and Mod.GUI != 0, onClick = { toggle(Mod.GUI) }, label = { Text(if (mac) "⌘ Command" else "Win") })
                    }
                    Text("Pick a key, then add modifiers.", style = MaterialTheme.typography.bodySmall)
                    listOf(Keys.LETTERS, Keys.DIGITS, Keys.FUNCTION).forEach { group ->
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            group.forEach { k ->
                                val selected = preset == null && chord?.usage == k.usage
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        preset = null
                                        chord = KeyChord(k.usage, chord?.modifiers ?: 0)
                                        autoCaption(k.caption)
                                    },
                                    label = { Text(k.caption) },
                                )
                            }
                        }
                    }
                }
                Mode.TEXT -> {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; autoCaption("TEXT") },
                        label = { Text("Text to type") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val delivered = TextTyper.foldAccents(TextTyper.straighten(text))
                    if (delivered != text) {
                        Text("Will be typed as: $delivered", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = enterAfter, onCheckedChange = { enterAfter = it })
                        Text("Press Enter after", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Text("Saved for $hostName", style = MaterialTheme.typography.bodySmall)

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSave(Macro("", MacroAction.None)); onDismiss() }) { Text("Clear") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(enabled = valid, onClick = { onSave(draft); onDismiss() }) { Text("Save") }
            }
        }
    }
}

/** A sideways-scrolling row of small keycaps; the selected one shows the lit art. */
@Composable
private fun KeycapRow(
    items: List<Macro>,
    os: HostOs?,
    isSelected: (Macro) -> Boolean,
    onPick: (Macro) -> Unit,
) {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.chassisBase)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { m ->
            MacroKey(
                macro = m,
                os = os,
                size = 56.dp,
                lit = isSelected(m),
                onTap = { onPick(m) },
            )
        }
    }
}

private const val CAPTION_MAX = 8
