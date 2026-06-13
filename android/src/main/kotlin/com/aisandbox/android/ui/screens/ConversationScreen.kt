package com.aisandbox.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aisandbox.android.conversation.ConversationItem
import com.aisandbox.android.conversation.ToolDetailState
import com.aisandbox.android.conversation.TurnPhase
import com.aisandbox.android.ui.components.AgentSwitcherBar
import com.aisandbox.android.ui.components.Composer
import com.aisandbox.android.ui.components.QuestionSheet
import com.aisandbox.android.ui.components.bubbleTintForSource
import com.aisandbox.android.ui.components.subtleBubbleTint
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
    val toolDetail by viewModel.toolDetail.collectAsState()
    // UC-53 — live appearance prefs scoped to this transcript only (AC2/AC3).
    val fontScale by viewModel.fontScale.collectAsState()
    val useAgentColor by viewModel.useAgentColor.collectAsState()

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
                    ConversationOverflowMenu(
                        expanded = menuOpen,
                        onClear = {
                            menuOpen = false // AC7 — close the menu after Clear is chosen
                            // AC6 — in-place reset; does NOT disconnect or navigate back.
                            viewModel.clear()
                        },
                        onDisconnect = {
                            menuOpen = false
                            viewModel.disconnect()
                            onBack()
                        },
                        onDismiss = { menuOpen = false },
                    )
                },
            )
        },
        bottomBar = {
            Column(modifier = Modifier.imePadding().navigationBarsPadding()) {
                pendingSheet?.let { sheet ->
                    QuestionSheet(
                        sheet = sheet,
                        onSubmit = viewModel::submitAnswer,
                        onSubmitBatch = viewModel::submitAnswerBatch,
                    )
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
                ConversationContent(
                    items = items,
                    modifier = Modifier.fillMaxSize(),
                    listState = listState,
                    fontScale = fontScale,
                    useAgentColor = useAgentColor,
                    onToolTap = { toolUseId ->
                        // AC5 — resolve the originating tool_use line's uuid (server scoping) and fetch.
                        val uuid = items.firstOrNull {
                            it is ConversationItem.ToolActivity && it.toolUseId == toolUseId
                        }?.uuid ?: ""
                        viewModel.openDetail(toolUseId, uuid)
                    },
                )
            }
            SpinnerRow(turnPhase)
        }
        toolDetail?.let { detail ->
            ToolDetailDialog(state = detail, onDismiss = viewModel::closeDetail)
        }
    }
}

/**
 * UC-65 — the top-bar overflow menu, extracted from [ConversationScreen] as an `internal`
 * seam (same pattern as [ConversationContent]) so same-package instrumented tests can drive
 * the Clear / Disconnect actions deterministically. Pure extraction plus the new **Clear**
 * item, positioned ABOVE **Disconnect** (AC1); both remain reachable.
 */
@Composable
internal fun ConversationOverflowMenu(
    expanded: Boolean,
    onClear: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Clear") },
            onClick = onClear,
        )
        DropdownMenuItem(
            text = { Text("Disconnect") },
            onClick = onDisconnect,
        )
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

/**
 * The scrollable transcript list. Extracted from [ConversationScreen] as an
 * `internal` seam so same-package instrumented tests can render representative
 * conversation items deterministically. Pure extraction — `state`,
 * `contentPadding`, `verticalArrangement`, item keys, and row rendering are
 * verbatim from the inline `LazyColumn` it replaced; no visual or
 * scroll/ordering change. The working spinner stays in [ConversationScreen] as
 * a sibling below this list, exactly as before.
 */
@Composable
internal fun ConversationContent(
    items: List<ConversationItem>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    fontScale: Float = 1f,
    useAgentColor: Boolean = false,
    onToolTap: (String) -> Unit = {},
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = items, key = { it.key }) { item ->
            ConversationItemRow(item, onToolTap, fontScale, useAgentColor)
        }
    }
}

@Composable
private fun ConversationItemRow(
    item: ConversationItem,
    onToolTap: (String) -> Unit,
    fontScale: Float,
    useAgentColor: Boolean,
) {
    when (item) {
        is ConversationItem.UserMessage ->
            Bubble(label = null, body = item.text, isUser = true, isSidechain = item.isSidechain, fontScale = fontScale, tint = null)
        is ConversationItem.AssistantMessage -> {
            // UC-53 (AC3/AC4) — toggle ON + a non-null chromatic tint for this
            // source → subtle background; else today's neutral SurfaceLow.
            val tint = if (useAgentColor) bubbleTintForSource(item.source)?.let { subtleBubbleTint(it) } else null
            Bubble(label = labelFor(item.source), body = item.text, isUser = false, isSidechain = item.isSidechain, fontScale = fontScale, tint = tint)
        }
        is ConversationItem.Thinking -> MetaLine(prefix = "thinking", body = item.text, fontScale = fontScale)
        is ConversationItem.ToolActivity -> ToolBubble(item = item, onTap = { onToolTap(item.toolUseId) }, fontScale = fontScale)
        is ConversationItem.Question -> MetaLine(
            prefix = "❓ question",
            body = item.questions.firstOrNull()?.question ?: "",
            fontScale = fontScale,
        )
        is ConversationItem.PlanApproval -> MetaLine(prefix = "📋 plan", body = item.plan, fontScale = fontScale)
        is ConversationItem.SystemNote -> SystemNoteRow(item, fontScale = fontScale)
    }
}

/**
 * UC-42 (AC4) — a harness-injected `user` line with no host tool bubble, rendered as a
 * collapsed, **left-aligned, non-user** "system note" (MetaLine style — like the
 * tool/question meta rows, NOT the right-aligned user [Bubble]). Tapping toggles the
 * inline [ConversationItem.SystemNote.detail] open/closed, reusing UC-41's
 * collapse/expand affordance; the body is carried inline in the frame, so no
 * `fetch-detail` round-trip is needed.
 */
@Composable
internal fun SystemNoteRow(item: ConversationItem.SystemNote, fontScale: Float = 1f) {
    var expanded by remember(item.key) { mutableStateOf(false) }
    val prefix = if (item.isSidechain) "${item.label} · subagent" else item.label
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp, horizontal = 2.dp),
    ) {
        Text(text = prefix, style = AiSandboxMonoTypography.metadata.scaledBy(fontScale), color = OnSurfaceVariant)
        if (expanded && item.detail.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Text(
                    text = item.detail,
                    style = AiSandboxMonoTypography.metadata.scaledBy(fontScale),
                    color = OnSurfaceMuted,
                )
            }
        }
    }
}

/**
 * UC-41 (AC1/AC2/AC3/AC4/AC7/AC8) — the single collapsed, type-aware tool row. One
 * line: "Skill loaded `<name>`", "Command used: `<~20-char snippet>`…", or
 * "`<tool>`: `<snippet>`…". An error result (AC7) recolors the label and adds a `✗`;
 * a not-yet-arrived result (AC8) shows an "awaiting result" hint. Tapping opens the
 * detail dialog (AC5) via [onTap].
 */
@Composable
internal fun ToolBubble(item: ConversationItem.ToolActivity, onTap: () -> Unit, fontScale: Float = 1f) {
    val isError = item.result?.isError == true
    val awaiting = item.result == null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onTap)
            .padding(vertical = 2.dp, horizontal = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = toolBubbleLabel(item),
                style = AiSandboxMonoTypography.metadata.scaledBy(fontScale),
                color = if (isError) Warning else OnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isError) {
                Spacer(Modifier.width(6.dp))
                Text(text = "✗", style = AiSandboxMonoTypography.metadata.scaledBy(fontScale), color = Warning)
            }
        }
        if (awaiting) {
            Text(
                text = "awaiting result…",
                style = AiSandboxMonoTypography.metadata.scaledBy(fontScale),
                color = OnSurfaceMuted,
            )
        }
        if (item.isSidechain) {
            Text(text = "· subagent", style = AiSandboxMonoTypography.metadata.scaledBy(fontScale), color = OnSurfaceMuted)
        }
    }
}

/** UC-41 — type-aware collapsed label. ~20-char snippet + ellipsis only when truncated (AC2/AC3). */
private fun toolBubbleLabel(item: ConversationItem.ToolActivity): String {
    val value = item.primaryText.ifBlank { item.inputSummary }
    return when (item.toolName) {
        "Skill" -> "Skill loaded ${value.ifBlank { "skill" }}"
        "Bash" -> "Command used: ${toolSnippet(value)}"
        else -> "${item.toolName}: ${toolSnippet(value)}"
    }
}

private const val TOOL_SNIPPET_LEN = 20

private fun toolSnippet(s: String): String =
    if (s.length > TOOL_SNIPPET_LEN) s.take(TOOL_SNIPPET_LEN) + "…" else s

/**
 * UC-41 (AC5/AC6/AC9) — the on-demand detail dialog: scrollable, selectable, bounded
 * max-height for large outputs. Renders the [ToolDetailState]: a spinner while
 * [ToolDetailState.Loading], the full untruncated input + output when
 * [ToolDetailState.Loaded] (the output label recolors on error, AC7), or a clear
 * "Detail unavailable" message when [ToolDetailState.Unavailable] (AC9).
 */
@Composable
internal fun ToolDetailDialog(state: ToolDetailState, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = SurfaceLow) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .padding(16.dp),
            ) {
                Text(text = "Tool detail", style = MaterialTheme.typography.titleSmall, color = OnSurface)
                Spacer(Modifier.height(12.dp))
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    when (state) {
                        ToolDetailState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Loading…", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                        }
                        ToolDetailState.Unavailable -> Text(
                            text = "Detail unavailable",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Warning,
                        )
                        is ToolDetailState.Loaded -> SelectionContainer {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                Text("Input", style = MaterialTheme.typography.labelMedium, color = OnSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = state.input.ifBlank { "(empty)" },
                                    style = AiSandboxMonoTypography.metadata,
                                    color = OnSurface,
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = "Output",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (state.isError) Warning else OnSurfaceVariant,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = state.result.ifBlank { "(empty)" },
                                    style = AiSandboxMonoTypography.metadata,
                                    color = OnSurface,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Close")
                }
            }
        }
    }
}

/**
 * UC-39 — chat-style aligned bubbles. `isUser` encodes the sender side: user
 * messages (isUser=true) align to the right with no label (passed as null —
 * right-alignment signals the sender); assistant/agent messages (isUser=false)
 * align to the left and keep their label plus the `· subagent` annotation. A
 * bubble sizes to its content, capped at ~80% of the list width, after which
 * text wraps. Default soft-wrap (no TextOverflow/softWrap override on the body)
 * keeps long unbroken tokens (URLs/code) inside the cap rather than overflowing.
 */
@Composable
private fun Bubble(
    label: String?,
    body: String,
    isUser: Boolean,
    isSidechain: Boolean,
    fontScale: Float = 1f,
    tint: Color? = null,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.8f
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            if (label != null) {
                Text(
                    text = if (isSidechain) "$label · subagent" else label,
                    style = AiSandboxMonoTypography.metadata.scaledBy(fontScale),
                    color = OnSurfaceMuted,
                )
                Spacer(Modifier.size(2.dp))
            }
            // UC-53 (AC3/AC4) — assistant bubbles take the agent-color [tint] when
            // provided; user bubbles always keep primaryContainer (AC4).
            val background = when {
                isUser -> MaterialTheme.colorScheme.primaryContainer
                tint != null -> tint
                else -> SurfaceLow
            }
            Box(
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    .clip(RoundedCornerShape(12.dp))
                    .background(background)
                    .padding(12.dp),
            ) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium.scaledBy(fontScale),
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else OnSurface,
                )
            }
        }
    }
}

@Composable
private fun MetaLine(prefix: String, body: String, fontScale: Float = 1f) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = prefix, style = AiSandboxMonoTypography.metadata.scaledBy(fontScale), color = OnSurfaceVariant)
        if (body.isNotBlank()) {
            Text(
                text = body,
                style = AiSandboxMonoTypography.metadata.scaledBy(fontScale),
                color = OnSurfaceMuted,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun labelFor(source: String): String = if (source.startsWith("subagent:")) "Agent" else "Claude"

/**
 * UC-53 (AC2) — scale a transcript [TextStyle]'s font (and line height, when
 * specified) by [scale], leaving everything else untouched. Resolves a concrete
 * base sp first so an `Unspecified` font size (which would otherwise throw when
 * multiplied) falls back to the bodyMedium 14sp baseline. Scaling stays local to
 * the conversation-view composables — never the global theme — so other screens
 * are unaffected.
 */
private fun TextStyle.scaledBy(scale: Float): TextStyle {
    if (scale == 1f) return this
    val baseSize = if (fontSize != TextUnit.Unspecified) fontSize else 14.sp
    return copy(
        fontSize = (baseSize.value * scale).sp,
        lineHeight = if (lineHeight != TextUnit.Unspecified) (lineHeight.value * scale).sp else lineHeight,
    )
}
