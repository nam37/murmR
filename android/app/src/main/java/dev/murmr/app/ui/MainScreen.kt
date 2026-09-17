package dev.murmr.app.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.murmr.app.hid.HidKeyboard
import dev.murmr.app.hid.HostDevice
import dev.murmr.app.service.PttPhase
import dev.murmr.app.service.UiState
import dev.murmr.app.ui.instrument.Chassis
import dev.murmr.app.ui.instrument.ChassisArt
import dev.murmr.app.ui.instrument.ConnectionSheet
import dev.murmr.app.ui.instrument.GlassArt
import dev.murmr.app.ui.instrument.GlassPanel
import dev.murmr.app.ui.instrument.Header
import dev.murmr.app.ui.instrument.Palette
import dev.murmr.app.ui.instrument.TalkButton
import dev.murmr.app.ui.instrument.Waveform

/**
 * The instrument: the approved skeuomorphic mockup (design/phone-mockup) built from its
 * artwork. Layout, spacing, type and colour follow the mockup's stylesheet values.
 */
@Composable
fun MainScreen(
    state: UiState,
    hosts: List<HostDevice>,
    permissionsDenied: Boolean,
    onRefreshHosts: () -> Unit,
    onConnect: (address: String) -> Unit,
    onDisconnect: () -> Unit,
    onMakeDiscoverable: () -> Unit,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    var chassisArt by rememberSaveable { mutableStateOf(ChassisArt.ORIGINAL) }
    var glassArt by rememberSaveable { mutableStateOf(GlassArt.ORIGINAL) }

    val busy = state.phase == PttPhase.FINISHING || state.phase == PttPhase.TYPING
    val listening = state.phase == PttPhase.LISTENING || state.phase == PttPhase.FINISHING

    Chassis(chassisArt) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 14.dp, top = 18.dp, end = 14.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(hid = state.hid, lastHost = state.lastHost, onOpenConnection = { sheetOpen = true })

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
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 29.dp, top = 26.dp, end = 29.dp, bottom = 27.dp),
            ) {
                TranscriptContent(state)
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
                TalkButton(phase = state.phase, enabled = !busy, onDown = onPttDown, onUp = onPttUp)
                Text(
                    "Hold to talk, release to type",
                    color = Palette.instructions,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 15.dp),
                )
                Text(
                    "Volume Down works too",
                    color = Palette.hint,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }

    if (sheetOpen) {
        ConnectionSheet(
            hid = state.hid,
            lastHost = state.lastHost,
            hosts = hosts,
            chassisArt = chassisArt,
            glassArt = glassArt,
            onChassisArt = { chassisArt = it },
            onGlassArt = { glassArt = it },
            onRefreshHosts = onRefreshHosts,
            onConnect = onConnect,
            onDisconnect = onDisconnect,
            onMakeDiscoverable = onMakeDiscoverable,
            onDismiss = { sheetOpen = false },
        )
    }
}

@Composable
private fun ColumnScope.TranscriptContent(state: UiState) {
    val offline = state.hid !is HidKeyboard.State.Connected
    val status = when {
        offline && state.phase == PttPhase.IDLE -> "Offline"
        state.phase == PttPhase.LISTENING -> "Listening"
        state.phase == PttPhase.FINISHING -> "Finishing"
        state.phase == PttPhase.TYPING -> "Typing"
        state.phase == PttPhase.SENT -> "Typed"
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

    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(top = 20.dp)
            .verticalScroll(rememberScrollState()),
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
                val shown = state.lastTyped.ifBlank { state.partial }
                if (shown.isBlank()) Placeholder("Hold to talk") else Text(shown, style = transcriptStyle)
            }
        }
    }

    val notice = when {
        state.phase == PttPhase.TYPING ->
            "Typing · ${state.deliveredChars.coerceAtMost(state.partial.length)} / ${state.partial.length} characters"
        state.error != null -> state.error
        else -> null
    }
    if (notice != null) {
        Text(notice, color = Palette.notice, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun Placeholder(text: String) {
    Text(text, color = Palette.placeholder, fontSize = 23.sp, fontWeight = FontWeight.Medium)
}
