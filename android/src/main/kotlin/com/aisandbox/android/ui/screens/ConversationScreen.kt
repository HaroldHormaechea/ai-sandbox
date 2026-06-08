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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aisandbox.android.conversation.ConversationItem
import com.aisandbox.android.conversation.TurnPhase
import com.aisandbox.android.ui.components.AgentSwitcherBar
import com.aisandbox.android.ui.components.Composer
import com.aisandbox.android.ui.components.QuestionSheet
import com.aisandbox.android.ui.theme.AiSandboxMonoTypography
import com.aisandbox.android.ui.theme.OnSurface
import com.aisandbox.android.ui.theme.OnSurfaceMuted
import com.aisandbox.android.ui.theme.OnSurfaceVariant
import com.aisandbox.android.ui.theme.SurfaceLow
import com.aisandbox.android.ui.theme.Warning

/**
 * UC-37 — the structured "conversation" view (single-tap from the sessions
 * list). A scrollable transcript of typed conversation items (AC3/AC4/AC5), the
 * reused agent switcher (AC16), a thinking/working spinner (AC14/AC15), a pinned
 * question/plan sheet (AC10/AC13), and the local composer (AC7), all driving the
 * SAME live session as the tmux view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    sessionN: Int,
    onBack: () -> Unit,
    viewModel: ConversationViewModel = viewModel(),
) {
    LaunchedEffect(sessionN) { viewModel.attach(sessionN) }

    val state by viewModel.state.collectAsState()
    val items by viewModel.items.collectAsState()
    val targets by viewModel.targets.collectAsState()
    val selectedTargetId by viewModel.selectedTargetId.collectAsState()
    val pendingSheet by viewModel.pendingSheet.collectAsState()
    val turnPhase by viewModel.turnPhase.collectAsState()

    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Auto-scroll to the newest item as the transcript grows.
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "ai-sandbox-$sessionN",
                        style = AiSandboxMonoTypography.sessionId,
                        color = OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Disconnect") },
                            onClick = {
                                menuOpen = false
                                viewModel.disconnect()
                                onBack()
                            },
                        )
                    }
                },
            )
        },
        bottomBar = {
            Column(modifier = Modifier.imePadding().navigationBarsPadding()) {
                pendingSheet?.let { sheet ->
                    QuestionSheet(sheet = sheet, onSubmit = viewModel::submitAnswer)
                }
                Composer(
                    enabled = pendingSheet == null,
                    onSubmit = viewModel::submitComposer,
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AgentSwitcherBar(
                targets = targets,
                selectedTargetId = selectedTargetId,
                onSelect = viewModel::selectTarget,
            )
            ConnectionBanner(state)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = items, key = { it.key }) { item ->
                        ConversationItemRow(item)
                    }
                }
            }
            SpinnerRow(turnPhase)
        }
    }
}

@Composable
private fun ConnectionBanner(state: TerminalState) {
    val msg = when (state) {
        is TerminalState.Reconnecting -> "Reconnecting… (attempt ${state.attempt})"
        TerminalState.Connecting -> "Connecting…"
        TerminalState.GaveUp -> "Disconnected — reopen to retry"
        TerminalState.Revoked -> "Access revoked"
        is TerminalState.Failed -> "Connection failed (${state.reason})"
        else -> null
    } ?: return
    Box(
        modifier = Modifier.fillMaxWidth().background(SurfaceLow).padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(msg, style = MaterialTheme.typography.labelMedium, color = Warning)
    }
}

@Composable
private fun SpinnerRow(phase: TurnPhase) {
    if (phase == TurnPhase.IDLE) return
    val label = if (phase == TurnPhase.THINKING) "Thinking…" else "Working…"
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
    }
}

@Composable
private fun ConversationItemRow(item: ConversationItem) {
    when (item) {
        is ConversationItem.UserMessage -> Bubble(label = "You", body = item.text, accent = true, item.isSidechain)
        is ConversationItem.AssistantMessage -> Bubble(label = labelFor(item.source), body = item.text, accent = false, item.isSidechain)
        is ConversationItem.Thinking -> MetaLine(prefix = "thinking", body = item.text)
        is ConversationItem.ToolUse -> MetaLine(prefix = "▸ ${item.toolName}", body = item.inputSummary)
        is ConversationItem.ToolResult -> MetaLine(
            prefix = if (item.isError) "✗ result" else "✓ result",
            body = item.summary,
        )
        is ConversationItem.Question -> MetaLine(
            prefix = "❓ question",
            body = item.questions.firstOrNull()?.question ?: "",
        )
        is ConversationItem.PlanApproval -> MetaLine(prefix = "📋 plan", body = item.plan)
    }
}

@Composable
private fun Bubble(label: String, body: String, accent: Boolean, isSidechain: Boolean) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (isSidechain) "$label · subagent" else label,
            style = AiSandboxMonoTypography.metadata,
            color = OnSurfaceMuted,
        )
        Spacer(Modifier.size(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (accent) MaterialTheme.colorScheme.primaryContainer else SurfaceLow)
                .padding(12.dp),
        ) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = if (accent) MaterialTheme.colorScheme.onPrimaryContainer else OnSurface,
            )
        }
    }
}

@Composable
private fun MetaLine(prefix: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = prefix, style = AiSandboxMonoTypography.metadata, color = OnSurfaceVariant)
        if (body.isNotBlank()) {
            Text(
                text = body,
                style = AiSandboxMonoTypography.metadata,
                color = OnSurfaceMuted,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun labelFor(source: String): String = if (source.startsWith("subagent:")) "Agent" else "Claude"
