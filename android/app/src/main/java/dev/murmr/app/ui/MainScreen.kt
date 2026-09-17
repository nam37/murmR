package dev.murmr.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.murmr.app.hid.HidKeyboard
import dev.murmr.app.hid.HostDevice
import dev.murmr.app.macros.HostConfig
import dev.murmr.app.macros.HostOs
import dev.murmr.app.macros.Macro
import dev.murmr.app.macros.MacroAction
import dev.murmr.app.service.PttPhase
import dev.murmr.app.service.UiState
import dev.murmr.app.settings.KeepAwake
import dev.murmr.app.settings.Settings
import dev.murmr.app.ui.instrument.Chassis
import dev.murmr.app.ui.instrument.ChassisArt
import dev.murmr.app.ui.instrument.ConnectionSheet
import dev.murmr.app.ui.instrument.GlassArt
import dev.murmr.app.ui.instrument.GlassPanel
import dev.murmr.app.ui.instrument.Header
import dev.murmr.app.ui.instrument.MacroCluster
import dev.murmr.app.ui.instrument.MacroEditorSheet
import dev.murmr.app.ui.instrument.Palette
import dev.murmr.app.ui.instrument.SettingsSheet
import dev.murmr.app.ui.instrument.Waveform

private enum class Sheet { NONE, CONNECTION, SETTINGS }

/**
 * The instrument: the approved skeuomorphic mockup (design/phone-mockup) built from its
 * artwork. Layout, spacing, type and colour follow the mockup's stylesheet values.
 */
@Composable
fun MainScreen(
    state: UiState,
    hosts: List<HostDevice>,
    permissionsDenied: Boolean,
    settings: Settings,
    onSettings: ((Settings) -> Settings) -> Unit,
    onRefreshHosts: () -> Unit,
    onConnect: (address: String) -> Unit,
    onDisconnect: () -> Unit,
    onMakeDiscoverable: () -> Unit,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    onEraseLast: () -> Unit,
    onTranscriptTouch: () -> Unit,
    hostConfig: HostConfig,
    canEditKeys: Boolean,
    onMacro: (index: Int) -> Unit,
    onSaveMacro: (index: Int, Macro) -> Unit,
    onHostOs: (HostOs) -> Unit,
) {
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var editingKey by remember { mutableStateOf<Int?>(null) }
    var chassisArt by rememberSaveable { mutableStateOf(ChassisArt.ORIGINAL) }
    var glassArt by rememberSaveable { mutableStateOf(GlassArt.ORIGINAL) }

    val busy = state.phase == PttPhase.FINISHING || state.phase == PttPhase.TYPING || state.phase == PttPhase.ERASING
    val listening = state.phase == PttPhase.LISTENING || state.phase == PttPhase.FINISHING
    // As in the mockup: no computer, no dictation. A hold that cannot land anywhere is a trap.
    val connected = state.hid is HidKeyboard.State.Connected

    // The phone is held while the user looks at the computer; letting it dim mid-hold would be
    // worse than the screen cost. Which idle moments also stay lit is the user's setting.
    val awake = when (settings.keepAwake) {
        KeepAwake.WHILE_CONNECTED -> connected || state.phase != PttPhase.IDLE
        KeepAwake.WHILE_DICTATING -> state.phase != PttPhase.IDLE
        KeepAwake.NEVER -> false
    }
    KeepScreenOn(awake)

    val currentTouch by rememberUpdatedState(onTranscriptTouch)

    Chassis(chassisArt) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 14.dp, top = 18.dp, end = 14.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(hid = state.hid, lastHost = state.lastHost, onOpenConnection = { sheet = Sheet.CONNECTION })

            if (permissionsDenied) {
                Text(
                    "Microphone and Nearby devices permissions are required. " +
                        "Grant them in system settings and reopen the app.",
                    color = Palette.ledOff,
                    fontSize = 12.sp,
                )
            }

            GlassPanel(
                art = glassArt,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // Any touch on the transcript restarts the auto-clear countdown. Observed,
                    // not consumed, so scrolling and the erase button work as before.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            currentTouch()
                        }
                    },
                contentPadding = PaddingValues(start = 29.dp, top = 26.dp, end = 29.dp, bottom = 27.dp),
            ) {
                TranscriptContent(state, onEraseLast)
            }

            GlassPanel(
                art = glassArt,
                modifier = Modifier.fillMaxWidth().height(66.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Waveform(
                    level = state.level,
                    active = listening,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                )
            }

            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                MacroCluster(
                    phase = state.phase,
                    connected = connected,
                    macros = hostConfig.macros,
                    os = hostConfig.os,
                    onPttDown = onPttDown,
                    onPttUp = onPttUp,
                    onMacro = onMacro,
                    // Assignment needs a computer to belong to; with none known yet, pairing
                    // is the next step, so open that sheet instead.
                    onEditMacro = { i -> if (canEditKeys) editingKey = i else sheet = Sheet.CONNECTION },
                )
                // Settings lives in the footer as etched caption text, the mockup's own idiom
                // for its sheet button, so it never competes with the controls. The button
                // itself says "hold to talk"; nothing else on the screen repeats it.
                Text(
                    "SETTINGS",
                    color = Palette.hint,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { sheet = Sheet.SETTINGS }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
    }

    editingKey?.let { index ->
        MacroEditorSheet(
            index = index,
            initial = hostConfig.macros.getOrNull(index) ?: Macro("", MacroAction.None),
            hostName = state.lastHost ?: "this computer",
            os = hostConfig.os,
            onSave = { macro -> onSaveMacro(index, macro) },
            onDismiss = { editingKey = null },
        )
    }

    when (sheet) {
        Sheet.CONNECTION -> ConnectionSheet(
            hid = state.hid,
            lastHost = state.lastHost,
            hosts = hosts,
            hostOs = hostConfig.os,
            canSetOs = canEditKeys,
            onHostOs = onHostOs,
            onRefreshHosts = onRefreshHosts,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onMakeDiscoverable = onMakeDiscoverable,
            onOpenSettings = { sheet = Sheet.SETTINGS },
            onDismiss = { sheet = Sheet.NONE },
        )
        Sheet.SETTINGS -> SettingsSheet(
            settings = settings,
            onSettings = onSettings,
            chassisArt = chassisArt,
            glassArt = glassArt,
            onChassisArt = { chassisArt = it },
            onGlassArt = { glassArt = it },
            onDismiss = { sheet = Sheet.NONE },
        )
        Sheet.NONE -> Unit
    }
}

@Composable
private fun ColumnScope.TranscriptContent(state: UiState, onEraseLast: () -> Unit) {
    val offline = state.hid !is HidKeyboard.State.Connected
    val status = when {
        offline && state.phase == PttPhase.IDLE -> "Offline"
        state.phase == PttPhase.LISTENING -> "Listening"
        state.phase == PttPhase.FINISHING -> "Finishing"
        state.phase == PttPhase.TYPING -> "Typing"
        state.phase == PttPhase.SENT -> "Typed"
        state.phase == PttPhase.ERASING -> "Erasing"
        else -> "Ready"
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("TRANSCRIPT", color = Palette.heading, fontSize = 11.sp, letterSpacing = 2.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Canvas(Modifier.size(8.dp)) {
                drawCircle(Palette.ledGlow.copy(alpha = 0.5f), radius = size.minDimension / 2f + 2.dp.toPx())
                drawCircle(Palette.status)
            }
            Text(status, color = Palette.status, fontSize = 11.sp, letterSpacing = 1.sp)
        }
    }

    val transcriptStyle = TextStyle(
        color = Palette.transcript,
        fontSize = 29.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.25).sp,
        lineHeight = 40.sp,
        shadow = Shadow(Color.Black, Offset(0f, 1f), 1f),
    )

    // While words are arriving or being typed, keep the newest text in view by pinning the
    // scroll to the bottom whenever the content grows. Once idle, the user can scroll freely.
    val scroll = rememberScrollState()
    val follow = state.phase != PttPhase.IDLE
    LaunchedEffect(scroll.maxValue, follow) {
        if (follow) scroll.scrollTo(scroll.maxValue)
    }

    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(top = 20.dp)
            .verticalScroll(scroll),
    ) {
        when (state.phase) {
            PttPhase.TYPING -> {
                val text = state.partial
                val n = state.deliveredChars.coerceIn(0, text.length)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Palette.transcript)) { append(text.substring(0, n)) }
                        withStyle(SpanStyle(color = Palette.caret, fontWeight = FontWeight.Normal)) { append("|") }
                        withStyle(SpanStyle(color = Palette.pending)) { append(text.substring(n)) }
                    },
                    style = transcriptStyle,
                )
            }
            PttPhase.ERASING -> {
                // Backspaces eat from the end: what remains stays bright, what is gone dims.
                val text = state.lastTyped
                val keep = (text.length - state.deliveredChars).coerceIn(0, text.length)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Palette.transcript)) { append(text.substring(0, keep)) }
                        withStyle(SpanStyle(color = Palette.pending)) { append(text.substring(keep)) }
                    },
                    style = transcriptStyle,
                )
            }
            PttPhase.LISTENING, PttPhase.FINISHING -> {
                if (state.partial.isBlank()) {
                    Placeholder("Listening…")
                } else {
                    Text(
                        buildAnnotatedString {
                            append(state.partial)
                            withStyle(SpanStyle(color = Palette.caret, fontWeight = FontWeight.Normal)) { append(" |") }
                        },
                        style = transcriptStyle,
                    )
                }
            }
            PttPhase.IDLE, PttPhase.SENT -> {
                // Empty stays empty: the button already says what to do.
                val shown = state.lastTyped.ifBlank { state.partial }
                if (shown.isNotBlank()) Text(shown, style = transcriptStyle)
            }
        }
    }

    val notice = when (state.phase) {
        PttPhase.TYPING ->
            "Typing · ${state.deliveredChars.coerceAtMost(state.partial.length)} / ${state.partial.length} characters"
        PttPhase.ERASING ->
            "Erasing · ${state.deliveredChars.coerceAtMost(state.eraseCount)} / ${state.eraseCount} characters"
        PttPhase.IDLE, PttPhase.SENT ->
            listOfNotNull(state.error, state.notice, state.timing).joinToString("\n").ifBlank { null }
        else -> state.error
    }
    if (notice != null || state.canErase) {
        Row(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                notice.orEmpty(),
                color = Palette.notice,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f),
            )
            if (state.canErase) {
                // Erases exactly what was delivered, by backspace count. Correct only while the
                // cursor on the computer is still right after that text; the app cannot check.
                Text(
                    "ERASE LAST",
                    color = Palette.status,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onEraseLast)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Placeholder(text: String) {
    Text(text, color = Palette.placeholder, fontSize = 23.sp, fontWeight = FontWeight.Medium)
}

/** Holds the screen awake while [enabled]; the flag is dropped automatically when the view detaches. */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
