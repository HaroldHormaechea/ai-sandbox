package com.aisandbox.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aisandbox.android.R
import com.aisandbox.android.net.SessionSummary
import com.aisandbox.android.ui.components.AttachedBadge
import com.aisandbox.android.ui.components.SessionAvatar
import com.aisandbox.android.ui.components.StatusPill
import com.aisandbox.android.ui.theme.AiSandboxMonoTypography
import com.aisandbox.android.ui.theme.OnSurface
import com.aisandbox.android.ui.theme.OnSurfaceMuted
import com.aisandbox.android.ui.theme.OnSurfaceVariant
import com.aisandbox.android.ui.theme.SurfaceLow
import com.aisandbox.android.ui.theme.Success
import com.aisandbox.android.ui.theme.Warning
import kotlinx.coroutines.launch

/**
 * UC04-2 — large M3 top bar, three filter chips with counts, list of
 * session cards, extended "New session" FAB.
 *
 * <p>Tap a row → [onOpen] (terminal). Long-press a row → confirm dialog
 * (UC04-2b). FAB tap → bottom sheet (UC04-2a).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onOpen: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: SessionsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val coroutineScope = rememberCoroutineScope()

    var showNewSheet by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
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
            onLongPress = { deleteTarget = it },
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

    deleteTarget?.let { target ->
        DeleteSessionDialog(
            target = target,
            onCancel = { deleteTarget = null },
            onConfirm = { force ->
                viewModel.delete(target.n, force)
                deleteTarget = null
            },
        )
    }
}

@Composable
private fun SessionsBody(
    padding: PaddingValues,
    state: SessionsUiState,
    onSelectFilter: (SessionsFilter) -> Unit,
    onOpen: (Int) -> Unit,
    onLongPress: (SessionSummary) -> Unit,
) {
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
                SessionRow(row = row, onTap = { onOpen(row.n) }, onLongPress = { onLongPress(row) })
            }
        }
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

@Composable
private fun SessionRow(
    row: SessionSummary,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceLow)
            .clickable(onClick = onTap)
            .pointerInput(row.n) {
                detectTapGestures(onLongPress = { onLongPress() })
            }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SessionAvatar(n = row.n, state = row.state, sizeDp = 48)
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
                if (row.tmuxTitle.isNotBlank()) {
                    Text(
                        text = row.tmuxTitle,
                        style = AiSandboxMonoTypography.metadata,
                        color = OnSurfaceMuted,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                StatusPill(state = row.state)
                if (row.activeStreams > 0) {
                    Spacer(Modifier.height(6.dp))
                    AttachedBadge(count = row.activeStreams)
                }
            }
        }
    }
}

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
private fun DeleteSessionDialog(
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
