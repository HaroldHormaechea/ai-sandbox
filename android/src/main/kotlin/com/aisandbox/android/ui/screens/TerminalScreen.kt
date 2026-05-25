package com.aisandbox.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aisandbox.android.R
import com.aisandbox.android.net.SessionSummary
import com.aisandbox.android.requireContainer
import com.aisandbox.android.terminal.TerminalStreamController
import com.aisandbox.android.terminal.service.TerminalForegroundService
import com.aisandbox.android.ui.components.AgentSwitcherBar
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
    // AC#8 — the WebSocket + emulator are process-scoped, so leaving the screen
    // via the back arrow no longer tears anything down (the prior DisposableEffect
    // onDispose{ stop } is intentionally gone). Teardown is now explicit: the
    // hamburger's Disconnect / Delete actions, or process death (FGS self-stops).

    // Hamburger menu + delete-confirm dialog state (AC#5/#6/#7).
    var menuOpen by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val summary by viewModel.currentSummary.collectAsState()

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
                    // AC#5 — hamburger menu with Delete session + Disconnect.
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.terminal_more),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.terminal_menu_delete)) },
                                onClick = {
                                    menuOpen = false
                                    showDeleteDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.terminal_menu_disconnect)) },
                                onClick = {
                                    menuOpen = false
                                    // AC#7 — tear down + leave, no confirmation.
                                    viewModel.disconnect()
                                    onBack()
                                },
                            )
                        }
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

    // AC#6 — Delete reuses UC-20's confirm dialog (incl. the force toggle for
    // attached sessions). On confirm we delete on the server, tear down the
    // stream + FGS (in the ViewModel), and return to the list on success.
    if (showDeleteDialog) {
        DeleteSessionDialog(
            target = summary ?: SessionSummary(n = sessionN),
            onCancel = { showDeleteDialog = false },
            onConfirm = { force ->
                showDeleteDialog = false
                viewModel.deleteSession(force) { ok -> if (ok) onBack() }
            },
        )
    }
}

/**
 * UC-23 — test tags for the instrumented inset/occlusion assertions (AC#8).
 * Public so the androidTest module can target the terminal viewport and the
 * docked modifier-bar regions by tag.
 */
const val TerminalViewportTestTag = "uc23_terminal_viewport"
const val ModifierBarTestTag = "uc23_modifier_bar"

@Composable
private fun TerminalBody(
    padding: PaddingValues,
    state: TerminalState,
    controller: TerminalStreamController,
    viewModel: TerminalViewModel,
    onReconnect: () -> Unit,
) {
    val targets by viewModel.targets.collectAsState()
    val selectedTargetId by viewModel.selectedTargetId.collectAsState()

    // UC-23 — the body wrapper owns the Scaffold's system-bar insets via
    // padding(padding) and *consumes* them via consumeWindowInsets(padding) so
    // descendants don't double-count the navigation bar. The IME inset is
    // deliberately NOT consumed here: TerminalScaffoldLayout reads it through
    // its single injected `imeInsets` seam, and the docked ModifierBar
    // re-applies it via windowInsetsPadding — which then nets max(ime, navBar)
    // on the bottom edge because the nav bar is already consumed.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .background(BgWorkbench),
    ) {
        if (state is TerminalState.Reconnecting) {
            ReconnectBanner(state = state)
        }
        if (state is TerminalState.GaveUp) {
            DisconnectedBanner(onReconnect = onReconnect)
        }
        TerminalScaffoldLayout(
            modifier = Modifier.weight(1f),
            // AC#9–12 — agent-team switcher between the top bar and the
            // terminal. Self-hides when only the main target is present, and
            // collapses to a compact strip while the IME is up (AC#4).
            agentSwitcher = { compact ->
                AgentSwitcherBar(
                    targets = targets,
                    selectedTargetId = selectedTargetId,
                    onSelect = viewModel::selectTarget,
                    compact = compact,
                )
            },
            // The real terminal surface (Termux TerminalView via AndroidView).
            // It fills the requiredHeight-pinned slot supplied by the layout.
            terminal = {
                TerminalSurface(controller = controller, modifier = Modifier.fillMaxSize())
            },
            // AC15 / AC3 modifier bar — docked above the keyboard by the layout.
            modifierBar = {
                ModifierBar(onKey = { event -> dispatchKey(event, viewModel) })
            },
        )
    }
}

/**
 * UC-23 — the IME-aware terminal layout. Owns ALL of this screen's inset reads
 * through the single injected [imeInsets] parameter, which is the AC#8 test
 * seam: an instrumented test can pass a fabricated [WindowInsets] to drive the
 * keyboard-up geometry deterministically (no real IME needed) and assert that
 * (a) the terminal/cursor region's bottom sits above the IME inset and (b) the
 * modifier bar is positioned above the keyboard.
 *
 * <p>Behaviour: while the IME is up, the modifier-bar slot grows by the IME
 * height (windowInsetsPadding), shrinking the bottom-anchored, clipped terminal
 * viewport [Box]. The terminal slot inside pins its measured pixel height to the
 * resting (keyboard-hidden) height via [requiredHeight], so it ignores the
 * shrunk constraint, overflows the top (clipped), and keeps the cursor row just
 * above the modifier bar — without ever changing the [com.termux.view.TerminalView]'s
 * measured height, which is what would otherwise trigger a PTY resize.
 *
 * @param imeInsets the sole inset source for this layout (AC#8 test seam).
 * @param agentSwitcher slot; receives `compact = imeVisible` (AC#4).
 * @param terminal slot; sized by this layout via requiredHeight (see below).
 * @param modifierBar slot; docked above the keyboard via windowInsetsPadding.
 */
// `internal` (not `private`) so the androidTest source set can inject a
// fabricated `imeInsets` to drive the AC#8 instrumented assertions.
@Composable
internal fun TerminalScaffoldLayout(
    modifier: Modifier = Modifier,
    imeInsets: WindowInsets = WindowInsets.ime,
    agentSwitcher: @Composable (compact: Boolean) -> Unit,
    terminal: @Composable () -> Unit,
    modifierBar: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    // AC#8 seam: the ONLY inset read in this layout.
    val imeBottomPx = imeInsets.getBottom(density)
    val imeVisible = imeBottomPx > 0

    // Resting (keyboard-hidden) viewport height. Captured only while the IME is
    // down. Null until the first keyboard-hidden layout → fall back to
    // fillMaxSize (entering the screen with the IME already up yields one
    // self-correcting resize on the first keyboard-hidden frame; accepted).
    var restingViewportHeight by remember { mutableStateOf<Dp?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        // AC#4 — collapse the switcher to a compact strip while typing.
        agentSwitcher(imeVisible)

        // Terminal viewport: a shrinking, clipped, bottom-anchored Box. As the
        // IME pushes the modifier bar up, this Box loses height; the terminal
        // slot pins its own height and overflows the (clipped) top so the
        // cursor row stays just above the modifier bar (AC#1).
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged { size ->
                    // Re-capture the resting height ONLY while the keyboard is
                    // hidden. This is also exactly what lets genuine geometry
                    // changes (rotation, font/size changes) still flow a resize
                    // to the PTY: the resting height changes, the requiredHeight
                    // below changes with it, and TerminalView.updateSize() fires
                    // normally. IME toggles never reach here (guarded), so they
                    // never resize (AC#2).
                    if (!imeVisible) {
                        restingViewportHeight = with(density) { size.height.toDp() }
                    }
                },
            contentAlignment = Alignment.BottomStart,
        ) {
            // ⚠️ LOAD-BEARING (challenger guardrail #1): the no-PTY-resize
            // guarantee (AC#2) depends on requiredHeight() here — NOT height()
            // and NOT letting the view fill the shrinking Box. requiredHeight
            // makes the TerminalView ignore the parent's shrunk constraint, keep
            // its measured pixel height constant, and overflow the clipped Box.
            // Because getHeight() never changes on an IME toggle,
            // TerminalView.updateSize()'s column/row change-guard never fires, so
            // no sendResize is emitted. Switching to height()/fill re-introduces
            // the resize and BREAKS AC#2 — do not change this.
            val terminalSizeModifier = restingViewportHeight
                ?.let { Modifier.fillMaxWidth().requiredHeight(it) }
                ?: Modifier.fillMaxSize()
            Box(modifier = terminalSizeModifier.testTag(TerminalViewportTestTag)) {
                terminal()
            }
        }

        // AC#3 — dock the modifier bar directly above the keyboard. The wrapper
        // consumed the nav-bar inset, so windowInsetsPadding(imeInsets) nets
        // max(ime, navBar) on the bottom edge: it rests on the nav bar with the
        // keyboard down and rides up to sit on the keyboard when it's shown.
        // The ModifierBar's internals (escape sequences / KeyEncoding) are
        // untouched, so AC#3's sequence correctness is preserved.
        Box(
            modifier = Modifier
                .windowInsetsPadding(imeInsets)
                .testTag(ModifierBarTestTag),
        ) {
            modifierBar()
        }
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
