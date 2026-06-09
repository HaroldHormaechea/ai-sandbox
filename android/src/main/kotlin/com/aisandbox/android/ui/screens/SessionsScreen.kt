package com.aisandbox.android.ui.screens

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
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aisandbox.android.R
import com.aisandbox.android.net.LifecycleAction
import com.aisandbox.android.net.SessionSummary
import com.aisandbox.android.ui.components.AttachedBadge
import com.aisandbox.android.ui.components.PendingQuestionBadge
import com.aisandbox.android.ui.components.SessionAvatar
import com.aisandbox.android.ui.components.StatusPill
import com.aisandbox.android.ui.theme.AiSandboxMonoTypography
import com.aisandbox.android.ui.theme.OnSurface
import com.aisandbox.android.ui.theme.OnSurfaceMuted
import com.aisandbox.android.ui.theme.OnSurfaceVariant
import com.aisandbox.android.ui.theme.SurfaceLow
import com.aisandbox.android.ui.theme.Success
import com.aisandbox.android.ui.theme.Warning
import kotlinx.coroutines.awaitCancellation
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
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Success),
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
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
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
        SessionsBody(
            padding = innerPadding,
            state = state,
            onSelectFilter = viewModel::selectFilter,
            onOpen = onOpen,
            onOpenTerminal = onOpenTerminal,
            onConfirmDelete = viewModel::delete,
            onLifecycle = viewModel::lifecycle,
            onBlockedOpen = {
                // UC-46 row-open gate — a non-attachable row (stopped / paused
                // / terminating) surfaces a hint instead of navigating into a
                // guaranteed-failing connection.
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        message = context.getString(R.string.session_not_running_hint),
                        duration = SnackbarDuration.Short,
                    )
                }
            },
        )
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
        if (state.visible.isEmpty()) {
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
                val attachable = isAttachable(effectiveState)
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
                            // UC-46 row-open gate — only attachable states
                            // navigate; otherwise surface the hint.
                            onTap = { if (attachable) onOpen(row.n) else onBlockedOpen() },
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
                    text = "ai-sandbox-${row.n}",
                    style = AiSandboxMonoTypography.sessionId,
                    color = OnSurface,
                )
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
            // UC-46 — trailing overflow context menu. Its IconButton onClick is
            // SEPARATE from the row's combinedClickable (IconButton consumes the
            // tap), so tap / long-press / swipe-left on the row are preserved.
            SessionRowMenu(
                n = row.n,
                effectiveState = effectiveState,
                pending = pending,
                onRemove = onRemove,
                onLifecycle = onLifecycle,
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
    val hasAttached = target.activeStreams > 0
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.delete_title, target.n)) },
        text = {
            Column {
                Text(stringResource(R.string.delete_body), style = MaterialTheme.typography.bodyMedium)
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
