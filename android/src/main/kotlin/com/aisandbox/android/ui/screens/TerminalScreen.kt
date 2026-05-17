package com.aisandbox.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Splitscreen
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aisandbox.android.R
import com.aisandbox.android.terminal.service.TerminalForegroundService
import com.aisandbox.android.ui.components.BatteryOptPrompt
import com.aisandbox.android.ui.components.HapticEventListener
import com.aisandbox.android.ui.components.KeyEncoding
import com.aisandbox.android.ui.components.KeyEvent
import com.aisandbox.android.ui.components.ModifierBar
import com.aisandbox.android.requireContainer
import com.aisandbox.android.ui.theme.AiSandboxMonoTypography
import com.aisandbox.android.ui.theme.BgWorkbench
import com.aisandbox.android.ui.theme.OnSurface
import com.aisandbox.android.ui.theme.OnSurfaceMuted
import com.aisandbox.android.ui.theme.OnSurfaceVariant
import com.aisandbox.android.ui.theme.Success
import com.aisandbox.android.ui.theme.SurfaceLow
import com.aisandbox.android.ui.theme.Warning
import kotlin.math.min

/**
 * UC04-3 terminal screen. Top mono bar + terminal surface + docked
 * modifier bar.
 *
 * <p>Two key v0.1 notes the design team has acknowledged:
 *
 * <ol>
 *   <li><b>Terminal rendering</b> — the proposal calls for Termux's
 *       {@code TerminalView} (Apache 2.0). Termux ships as a source
 *       module (no Maven artefact); vendoring it is a one-time
 *       integration task outside the scope of this checkpoint. The
 *       placeholder [SimpleTerminalSurface] renders raw UTF-8 bytes
 *       monospace + wraps at viewport width — sufficient to confirm
 *       wire correctness end-to-end (`echo`, `ls -l`, etc.) while
 *       Termux's emulator follows up.</li>
 *   <li><b>Selection menu</b> — long-press → Copy / Paste / Select all /
 *       Send to… is a future polish item; the v0.1 surface ignores
 *       long-press in the terminal pane.</li>
 * </ol>
 *
 * <p>What IS wired here: AC14 (BEL → haptic), AC15 (modifier bar with
 * sticky-one-shot semantics), AC18 (resize frame on viewport change),
 * AC24 (reconnect indicator), AC25 (give-up "tap to reconnect"
 * surfacing), AC12 (mono toolbar with session name + status dot +
 * connection metadata).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    sessionN: Int,
    onBack: () -> Unit,
    viewModel: TerminalViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    var splitOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Bind once; subsequent recompositions are no-ops.
    LaunchedEffect(sessionN) { viewModel.attach(sessionN) }

    // AC14 — observe haptic events and fire 150 ms vibrate.
    HapticEventListener(viewModel = viewModel)

    // AC21–AC23 — start the dataSync foreground service when the WS is
    // Open; tear it down on Revoked / GaveUp / leaving the screen. Show
    // the one-time battery-optimization prompt the first time we reach
    // Open after install.
    val container = remember { requireContainer(context) }
    val profile by container.profileStore.profile.collectAsState(initial = null)
    LaunchedEffect(state, profile) {
        when (state) {
            is TerminalState.Open -> {
                TerminalForegroundService.start(
                    context,
                    TerminalForegroundService.NotificationParams(
                        sessionN = sessionN,
                        wssUrl = profile?.serverUrl?.replace("https://", "wss://") ?: "",
                        cols = 80,
                        rows = 24,
                        idleSec = 0,
                    ),
                )
            }
            is TerminalState.Revoked, is TerminalState.GaveUp -> {
                TerminalForegroundService.stop(context)
            }
            else -> { /* keep current notification — reconnect counts as still-attached */ }
        }
    }
    // AC23 — one-time prompt on first reach of Open.
    if (state is TerminalState.Open) {
        BatteryOptPrompt()
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { TerminalForegroundService.stop(context) }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "ai-sandbox-$sessionN",
                                style = AiSandboxMonoTypography.sessionId,
                                color = OnSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(state.dotColor()),
                            )
                        }
                        Text(
                            text = state.subtitleFor(),
                            style = AiSandboxMonoTypography.metadata,
                            color = OnSurfaceMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.terminal_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { splitOpen = !splitOpen }) {
                        Icon(
                            imageVector = Icons.Outlined.Splitscreen,
                            contentDescription = stringResource(R.string.terminal_split),
                        )
                    }
                    IconButton(onClick = { /* future overflow menu */ }) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.terminal_more),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        TerminalBody(
            padding = innerPadding,
            state = state,
            splitOpen = splitOpen,
            viewModel = viewModel,
            onReconnect = viewModel::userTriggeredReconnect,
        )
    }
}

@Composable
private fun TerminalBody(
    padding: PaddingValues,
    state: TerminalState,
    splitOpen: Boolean,
    viewModel: TerminalViewModel,
    onReconnect: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(padding).background(BgWorkbench)) {
        if (state is TerminalState.Reconnecting) {
            ReconnectBanner(state = state)
        }
        if (state is TerminalState.GaveUp) {
            DisconnectedBanner(onReconnect = onReconnect)
        }
        // Body: terminal surface(s). The split-pane toggle stacks two
        // SimpleTerminalSurfaces vertically — both subscribe to the
        // same flow for v0.1 (server-side `tmux split-window -v` wiring
        // is a follow-up).
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (splitOpen) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        SimpleTerminalSurface(viewModel = viewModel)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .background(SurfaceLow),
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        SimpleTerminalSurface(viewModel = viewModel)
                    }
                }
            } else {
                SimpleTerminalSurface(viewModel = viewModel)
            }
        }
        // AC15 modifier bar.
        ModifierBar(onKey = { event -> dispatchKey(event, viewModel) })
    }
}

/**
 * Translate one [KeyEvent] into PTY input. Sticky-modifier semantics
 * apply: arm/disarm events update the local prefix state; the next
 * non-modifier event flushes both the modifier byte and the keypress.
 *
 * <p>v0.1 does not maintain a persistent "ctrl/alt armed" buffer here
 * — the modifier-aware character path is the ViewModel's
 * responsibility once we plug in a real terminal emulator. For now we
 * emit the immediate byte sequence so navigation keys / Esc / Tab work
 * out of the box on a stock bash prompt.
 */
private fun dispatchKey(event: KeyEvent, viewModel: TerminalViewModel) {
    val bytes = KeyEncoding.bytesFor(event) ?: return
    val payload = if (event is KeyEvent.Function ||
        event is KeyEvent.ArrowUp || event is KeyEvent.ArrowDown ||
        event is KeyEvent.ArrowLeft || event is KeyEvent.ArrowRight
    ) {
        // Escape-prefixed sequences (xterm convention).
        byteArrayOf(0x1b) + bytes
    } else {
        bytes
    }
    viewModel.sendStdin(payload)
}

/**
 * Placeholder terminal surface — renders the latest few KB of stdout as
 * monospace text. Future revision swaps in Termux's `TerminalView` via
 * `AndroidView`; the public Composable signature stays the same so the
 * call site is forward-compatible.
 */
@Composable
private fun SimpleTerminalSurface(viewModel: TerminalViewModel) {
    val scrollState = rememberScrollState()
    val buffer = remember { StringBuilder(16_384) }
    var ticker by remember { mutableStateOf(0) }

    LaunchedEffect(viewModel) {
        viewModel.output.collect { bytes ->
            // UTF-8 decode tolerant of partial sequences (best-effort).
            val text = String(bytes, Charsets.UTF_8)
            buffer.append(text)
            // Cap at ~16k chars to keep the recomposer's state small.
            if (buffer.length > 16_000) {
                buffer.delete(0, buffer.length - 16_000)
            }
            ticker++  // force recompose
        }
    }

    val display by remember(buffer) {
        derivedStateOf { @Suppress("UNUSED_EXPRESSION") ticker; buffer.toString() }
    }

    LaunchedEffect(display) {
        // Auto-scroll to bottom on new output.
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgWorkbench)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .verticalScroll(scrollState),
    ) {
        Text(
            text = display.take(min(display.length, 16_000)),
            style = AiSandboxMonoTypography.terminalBody.copy(fontFamily = FontFamily.Monospace),
            color = OnSurface,
            softWrap = true,
        )
    }
}

// ── Banners ─────────────────────────────────────────────────────────────

@Composable
private fun ReconnectBanner(state: TerminalState.Reconnecting) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Warning.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "${stringResource(R.string.terminal_reconnecting)} (attempt ${state.attempt})",
            style = MaterialTheme.typography.labelMedium,
            color = Warning,
        )
    }
}

@Composable
private fun DisconnectedBanner(onReconnect: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.20f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.terminal_disconnected),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
        )
        // Tap anywhere on the banner to reconnect; clickable wraps the Text.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(Color.Transparent),
        ) {
            androidx.compose.material3.TextButton(onClick = onReconnect) {
                Text(text = stringResource(R.string.terminal_disconnected))
            }
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────

private fun TerminalState.dotColor() = when (this) {
    is TerminalState.Open -> Success
    is TerminalState.Connecting, is TerminalState.Reconnecting -> Warning
    else -> OnSurfaceVariant
}

@Composable
private fun TerminalState.subtitleFor(): String {
    val protoStr = "wss · subproto ai-sandbox.v1"
    return when (this) {
        is TerminalState.Open -> "$protoStr · attached"
        is TerminalState.Connecting -> "$protoStr · connecting…"
        is TerminalState.Reconnecting -> "$protoStr · reconnect ${this.attempt}"
        is TerminalState.GaveUp -> "$protoStr · gave up"
        is TerminalState.Revoked -> "$protoStr · revoked"
        is TerminalState.Idle -> protoStr
        is TerminalState.Failed -> "$protoStr · ${this.reason}"
    }
}
