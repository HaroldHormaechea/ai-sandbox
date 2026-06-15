package com.aisandbox.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aisandbox.android.net.McpServerInfo
import com.aisandbox.android.ui.theme.Accent
import com.aisandbox.android.ui.theme.ErrorTone
import com.aisandbox.android.ui.theme.OnSurface
import com.aisandbox.android.ui.theme.OnSurfaceMuted
import com.aisandbox.android.ui.theme.OnSurfaceVariant
import com.aisandbox.android.ui.theme.Success
import com.aisandbox.android.ui.theme.SurfaceLow
import com.aisandbox.android.ui.theme.Warning

/**
 * UC-67 / UC-82 — full-screen MCP management view for a session (opened from the
 * conversation overflow menu). Lists the session's MCP servers and their state
 * (AC2/AC3/AC4), with per-server controls: an authenticate/login control for servers
 * that need it, a reconnect affordance otherwise, and (UC-82) a Remove control. The
 * TopAppBar and the empty state both expose an Add affordance (UC-82 AC1) that opens a
 * transport-aware dialog. Invoking any control drives the server-side action and
 * re-fetches so the list reflects the result live (AC3/AC6). An empty inventory shows a
 * clear message + Add button (AC7). Mirrors [SettingsScreen]'s Scaffold + TopAppBar.
 *
 * <p><b>Honesty:</b> login only INITIATES authentication — it surfaces the flow in the
 * session's live Claude; the snackbar copy says to complete it there. Remove only
 * deregisters: the server's own response message (shown in the snackbar) is explicit
 * that an already-running child keeps running until the next MCP reload (UC-82 AC2). The
 * screen never implies an OAuth login completed headlessly or a process was force-killed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(
    sessionN: Int,
    onBack: () -> Unit,
    viewModel: McpViewModel = viewModel(),
) {
    LaunchedEffect(sessionN) { viewModel.attach(sessionN) }

    val state by viewModel.state.collectAsState()
    val busyServer by viewModel.busyServer.collectAsState()
    val actionMessage by viewModel.actionMessage.collectAsState()
    val addSucceeded by viewModel.addSucceeded.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<McpServerInfo?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(actionMessage) {
        val msg = actionMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeActionMessage()
        }
    }
    // UC-82 — a successful add closes the dialog; a failed one (e.g. 409 duplicate) leaves
    // it open with the user's input intact so they can correct and resubmit.
    LaunchedEffect(addSucceeded) {
        if (addSucceeded) {
            showAddDialog = false
            viewModel.consumeAddSucceeded()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("MCP — ai-sandbox-$sessionN") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add MCP server")
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (val s = state) {
                is McpUiState.Loading -> CenterStatus { CircularProgressIndicator() }
                is McpUiState.Empty -> CenterStatus {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No MCP servers for this session",
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnSurfaceMuted,
                        )
                        Spacer(Modifier.height(12.dp))
                        // AC1 — Add must be reachable even from the empty state.
                        OutlinedButton(onClick = { showAddDialog = true }) { Text("Add MCP server") }
                    }
                }
                is McpUiState.Error -> CenterStatus {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Couldn't load MCP servers",
                            style = MaterialTheme.typography.titleMedium,
                            color = OnSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(text = s.message, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { viewModel.refresh() }) { Text("Retry") }
                    }
                }
                is McpUiState.Loaded -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(s.servers, key = { it.name }) { server ->
                        McpServerRow(
                            server = server,
                            busy = busyServer == server.name,
                            anyBusy = busyServer != null,
                            onLogin = { viewModel.operate(server.name, "login") },
                            onReconnect = { viewModel.operate(server.name, "reconnect") },
                            onRemove = { confirmRemove = server },
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddMcpDialog(
            busy = busyServer != null,
            onDismiss = { showAddDialog = false },
            onConfirm = { name, transport, command, args, url, env, headers ->
                viewModel.add(buildMcpAddRequest(name, transport, command, args, url, env, headers))
            },
        )
    }

    confirmRemove?.let { target ->
        RemoveConfirmDialog(
            serverName = target.name,
            onDismiss = { confirmRemove = null },
            onConfirm = {
                viewModel.remove(target.name)
                confirmRemove = null
            },
        )
    }
}

@Composable
private fun CenterStatus(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

/**
 * UC-67 / UC-82 — one MCP server row: name + state chip on top, the raw connection detail
 * below, and the state-driven controls. Login is enabled only for a `needs_auth` server;
 * Reconnect is enabled otherwise; Remove (UC-82) is always available unless an action is
 * in flight. All controls are disabled while any action runs, and a per-row spinner shows
 * on the server being operated.
 */
@Composable
private fun McpServerRow(
    server: McpServerInfo,
    busy: Boolean,
    anyBusy: Boolean,
    onLogin: () -> Unit,
    onReconnect: () -> Unit,
    onRemove: () -> Unit,
) {
    val needsAuth = server.state.equals("needs_auth", ignoreCase = true)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceLow)
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface,
                    modifier = Modifier.weight(1f),
                )
                StateChip(server.state)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = transportLabel(server.transport) + (if (server.detail.isBlank()) "" else " · ${server.detail}"),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = OnSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onLogin,
                    enabled = needsAuth && !anyBusy,
                ) {
                    Text("Login")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onReconnect,
                    enabled = !needsAuth && !anyBusy,
                ) {
                    Text("Reconnect")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove, enabled = !anyBusy) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Remove ${server.name}",
                        tint = if (anyBusy) OnSurfaceMuted else ErrorTone,
                    )
                }
                if (busy) {
                    Spacer(Modifier.width(4.dp))
                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/**
 * UC-82 — the Add-MCP dialog (AC1). Collects the name, a transport selector, and the
 * transport-conditional fields: stdio → command + args + optional env; http/sse → url +
 * optional headers. The Add button is gated on the minimal required fields client-side;
 * the server is the authoritative validator (AC6). [onConfirm] hands the raw text fields
 * to the caller, which builds the request via [buildMcpAddRequest].
 */
@Composable
private fun AddMcpDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        transport: String,
        command: String,
        argsText: String,
        url: String,
        envText: String,
        headersText: String,
    ) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("stdio") }
    var command by remember { mutableStateOf("") }
    var argsText by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var envText by remember { mutableStateOf("") }
    var headersText by remember { mutableStateOf("") }

    val isStdio = transport == "stdio"
    val canSubmit = name.isNotBlank() &&
        (if (isStdio) command.isNotBlank() else url.isNotBlank()) &&
        !busy

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add MCP server") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Transport", style = MaterialTheme.typography.labelMedium, color = OnSurfaceMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("stdio", "http", "sse").forEach { t ->
                        FilterChip(
                            selected = transport == t,
                            onClick = { transport = t },
                            label = { Text(transportLabel(t)) },
                        )
                    }
                }
                if (isStdio) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("Command") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = argsText,
                        onValueChange = { argsText = it },
                        label = { Text("Args (space-separated)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = envText,
                        onValueChange = { envText = it },
                        label = { Text("Env (one KEY=VALUE per line)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL (http:// or https://)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        label = { Text("Headers (one \"Header: value\" per line)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, transport, command, argsText, url, envText, headersText) },
                enabled = canSubmit,
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}

/**
 * UC-82 — confirm dialog for Remove (AC2). The body is honest that removal only
 * deregisters; the authoritative honest note about an already-running child is carried
 * by the server's response and shown in the snackbar afterwards.
 */
@Composable
private fun RemoveConfirmDialog(
    serverName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove \"$serverName\"?") },
        text = {
            Text(
                "This deregisters the MCP server from this session. An already-running process keeps running "
                    + "until the session's MCP servers are next reloaded — it isn't force-killed.",
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Remove") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** A small state pill colored by the MCP server's coarse state. */
@Composable
private fun StateChip(state: String) {
    val color = when (state.lowercase()) {
        "connected" -> Success
        "needs_auth" -> Warning
        "failed" -> ErrorTone
        "pending" -> Accent
        else -> OnSurfaceMuted
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = stateLabel(state),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/** Human-readable label for an MCP state wire value. */
private fun stateLabel(state: String): String = when (state.lowercase()) {
    "connected" -> "Connected"
    "needs_auth" -> "Needs auth"
    "failed" -> "Failed"
    "pending" -> "Pending"
    else -> "Unknown"
}

/** Human-readable label for the transport hint. */
private fun transportLabel(transport: String): String = when (transport.lowercase()) {
    "stdio" -> "stdio"
    "http" -> "HTTP"
    "sse" -> "SSE"
    else -> "—"
}
