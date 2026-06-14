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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
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
 * UC-67 — full-screen MCP management view for a session (opened from the
 * conversation overflow menu). Lists the session's MCP servers and their state
 * (AC2/AC3/AC4), with per-server controls: an authenticate/login control for
 * servers that need it and a reconnect affordance otherwise (AC5), each enabled
 * by state. Invoking a control drives the server-side action and re-fetches so
 * the row reflects the result (AC6). An empty inventory shows a clear message
 * (AC7). Mirrors [SettingsScreen]'s full-screen Scaffold + TopAppBar + ArrowBack.
 *
 * <p><b>Honesty:</b> login only INITIATES authentication — it surfaces the flow in
 * the session's live Claude; the snackbar copy says to complete it there. The
 * server cannot complete an OAuth login headlessly, so the screen never implies
 * it has.
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

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(actionMessage) {
        val msg = actionMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeActionMessage()
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
                    Text(
                        text = "No MCP servers for this session",
                        style = MaterialTheme.typography.bodyLarge,
                        color = OnSurfaceMuted,
                    )
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
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterStatus(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

/**
 * UC-67 — one MCP server row: name + state chip on top, the raw connection detail
 * below, and the state-driven controls. Login is enabled only for a `needs_auth`
 * server (AC5); Reconnect is enabled otherwise. Both are disabled while any
 * action is in flight, and a per-row spinner shows on the server being operated.
 */
@Composable
private fun McpServerRow(
    server: McpServerInfo,
    busy: Boolean,
    anyBusy: Boolean,
    onLogin: () -> Unit,
    onReconnect: () -> Unit,
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
                if (busy) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
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
