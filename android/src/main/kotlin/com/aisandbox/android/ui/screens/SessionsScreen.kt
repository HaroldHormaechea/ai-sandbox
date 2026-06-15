package com.aisandbox.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aisandbox.android.R
import com.aisandbox.android.net.LifecycleAction
import com.aisandbox.android.net.SessionSummary
import com.aisandbox.android.ui.components.AttachedBadge
import com.aisandbox.android.ui.components.PendingQuestionBadge
import com.aisandbox.android.ui.components.ServerSshBadge
import com.aisandbox.android.ui.components.SessionAvatar
import com.aisandbox.android.ui.components.StatusPill
import com.aisandbox.android.ui.theme.AiSandboxMonoTypography
import com.aisandbox.android.ui.theme.ErrorTone
import com.aisandbox.android.ui.theme.OnSurface
import com.aisandbox.android.ui.theme.OnSurfaceMuted
import com.aisandbox.android.ui.theme.OnSurfaceVariant
import com.aisandbox.android.ui.theme.SurfaceLow
import com.aisandbox.android.ui.theme.Success
import com.aisandbox.android.ui.theme.Warning
import java.util.Locale
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * UC04-2 — large M3 top bar, three filter chips with counts, list of
 * session cards, extended "New session" FAB.
 *
 * <p>Tap a row → [onOpen] (terminal). Swipe a row LEFT → reveals a
 * red/destructive background with a black-outlined trash icon; releasing
 * past the threshold opens the delete-confirmation dialog (UC04-2b / UC20).
 * FAB tap → bottom sheet (UC04-2a). Errors from any operation (refresh /
 * spawn / delete) surface via the [Scaffold]'s snackbar host (AC5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onOpen: (Int) -> Unit,
    onOpenTerminal: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: SessionsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // UC-69 — request POST_NOTIFICATIONS once on Android 13+ so the app-wide
    // pending-question watcher can alert. Asked from the sessions list (the
    // first screen the enrolled user lands on). Denial is a graceful no-op (AC8):
    // the in-app UC-49 "?" indicators still convey pending questions.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted or denied — no-op either way (AC8) */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var showNewSheet by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }

    // AC5 — surface any operation error (refresh / spawn / delete) as a
    // snackbar carrying the error code + status. clearError() afterwards is
    // mandatory: lastError is a StateFlow VALUE, so a repeat same-code
    // failure would not re-key this effect unless we reset it to null first.
    LaunchedEffect(state.lastError) {
        val err = state.lastError
        if (err != null) {
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.sessions_error_snackbar, err),
                actionLabel = context.getString(R.string.sessions_error_dismiss),
                duration = SnackbarDuration.Short,
            )
            viewModel.clearError()
        }
    }

    // UC-32 / AC6 — bind the live status-push feed to the screen's foreground
    // lifecycle. On each (re)START: fire one REST refresh() (belt-and-suspenders
    // resync + the AC5 fallback if the socket never opens) and open the push
    // feed; the server's initial Snapshot is the authoritative resync. On STOP
    // the repeatOnLifecycle block is cancelled and the finally closes the socket
    // so it is never held open in the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.refresh()
            viewModel.connectEvents()
            try {
                awaitCancellation()
            } finally {
                viewModel.disconnectEvents()
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            androidx.compose.material3.LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.sessions_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = OnSurface,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // UC-54 — tri-state connectivity dot: green when the
                            // last interaction succeeded, red when it failed, yellow
                            // while a check is in flight or before the first call
                            // resolves. Color is the sole signal, so a
                            // contentDescription carries the state for TalkBack.
                            val dotColor = when (state.connectivity) {
                                Connectivity.REACHABLE -> Success
                                Connectivity.UNREACHABLE -> ErrorTone
                                // UC-72 — actively attempting a reconnect → yellow.
                                Connectivity.RETRYING -> Warning
                                // UC-72 — waiting out the back-off delay → red.
                                Connectivity.BACKOFF -> ErrorTone
                                Connectivity.CHECKING, Connectivity.UNKNOWN -> Warning
                            }
                            val dotDescription = when (state.connectivity) {
                                Connectivity.REACHABLE ->
                                    stringResource(R.string.sessions_connectivity_reachable)
                                Connectivity.UNREACHABLE ->
                                    stringResource(R.string.sessions_connectivity_unreachable)
                                Connectivity.RETRYING ->
                                    stringResource(R.string.sessions_connectivity_retrying)
                                Connectivity.BACKOFF ->
                                    stringResource(R.string.sessions_connectivity_backoff)
                                Connectivity.CHECKING, Connectivity.UNKNOWN ->
                                    stringResource(R.string.sessions_connectivity_checking)
                            }
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                                    .testTag("sessions_connectivity_dot")
                                    .semantics { contentDescription = dotDescription },
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${trimHost(state.profile?.serverUrl)} · mTLS",
                                style = AiSandboxMonoTypography.metadata,
                                color = OnSurfaceMuted,
                            )
                        }
                    }
                },
                actions = {
                    // UC-84 (AC1) — single overflow/hamburger menu. Rendering lives
                    // in the stateless [SessionsOverflowMenu] seam so an instrumented
                    // test can assert the two items independently of the ViewModel.
                    SessionsOverflowMenu(
                        onStartSsh = { viewModel.openServerSsh { n -> onOpenTerminal(n) } },
                        onOpenSettings = onOpenSettings,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNewSheet = true },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.sessions_new_session)) },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // UC-54 — the transient-connectivity signal is now the tri-state
            // dot in the top bar (replacing the UC-52 "reconnecting" banner), so
            // a server outage degrades gracefully without a disruptive surface.
            SessionsBody(
                // Outer Column already consumed the scaffold inset; the body
                // fills the remaining space.
                padding = PaddingValues(0.dp),
                state = state,
                onSelectFilter = viewModel::selectFilter,
                onOpen = onOpen,
                onOpenTerminal = onOpenTerminal,
                onConfirmDelete = viewModel::delete,
                onLifecycle = viewModel::lifecycle,
                onBlockedOpen = {
                    // UC-46 row-open gate — a non-attachable row (stopped /
                    // paused / terminating) surfaces a hint instead of
                    // navigating into a guaranteed-failing connection.
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar(
                            message = context.getString(R.string.session_not_running_hint),
                            duration = SnackbarDuration.Short,
                        )
                    }
                },
            )
        }
    }

    if (showNewSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewSheet = false },
            sheetState = sheetState,
        ) {
            NewSessionSheet(
                spawning = state.spawning,
                onCancel = {
                    coroutineScope.launch { sheetState.hide(); showNewSheet = false }
                },
                onSpawn = { label ->
                    viewModel.spawn(label.takeIf { it.isNotBlank() })
                    coroutineScope.launch { sheetState.hide(); showNewSheet = false }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionsBody(
    padding: PaddingValues,
    state: SessionsUiState,
    onSelectFilter: (SessionsFilter) -> Unit,
    onOpen: (Int) -> Unit,
    onOpenTerminal: (Int) -> Unit,
    onConfirmDelete: (n: Int, force: Boolean) -> Unit,
    // UC-46 — fire a lifecycle action for a row; surface the row-open hint.
    onLifecycle: (n: Int, action: LifecycleAction) -> Unit = { _, _ -> },
    onBlockedOpen: () -> Unit = {},
) {
    // UC20 — the swipe → confirm → delete flow lives in this internal seam
    // (the same seam UC18 used for tap-to-open) so an instrumented Compose
    // test can drive swipe → dialog → confirm without a live server.
    var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { FilterChipsRow(state = state, onSelectFilter = onSelectFilter) }
        if (state.showRetryingBackground) {
            // UC-70 (AC1/AC2) — the feed is disconnected/reconnecting (or has
            // given up): override the whole list region with the light-grey
            // "Not connected, retrying…" background. This takes precedence over
            // BOTH the rows and the normal empty state, so no stale rows linger
            // (AC2) while the connection is down.
            item { RetryingBackground(status = state.feedStatus) }
        } else if (state.visible.isEmpty()) {
            item { EmptyState(filter = state.filter) }
        } else {
            items(items = state.visible, key = { it.n }) { row ->
                // UC-28 — effective state is the union of the optimistic flag
                // and the server's `terminating` token (see SessionsUiState).
                val effectiveState = state.effectiveState(row)
                val isTerminating = effectiveState == "terminating"
                // UC-46 — a lifecycle action is in flight for this row (AC6:
                // disable its controls); and whether the row is attachable
                // (tap → chat / long-press → terminal) vs. a non-running state
                // that must redirect to the open-gate hint.
                val isPending = state.isPending(row)
                // UC-62 — the server host-shell row is ALWAYS attachable and
                // ALWAYS opens the terminal (never the Claude conversation view),
                // both on tap and long-press (AC5).
                val isSsh = row.isServerSsh
                val attachable = isSsh || isAttachable(effectiveState)
                // Pitfall 5 — scope the dismiss state per-N inside key(row.n)
                // so an in-flight list refresh can't carry a stale anchor onto
                // a different row or resurrect a just-deleted one.
                key(row.n) {
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            // UC-28 AC4 — while this row is terminating, the
                            // swipe-to-delete gesture is a defensive no-op: it
                            // never opens a second confirmation. Combined with
                            // enableDismissFromEndToStart = !isTerminating below
                            // (per-row → siblings stay deletable, AC6).
                            if (!isTerminating && value == SwipeToDismissBoxValue.EndToStart) {
                                // Pitfall 1 / AC2 — open the dialog and VETO the
                                // settle (return false) so the row never
                                // auto-dismisses; deletion happens only on an
                                // explicit confirm.
                                deleteTarget = row
                            }
                            false
                        },
                    )
                    // AC3 — once this row is no longer the pending target
                    // (cancelled or confirmed), snap it back to rest if the
                    // veto left it displaced.
                    LaunchedEffect(deleteTarget?.n) {
                        if (deleteTarget?.n != row.n &&
                            dismissState.currentValue != SwipeToDismissBoxValue.Settled
                        ) {
                            dismissState.reset()
                        }
                    }
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        enableDismissFromEndToStart = !isTerminating,
                        backgroundContent = {
                            SwipeDeleteBackground(progress = dismissState.progress)
                        },
                    ) {
                        SessionRow(
                            row = row,
                            effectiveState = effectiveState,
                            pending = isPending,
                            isServerSsh = isSsh,
                            // UC-62 — the SSH row routes BOTH tap and long-press
                            // to the terminal (never the conversation view). For
                            // Claude rows the UC-46 row-open gate is unchanged:
                            // tap → chat, long-press → terminal, else hint.
                            onTap = {
                                when {
                                    isSsh -> onOpenTerminal(row.n)
                                    attachable -> onOpen(row.n)
                                    else -> onBlockedOpen()
                                }
                            },
                            onLongPress = { if (attachable) onOpenTerminal(row.n) else onBlockedOpen() },
                            // Remove routes to the EXISTING confirmed delete
                            // path (DeleteSessionDialog) — not a second path.
                            onRemove = { deleteTarget = row },
                            onLifecycle = { action -> onLifecycle(row.n, action) },
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        DeleteSessionDialog(
            target = target,
            onCancel = { deleteTarget = null },
            onConfirm = { force ->
                onConfirmDelete(target.n, force)
                deleteTarget = null
            },
        )
    }
}

/**
 * UC20 / AC1 — the destructive swipe affordance revealed behind a row as it
 * is dragged LEFT. Brief-mandated black-outlined trash on M3 `error` red
 * (NOT errorContainer — its darker tone would fail black-icon contrast). The
 * icon scales with the drag [progress]; right-aligned because the gesture is
 * end → start.
 */
@Composable
private fun SwipeDeleteBackground(progress: Float) {
    val scale = 0.5f + 0.5f * progress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.delete_icon_description),
            tint = Color.Black,
            modifier = Modifier
                .size(28.dp)
                .scale(scale),
        )
    }
}

@Composable
private fun FilterChipsRow(
    state: SessionsUiState,
    onSelectFilter: (SessionsFilter) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.filter == SessionsFilter.ALL,
            onClick = { onSelectFilter(SessionsFilter.ALL) },
            label = { Text("${stringResource(R.string.sessions_filter_all)} · ${state.countAll}") },
            colors = FilterChipDefaults.filterChipColors(),
        )
        FilterChip(
            selected = state.filter == SessionsFilter.RUNNING,
            onClick = { onSelectFilter(SessionsFilter.RUNNING) },
            label = { Text("${stringResource(R.string.sessions_filter_running)} · ${state.countRunning}") },
        )
        FilterChip(
            selected = state.filter == SessionsFilter.STOPPED,
            onClick = { onSelectFilter(SessionsFilter.STOPPED) },
            label = { Text("${stringResource(R.string.sessions_filter_stopped)} · ${state.countStopped}") },
        )
    }
}

@Composable
private fun EmptyState(filter: SessionsFilter) {
    val msg = when (filter) {
        SessionsFilter.ALL -> stringResource(R.string.sessions_empty_all)
        SessionsFilter.RUNNING -> stringResource(R.string.sessions_empty_running)
        SessionsFilter.STOPPED -> stringResource(R.string.sessions_empty_stopped)
    }
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = msg, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
    }
}

/**
 * UC-70 — the light-grey, visually-subordinate list background shown while the
 * live sessions-events feed is disconnected/reconnecting (or has given up). It
 * is the empty-state treatment (centered, [OnSurfaceMuted] `bodyMedium` —
 * matching [EmptyState]), NOT an alarming error banner (AC1/AC7), and never
 * reintroduces the dismissed UC-52 blocking screen.
 *
 * <p>Lines, top to bottom:
 *  • "Not connected, retrying…" (AC1);
 *  • the current reconnect attempt (AC3);
 *  • a live 1-Hz countdown to the next retry over [SessionsFeedStatus.nextRetryAtMs]
 *    (AC4) — the ticker re-keys on the target so an early/rescheduled attempt
 *    resyncs rather than showing a stale timer;
 *  • a "giving up in mm:ss" line ONLY while [SessionsFeedStatus.giveUpAtMs] is
 *    non-null (AC5) — derived from the controller's budget, so when UC-71 makes
 *    retries unlimited (null) this line simply disappears with no code change.
 *
 * <p>Terminal give-up ([SessionsFeedStatus.Phase.STOPPED]) renders a single
 * static "Not connected" with no countdown (challenger hard-req #4).
 *
 * [nowProvider] is a test seam so an instrumented test can drive the countdown
 * deterministically; production uses the wall clock.
 */
@Composable
private fun RetryingBackground(
    status: SessionsFeedStatus,
    nowProvider: () -> Long = { System.currentTimeMillis() },
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp)
            .testTag("sessions_retrying_background"),
        contentAlignment = Alignment.Center,
    ) {
        if (status.phase == SessionsFeedStatus.Phase.STOPPED) {
            // Hard-req #4 — gave up: static "Not connected", no countdown.
            Text(
                text = stringResource(R.string.sessions_feed_disconnected),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
                modifier = Modifier.testTag("sessions_retrying_disconnected"),
            )
            return@Box
        }

        // AC4 — a 1-Hz ticker. Re-key (remember/effect) on the next-retry target
        // so a new schedule resyncs the clock instead of drifting (Pitfall:
        // countdown accuracy). `giveUpAtMs` is recomputed off the same `now`.
        val target = status.nextRetryAtMs
        var now by remember(target) { mutableStateOf(nowProvider()) }
        LaunchedEffect(target) {
            while (true) {
                now = nowProvider()
                delay(1_000L)
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.sessions_feed_retrying),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.sessions_feed_attempt, status.attempt),
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurfaceMuted,
            )
            if (target != null) {
                val secondsToRetry = ((target - now).coerceAtLeast(0L) + 999L) / 1000L
                Text(
                    text = stringResource(R.string.sessions_feed_next_retry, secondsToRetry),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted,
                    modifier = Modifier.testTag("sessions_retrying_countdown"),
                )
            }
            // AC5 — limit line ONLY when a finite budget exists (controller-
            // derived). Unlimited budget (UC-71) ⇒ giveUpAtMs == null ⇒ omitted.
            val giveUpAt = status.giveUpAtMs
            if (giveUpAt != null) {
                val remainingSec = ((giveUpAt - now).coerceAtLeast(0L) + 999L) / 1000L
                val clock = String.format(Locale.US, "%d:%02d", remainingSec / 60, remainingSec % 60)
                Text(
                    text = stringResource(R.string.sessions_feed_give_up, clock),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted,
                    modifier = Modifier.testTag("sessions_retrying_give_up"),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    row: SessionSummary,
    // UC-28 — the effective (union) state; drives the avatar + pill so the
    // optimistic terminating treatment shows before the server confirms.
    effectiveState: String = row.state,
    // UC-46 — a lifecycle action is in flight for this row (AC6); the overflow
    // menu is disabled while pending or terminating so it can't be double-fired.
    pending: Boolean = false,
    // UC-62 — this is the pinned server host-shell row: render the badge +
    // "Server shell" title, both gestures open the terminal, and the menu
    // offers ONLY Remove.
    isServerSsh: Boolean = false,
    onTap: () -> Unit,
    // UC-37 AC1 — long-press opens the tmux/terminal view; tap opens the
    // structured conversation view. Swipe-left (delete) is unchanged (AC2).
    onLongPress: () -> Unit = {},
    // UC-46 — "Remove" in the overflow menu routes here (the SAME confirmed
    // delete path as swipe-left); the four lifecycle verbs route to onLifecycle.
    onRemove: () -> Unit = {},
    onLifecycle: (LifecycleAction) -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceLow)
            .testTag("session-card-${row.n}")
            // UC-37 AC1/AC2 — tap → conversation, long-press → terminal. The
            // swipe-left delete affordance (UC20) and live-status (UC32) are
            // unchanged; horizontal drags are consumed by the SwipeToDismissBox.
            .combinedClickable(role = Role.Button, onClick = onTap, onLongClick = onLongPress)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SessionAvatar(n = row.n, state = effectiveState, sizeDp = 48)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // UC-62 (AC4) — the host shell shows "Server shell", never
                    // the synthetic "ai-sandbox-0".
                    text = if (isServerSsh) {
                        stringResource(R.string.server_ssh_row_title)
                    } else {
                        "ai-sandbox-${row.n}"
                    },
                    style = AiSandboxMonoTypography.sessionId,
                    color = OnSurface,
                )
                if (isServerSsh) {
                    // UC-62 (AC4) — the distinguishing "SERVER SSH SESSION" badge,
                    // plus a fixed hint; the SSH row has no Claude conversation
                    // name / tmux title / lifecycle state to show.
                    Spacer(Modifier.height(4.dp))
                    ServerSshBadge()
                    Text(
                        text = stringResource(R.string.server_ssh_row_hint),
                        style = AiSandboxMonoTypography.metadata,
                        color = OnSurfaceMuted,
                    )
                } else {
                    if (row.label.isNotBlank()) {
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant,
                        )
                    }
                    // UC-47 — prefer the Claude conversation name as the primary status
                    // line; fall back to the tmux title (which is already normalized to
                    // (idle)/(unavailable)) when no name is known (AC1, AC3). Single line
                    // + ellipsis so a long/odd name never breaks the layout or pushes out
                    // the StatusPill — the middle Column is weight(1f), so the trailing
                    // pill/badge/menu stay fixed (AC5).
                    val statusLine = row.conversationName?.takeIf { it.isNotBlank() } ?: row.tmuxTitle
                    if (statusLine.isNotBlank()) {
                        Text(
                            text = statusLine,
                            style = AiSandboxMonoTypography.metadata,
                            color = OnSurfaceMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // AC2 — discoverable connection-mode hint. UC-46: only show it
                    // for attachable rows; a stopped/paused/terminating row can't be
                    // opened (the row-open gate redirects to a hint), so advertising
                    // "tap to chat" would be misleading.
                    Text(
                        text = if (isAttachable(effectiveState)) {
                            "Tap to chat · hold for terminal"
                        } else {
                            "Use ⋮ to manage this session"
                        },
                        style = AiSandboxMonoTypography.metadata,
                        color = OnSurfaceMuted,
                    )
                }
            }
            // UC-62 — the host shell carries no Claude status (no running/
            // working/pending pill); its identity is the badge in the middle
            // column, so the trailing status stack is suppressed for it.
            if (!isServerSsh) {
                Column(horizontalAlignment = Alignment.End) {
                    // UC-49 / UC-48 — 3-way trailing indicator, all double-gated on a
                    // marker-confirmed `running` row (AC8 here, AC7 for the spinner): a
                    // stale working/pending must never render on a terminating/paused/
                    // stopped row even if it races the state override.
                    //   1. pendingQuestion ⇒ a "?" badge + the pill, NO spinner. PENDING
                    //      TAKES PRECEDENCE (AC5): the session is waiting on the user, not
                    //      working, so the badge replaces the spinner. (The server already
                    //      makes the two mutually exclusive, but the precedence here is the
                    //      client-side guarantee the row never shows both.)
                    //   2. else working ⇒ the working spinner + pill, exactly as UC-48
                    //      (16.dp / 2.dp matching the conversation view's SpinnerRow).
                    //   3. else ⇒ the pill alone.
                    // The pill stays the row's status anchor; the badge/spinner sits to its
                    // left in one Row, coexisting with the conversation name + UC-46 menu
                    // without a layout change (AC7).
                    val running = effectiveState == "running"
                    if (row.pendingQuestion && running) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PendingQuestionBadge()
                            Spacer(Modifier.width(6.dp))
                            StatusPill(state = effectiveState)
                        }
                    } else if (row.working && running) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            StatusPill(state = effectiveState)
                        }
                    } else {
                        StatusPill(state = effectiveState)
                    }
                    if (row.activeStreams > 0) {
                        Spacer(Modifier.height(6.dp))
                        AttachedBadge(count = row.activeStreams)
                    }
                }
            }
            // UC-46 — trailing overflow context menu. Its IconButton onClick is
            // SEPARATE from the row's combinedClickable (IconButton consumes the
            // tap), so tap / long-press / swipe-left on the row are preserved.
            SessionRowMenu(
                n = row.n,
                effectiveState = effectiveState,
                pending = pending,
                // UC-62 (AC8) — the host-shell row's menu offers ONLY Remove
                // (no stop/start/pause/unpause); swipe-to-dismiss still routes
                // to the same Remove flow (AC9).
                serverSshOnly = isServerSsh,
                onRemove = onRemove,
                onLifecycle = onLifecycle,
            )
        }
    }
}

/**
 * UC-84 (AC1/AC2) — the top-bar overflow/hamburger menu seam. Stateless +
 * `internal` so an instrumented test can drive it directly (mirrors the
 * `internal fun SessionsBody` pattern). Exposes exactly two items behind a
 * single [Icons.Filled.MoreVert] button:
 *
 * <ul>
 *   <li>"Start SSH session" — same create-or-focus host-shell behavior as the
 *       old UC-62 shell icon ([onStartSsh]).</li>
 *   <li>"Settings" — navigates to the Settings screen ([onOpenSettings]).</li>
 * </ul>
 *
 * Stable testTags: {@code sessions_overflow_action} (button),
 * {@code sessions_server_ssh_action} (SSH item),
 * {@code sessions_menu_settings} (Settings item).
 */
@Composable
internal fun SessionsOverflowMenu(
    onStartSsh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { menuExpanded = true },
            modifier = Modifier.testTag("sessions_overflow_action"),
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.sessions_overflow_description),
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.server_ssh_action)) },
                modifier = Modifier.testTag("sessions_server_ssh_action"),
                onClick = {
                    menuExpanded = false
                    onStartSsh()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sessions_menu_settings)) },
                modifier = Modifier.testTag("sessions_menu_settings"),
                onClick = {
                    menuExpanded = false
                    onOpenSettings()
                },
            )
        }
    }
}

/**
 * UC-46 — the per-row overflow menu. An [Icons.Filled.MoreVert] button reveals
 * a Material3 [DropdownMenu] with Remove + the four Docker-lifecycle actions.
 *
 * <ul>
 *   <li>The whole menu is disabled (greyed button) while the row is
 *       {@code terminating} or has a lifecycle action in flight ([pending]) —
 *       AC6, so the action can't be double-fired.</li>
 *   <li>Each lifecycle item is enabled only when valid for the current state
 *       ([LifecycleAction.isValidFrom]) — AC3; invalid actions are greyed, not
 *       hidden. "Remove" is always enabled (it routes to the confirmed delete
 *       dialog, valid from any non-terminating state).</li>
 * </ul>
 */
@Composable
private fun SessionRowMenu(
    n: Int,
    effectiveState: String,
    pending: Boolean,
    // UC-62 — when true the menu offers ONLY Remove (the host-shell row has no
    // Docker lifecycle); the four lifecycle items are omitted entirely (AC8).
    serverSshOnly: Boolean = false,
    onRemove: () -> Unit,
    onLifecycle: (LifecycleAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val menuEnabled = effectiveState != "terminating" && !pending
    Box {
        IconButton(
            onClick = { expanded = true },
            enabled = menuEnabled,
            modifier = Modifier.testTag("session-menu-$n"),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.session_menu_description),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_action_remove)) },
                onClick = {
                    expanded = false
                    onRemove()
                },
            )
            // Order per the proposal: Remove, Stop, Start, Pause, Unpause.
            // UC-62 — omitted entirely for the host-shell row (only Remove).
            if (!serverSshOnly) {
                for (action in listOf(
                    LifecycleAction.STOP,
                    LifecycleAction.START,
                    LifecycleAction.PAUSE,
                    LifecycleAction.UNPAUSE,
                )) {
                    DropdownMenuItem(
                        text = { Text(stringResource(lifecycleActionLabel(action))) },
                        enabled = action.isValidFrom(effectiveState),
                        onClick = {
                            expanded = false
                            onLifecycle(action)
                        },
                    )
                }
            }
        }
    }
}

/** UC-46 — string resource for a lifecycle action's menu label. */
private fun lifecycleActionLabel(action: LifecycleAction): Int = when (action) {
    LifecycleAction.STOP -> R.string.session_action_stop
    LifecycleAction.START -> R.string.session_action_start
    LifecycleAction.PAUSE -> R.string.session_action_pause
    LifecycleAction.UNPAUSE -> R.string.session_action_unpause
}

/**
 * UC-46 row-open gate — only these states can be opened (tap → chat,
 * long-press → terminal). {@code stopped} / {@code paused} / {@code terminating}
 * are not attachable; opening one would navigate into a guaranteed-failing
 * connection, so the row surfaces a hint instead.
 */
private fun isAttachable(state: String): Boolean =
    state == "running" || state == "starting" || state == "provisioning"

// ── UC04-2a New session sheet ───────────────────────────────────────────────

@Composable
private fun NewSessionSheet(
    spawning: Boolean,
    onCancel: () -> Unit,
    onSpawn: (String) -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text(
            text = stringResource(R.string.sessions_new_session),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.new_session_help),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceMuted,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = label,
            onValueChange = { if (it.length <= 64) label = it },
            placeholder = { Text(stringResource(R.string.new_session_label_hint)) },
            singleLine = true,
            enabled = !spawning,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel, enabled = !spawning) {
                Text(stringResource(R.string.new_session_cancel))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onSpawn(label) }, enabled = !spawning) {
                Text(stringResource(R.string.new_session_spawn))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── UC04-2b Delete dialog ───────────────────────────────────────────────────

@Composable
internal fun DeleteSessionDialog(
    target: SessionSummary,
    onCancel: () -> Unit,
    onConfirm: (force: Boolean) -> Unit,
) {
    var force by rememberSaveable { mutableStateOf(false) }
    // UC-62 — the host shell never reports attached streams (activeStreams=0)
    // and uses its own remove copy (it's a host tmux, not a sandbox container).
    val isSsh = target.isServerSsh
    val hasAttached = !isSsh && target.activeStreams > 0
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                if (isSsh) {
                    stringResource(R.string.server_ssh_delete_title)
                } else {
                    stringResource(R.string.delete_title, target.n)
                },
            )
        },
        text = {
            Column {
                Text(
                    if (isSsh) {
                        stringResource(R.string.server_ssh_delete_body)
                    } else {
                        stringResource(R.string.delete_body)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (hasAttached) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.delete_attached_warning, target.activeStreams),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Warning,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = force, onCheckedChange = { force = it })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.delete_force),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(force) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(stringResource(R.string.delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.delete_cancel)) }
        },
    )
}

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun trimHost(serverUrl: String?): String =
    serverUrl?.removePrefix("https://")?.removePrefix("http://")?.substringBefore('/') ?: "—"
