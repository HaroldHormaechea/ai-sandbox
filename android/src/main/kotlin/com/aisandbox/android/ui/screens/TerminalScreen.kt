package com.aisandbox.android.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aisandbox.android.R
import com.aisandbox.android.requireContainer
import com.aisandbox.android.terminal.TerminalStreamController
import com.aisandbox.android.terminal.service.TerminalForegroundService
import com.aisandbox.android.ui.components.BatteryOptPrompt
import com.aisandbox.android.ui.components.HapticEventListener
import com.aisandbox.android.ui.components.KeyEncoding
import com.aisandbox.android.ui.components.KeyEvent
import com.aisandbox.android.ui.components.ModifierBar
import com.aisandbox.android.ui.components.TerminalSurface
import com.aisandbox.android.ui.theme.AiSandboxMonoTypography
import com.aisandbox.android.ui.theme.BgWorkbench
import com.aisandbox.android.ui.theme.OnSurface
import com.aisandbox.android.ui.theme.OnSurfaceMuted
import com.aisandbox.android.ui.theme.OnSurfaceVariant
import com.aisandbox.android.ui.theme.Success
import com.aisandbox.android.ui.theme.Warning

/**
 * UC04-3 / UC-21 terminal screen. Top mono bar + real terminal surface +
 * docked modifier bar.
 *
 * <p>UC-21 M1 replaced the v0.1 placeholder ([com.aisandbox.android.ui.components.TerminalSurface]
 * now hosts the vendored Termux {@code TerminalView} for full ANSI/cursor/color
 * emulation — AC#1) and moved WebSocket + emulator ownership into the
 * process-scoped [TerminalStreamController], so the session keeps syncing across
 * back-navigation (AC#8 foundation). Input arrives from the IME / hardware
 * keyboard (AC#2) and the [ModifierBar] (AC#3); the surface drives resize frames
 * so the server PTY matches the rendered geometry (AC#4), which also feeds the
 * foreground-service notification's cols × rows.
 *
 * <p>What is still wired here: AC14 (BEL → haptic), AC15/AC3 (modifier bar),
 * AC24 (reconnect indicator), AC25 (give-up "tap to reconnect"), AC12 (mono
 * toolbar with session name + status dot + connection metadata).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    sessionN: Int,
    onBack: () -> Unit,
    viewModel: TerminalViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val container = remember { requireContainer(context) }

    // Process-scoped controller for this session (same instance the VM resolves).
    val controller = remember(sessionN) { container.terminalController(sessionN) }

    // Bind once; subsequent recompositions are no-ops.
    LaunchedEffect(sessionN) { viewModel.attach(sessionN) }

    // AC14 — observe haptic events and fire a 150 ms vibrate.
    HapticEventListener(viewModel = viewModel)

    // AC21–AC23 — keep the dataSync foreground service running while the WS is
    // Open; tear it down on Revoked / GaveUp. The notification's cols × rows now
    // reflect the real rendered geometry (AC#4) instead of a hard-coded 80×24.
    val profile by container.profileStore.profile.collectAsState(initial = null)
    val size by controller.size.collectAsState()
    LaunchedEffect(state, profile, size) {
        when (state) {
            is TerminalState.Open -> {
                TerminalForegroundService.start(
                    context,
                    TerminalForegroundService.NotificationParams(
                        sessionN = sessionN,
                        wssUrl = profile?.serverUrl?.replace("https://", "wss://") ?: "",
                        cols = size.cols,
                        rows = size.rows,
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
    // NOTE (UC-21 M1): the WebSocket + emulator are process-scoped now, so
    // leaving the screen no longer tears the stream down. The remaining FGS
    // lifecycle (back keeps syncing; Disconnect/Delete stop) lands in M2.
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
                    // M2 turns this into a Delete / Disconnect dropdown.
                    IconButton(onClick = { /* M2: overflow menu */ }) {
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
            controller = controller,
            viewModel = viewModel,
            onReconnect = viewModel::userTriggeredReconnect,
        )
    }
}

@Composable
private fun TerminalBody(
    padding: PaddingValues,
    state: TerminalState,
    controller: TerminalStreamController,
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
        // Body: the real terminal surface (Termux TerminalView via AndroidView).
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            TerminalSurface(controller = controller, modifier = Modifier.fillMaxSize())
        }
        // AC15 / AC3 modifier bar.
        ModifierBar(onKey = { event -> dispatchKey(event, viewModel) })
    }
}

/**
 * Translate one [KeyEvent] into PTY input. Sticky-modifier semantics
 * apply: arm/disarm events update the local prefix state; the next
 * non-modifier event flushes both the modifier byte and the keypress.
 *
 * <p>v0.1 does not maintain a persistent "ctrl/alt armed" buffer here
 * — the modifier-aware character path is the terminal view's
 * responsibility. We emit the immediate byte sequence so navigation keys /
 * Esc / Tab work out of the box on a stock bash prompt.
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
