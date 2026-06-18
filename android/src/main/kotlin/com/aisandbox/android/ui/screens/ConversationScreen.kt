package com.aisandbox.android.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aisandbox.android.R
import com.aisandbox.android.conversation.AnswerItem
import com.aisandbox.android.conversation.ConversationItem
import com.aisandbox.android.conversation.PendingSheet
import com.aisandbox.android.conversation.ToolDetailState
import com.aisandbox.android.conversation.TurnPhase
import com.aisandbox.android.net.ModelInfo
import com.aisandbox.android.terminal.TerminalStreamController.Companion.SUBAGENT_ID_PREFIX
import com.aisandbox.android.ui.components.AgentSwitcherBar
import com.aisandbox.android.ui.components.Composer
import com.aisandbox.android.ui.components.QuestionSheet
import com.aisandbox.android.ui.components.agentColor
import com.aisandbox.android.ui.components.bubbleTintForSource
import com.aisandbox.android.ui.components.subtleBubbleTint
import com.aisandbox.android.ui.testtags.ConversationTestTags
import com.aisandbox.android.ui.theme.AiSandboxMonoTypography
import com.aisandbox.android.ui.theme.OnSurface
import com.aisandbox.android.ui.theme.OnSurfaceMuted
import com.aisandbox.android.ui.theme.OnSurfaceVariant
import com.aisandbox.android.ui.theme.SurfaceLow
import com.aisandbox.android.ui.theme.Warning
import kotlinx.coroutines.launch

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
    // UC-67 — open the full-screen MCP manager from the overflow menu. Defaults to
    // a no-op so existing call sites / tests that don't wire navigation still compile.
    onOpenMcp: () -> Unit = {},
    viewModel: ConversationViewModel = viewModel(),
) {
    LaunchedEffect(sessionN) { viewModel.attach(sessionN) }

    val state by viewModel.state.collectAsState()
    val items by viewModel.items.collectAsState()
    val targets by viewModel.targets.collectAsState()
    val selectedTargetId by viewModel.selectedTargetId.collectAsState()
    val pendingSheet by viewModel.pendingSheet.collectAsState()
    val turnPhase by viewModel.turnPhase.collectAsState()
    // UC-78 — true while history replays over SSE; drives the instant (no-animation) anchor.
    val backfilling by viewModel.backfilling.collectAsState()
    // UC-79 — older-page (infinite scroll) state: loading affordance + stop-at-start gate.
    val loadingOlder by viewModel.loadingOlder.collectAsState()
    val atTranscriptStart by viewModel.atTranscriptStart.collectAsState()
    val toolDetail by viewModel.toolDetail.collectAsState()
    // UC-66 — model-selection dialog state + the highlighted last-selected model (AC5).
    val modelMenu by viewModel.modelMenu.collectAsState()
    val selectedModelId by viewModel.selectedModelId.collectAsState()
    // UC-53 — live appearance prefs scoped to this transcript only (AC2/AC3).
    val fontScale by viewModel.fontScale.collectAsState()
    val useAgentColor by viewModel.useAgentColor.collectAsState()

    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // UC-60/UC-90 — a selected background-subagent pill is a READ-ONLY view: it has no pane to
    // inject into and the server hard-blocks input to a `subagent:` id, so the answer box is
    // suppressed and the composer disabled. Computed once at screen level (UC-90) because both
    // the bottom-bar composer gating AND the new top-anchored question slot read it.
    val readOnly = selectedTargetId.startsWith(SUBAGENT_ID_PREFIX)

    // UC-81 — one hoisted copy action shared by every bubble (both sides + teammate) and
    // the UC-41 tool-detail popup. Places the FULL text on the system clipboard via the
    // same AnnotatedString pattern as ServerIdentityChangedScreen. Confirmation follows
    // platform convention: a Toast only on API < 33; on Android 13+ the system surfaces its
    // own clipboard UI, so a redundant toast is suppressed (same SDK gate as SessionsScreen).
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedConfirmation = stringResource(R.string.conversation_copied)
    val onCopy: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, copiedConfirmation, Toast.LENGTH_SHORT).show()
        }
    }

    // UC-78/UC-89 — the auto-follow / anchor-to-bottom state machine lives in
    // [ConversationContent]. It owns a single explicit `autoFollow` flag (one scroll
    // mechanism, no double-scroll): replay snaps instantly, live growth sticks only while
    // followed, and a jump-to-bottom button + unread badge surface when the user scrolls up.

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
                        onModel = {
                            menuOpen = false // close the menu, then open the model dialog (AC2)
                            viewModel.loadModels() // sets ModelMenuState != Idle, which shows the dialog
                        },
                        onMcp = {
                            menuOpen = false // UC-67 AC1/AC2 — close the menu, then open the full-screen MCP manager
                            onOpenMcp()
                        },
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
            // UC-90 — the answer box no longer lives here. It has moved to a top-anchored,
            // collapsible slot in the content Column (see [AnchoredQuestionBox]) so the
            // conversation list can scroll beneath it. The bottom bar keeps ONLY the composer.
            // Composer gating is UNCHANGED: it stays locked while a question is pending
            // (collapsed or not), so collapsing the question never re-enables free-form input.
            Composer(
                modifier = Modifier.imePadding().navigationBarsPadding(),
                enabled = !readOnly && pendingSheet == null,
                onSubmit = viewModel::submitComposer,
                readOnly = readOnly,
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AgentSwitcherBar(
                targets = targets,
                selectedTargetId = selectedTargetId,
                onSelect = viewModel::selectTarget,
            )
            ConnectionBanner(state)
            // UC-90 — the pending question/plan box, anchored ABOVE the scrollable transcript
            // (a sibling of the list Box, NOT inside [ConversationContent], so the UC-89
            // auto-follow list + jump-to-bottom FAB stay byte-untouched and keep working while
            // a question is anchored — AC10). Expanded by default; collapses to a header bar so
            // the user can read/scroll the messages beneath it (AC1/AC2). Suppressed in the
            // read-only subagent view, matching the composer gating.
            if (!readOnly) {
                pendingSheet?.let { sheet ->
                    AnchoredQuestionBox(
                        sheet = sheet,
                        onSubmit = viewModel::submitAnswer,
                        onSubmitBatch = viewModel::submitAnswerBatch,
                    )
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ConversationContent(
                    items = items,
                    modifier = Modifier.fillMaxSize(),
                    listState = listState,
                    backfilling = backfilling,
                    loadingOlder = loadingOlder,
                    atTranscriptStart = atTranscriptStart,
                    onLoadOlder = viewModel::loadOlder,
                    fontScale = fontScale,
                    useAgentColor = useAgentColor,
                    onCopy = onCopy,
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
            ToolDetailDialog(state = detail, onDismiss = viewModel::closeDetail, onCopy = onCopy)
        }
        // UC-66 — the model-selection dialog. Shown whenever the catalogue fetch is in any
        // non-Idle state; dismissing resets it to Idle and changes nothing (AC6).
        if (modelMenu != ModelMenuState.Idle) {
            ModelSelectionDialog(
                state = modelMenu,
                selectedModelId = selectedModelId,
                onSelect = { id ->
                    viewModel.selectModel(id) // AC4 — routes `/model <id>` to the current target
                    viewModel.dismissModelMenu()
                },
                onDismiss = viewModel::dismissModelMenu,
            )
        }
    }
}

/**
 * UC-90 — the top-anchored, collapsible answer box. Wraps the existing [QuestionSheet]
 * (rendered UNCHANGED) with an always-present header bar carrying a short label and a
 * collapse/expand toggle. Lives in a top slot of the chat screen, above the scrollable
 * transcript, so the conversation can scroll beneath it (AC1/AC2) while the question stays
 * pending and answerable (AC6). `internal` so same-package instrumented tests can drive it
 * (same seam style as [ConversationContent]).
 *
 *  - **Expanded by default** ([collapsed] = false), keyed on `sheet.questionUuid` so a NEW
 *    question re-expands and the state survives rotation (AC3).
 *  - **Collapsed** shows only the header bar (AC4); the whole multi-question group collapses
 *    or expands as ONE unit because the entire [QuestionSheet] is the collapse target (AC7).
 *  - **State preservation (AC5) — MOUNTED-ZERO-HEIGHT, never unmounted.** The [QuestionSheet]
 *    subtree stays in composition at all times; collapsing only swaps its modifier to
 *    `height(0.dp).clipToBounds()` + `clearAndSetSemantics {}`. Because the subtree stays
 *    COMPOSED, QuestionSheet's `remember(questionUuid)` selections / free-text / paged
 *    `current` SURVIVE a collapse→expand cycle. `clearAndSetSemantics` frees space (AC8) and
 *    removes it from the a11y/semantics tree while collapsed (AC11) — so collapsed-state tests
 *    assert with `assertDoesNotExist()`. `LocalFocusManager.clearFocus()` is called on collapse
 *    so a now-hidden "Other" text field can't keep the IME open.
 *  - **No internal height cap (AC8):** the expanded box is bounded only by a fraction of the
 *    available height (so a very tall group can't starve the list to zero before the user can
 *    collapse it) and given ONE viewport-bounded scroll region so Send / Next / Submit stay
 *    reachable; otherwise it grows naturally and collapse is the mitigation.
 */
@Composable
internal fun AnchoredQuestionBox(
    sheet: PendingSheet,
    onSubmit: (questionUuid: String, questionIndex: Int, selections: List<Int>, freeText: String) -> Unit,
    onSubmitBatch: (questionUuid: String, items: List<AnswerItem>) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    // Expanded by default; resets per question (AC3) and survives rotation/process death.
    var collapsed by rememberSaveable(sheet.questionUuid) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // ONE hoisted scroll state so the scroll position survives the collapse→expand modifier swap.
    val sheetScroll = rememberScrollState()

    val pendingLabel = stringResource(R.string.conversation_question_pending_label)
    val shortLabel = questionShortLabel(sheet, pendingLabel)
    val collapseDescription = stringResource(R.string.conversation_question_collapse)
    val expandDescription = stringResource(R.string.conversation_question_expand)

    Column(modifier = modifier.fillMaxWidth().testTag("question_anchor")) {
        // Always-present header bar: short label + collapse/expand toggle (AC4).
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = shortLabel,
                style = MaterialTheme.typography.labelLarge,
                color = OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    // Drop focus BEFORE hiding the sheet so a hidden OutlinedTextField can't
                    // keep the soft keyboard up over the now-collapsed box.
                    if (!collapsed) focusManager.clearFocus()
                    collapsed = !collapsed
                },
                modifier = Modifier.testTag("question_collapse_toggle"),
            ) {
                Icon(
                    imageVector = if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = if (collapsed) expandDescription else collapseDescription,
                )
            }
        }

        // The expanded sheet stays in composition always (state preservation, AC5). When
        // collapsed it is forced to zero height + cleared from semantics; when expanded it is
        // bounded by a fraction of the available height and scrolls within that bound (AC8).
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val maxExpandedHeight = maxHeight * 0.6f
            val sheetModifier = if (collapsed) {
                Modifier
                    .height(0.dp)
                    .clipToBounds()
                    .clearAndSetSemantics {}
            } else {
                Modifier
                    .heightIn(max = maxExpandedHeight)
                    .verticalScroll(sheetScroll)
            }
            Box(modifier = sheetModifier) {
                QuestionSheet(
                    sheet = sheet,
                    onSubmit = onSubmit,
                    onSubmitBatch = onSubmitBatch,
                )
            }
        }
    }
}

/**
 * UC-90 — the compact label shown in the [AnchoredQuestionBox] header (and the collapsed
 * bar). Plan approval → "Plan approval"; a question group → the first question's `header`,
 * falling back to its `question` text, falling back to [pendingLabel] (the localized
 * "Question pending"). Pure + `internal` so QA can unit-test it directly.
 */
internal fun questionShortLabel(sheet: PendingSheet, pendingLabel: String): String = when (sheet) {
    is PendingSheet.Plan -> "Plan approval"
    is PendingSheet.Questions ->
        sheet.questions.firstOrNull()
            ?.let { q -> q.header.ifBlank { q.question } }
            ?.ifBlank { pendingLabel }
            ?: pendingLabel
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
    onModel: () -> Unit,
    // UC-67 — defaulted so existing same-package tests constructing this menu still compile.
    onMcp: () -> Unit = {},
    onClear: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // UC-66 — opens the model-selection dialog for the current target (AC1).
        DropdownMenuItem(
            text = { Text("Model") },
            onClick = onModel,
        )
        // UC-67 — opens the full-screen MCP management screen for this session (AC1).
        DropdownMenuItem(
            text = { Text("MCP") },
            onClick = onMcp,
        )
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

/** UC-79 — how close to the top (in items) the user must scroll before the next older page is prefetched (AC2). */
private const val LOAD_OLDER_PREFETCH_THRESHOLD = 3

/** UC-79 — stable LazyColumn key for the top loading affordance, kept distinct from any item key (AC3). */
private const val LOADING_OLDER_ROW_KEY = "__loading_older__"

/**
 * The scrollable transcript list. Extracted from [ConversationScreen] as an
 * `internal` seam so same-package instrumented tests can render representative
 * conversation items deterministically.
 *
 * UC-78/UC-89 — this seam OWNS auto-follow and the jump-to-bottom affordance via a
 * single explicit state machine (one scroll mechanism — NOT the old UC-78 anchor
 * layered with a second scroll, which would double-scroll). Three hoisted pieces of
 * `rememberSaveable` state drive it: `autoFollow` (are we pinned to the bottom?),
 * `unreadCount` (messages that arrived while scrolled up), and `lastBottomKey` (the
 * last trailing key we reconciled — parity with the UC-79 anchor).
 *
 *  - **Effect A (settle reconcile)** watches `listState.isScrollInProgress` and, on
 *    each settle, sets `autoFollow`/`unreadCount` from whether we ended `atBottom`.
 *    This is the single source for "button appears on scroll-up" (AC2) and "manual
 *    return to bottom re-engages follow + clears the badge + hides the button"
 *    (AC8). Keying on settle (not live `atBottom`) means an append that briefly
 *    pushes content below the fold can't spuriously disengage follow.
 *  - **Effect B (content-driven)** keyed on the last item's KEY (a top-prepend keeps
 *    the last key, so older-page loads never yank the viewport — the UC-79 contract):
 *      - empty list → re-arm (`autoFollow=true`, clear badge) for AC9 switches;
 *      - replay / first content → snap INSTANTLY via `scrollToItem` (AC1/AC6/AC9);
 *      - followed & no active gesture → `animateScrollToItem`, the stick incl.
 *        streaming (AC5/AC7);
 *      - else (scrolled up, or a message landed mid-gesture) → DON'T move; just bump
 *        the unread badge (AC6), gated to user-perceived messages.
 *  - **[atBottom]** is a `derivedStateOf` over `layoutInfo`: last visible index within
 *    ~1 of `totalItemsCount` (the small tolerance, AC1); empty list counts as bottom.
 *
 * The button (a `SmallFloatingActionButton` + numeric `Badge`) overlays the list at
 * `BottomEnd`, inside the list [Box] and above the composer (which lives in the
 * Scaffold bottom bar), so it never obscures input and respects insets (AC10).
 * `backfilling` is read as a plain value — NEVER a [LaunchedEffect] key. The working
 * spinner stays in [ConversationScreen] as a sibling below this list.
 */
@Composable
internal fun ConversationContent(
    items: List<ConversationItem>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    backfilling: Boolean = false,
    // UC-79 — older-page (infinite scroll) wiring. [loadingOlder] shows the top affordance
    // (AC3) and gates the trigger; [atTranscriptStart] stops paging at the start (AC4);
    // [onLoadOlder] requests the next older page when the user scrolls near the top (AC2).
    loadingOlder: Boolean = false,
    atTranscriptStart: Boolean = false,
    onLoadOlder: () -> Unit = {},
    fontScale: Float = 1f,
    useAgentColor: Boolean = false,
    // UC-81 — copy a bubble's full text to the clipboard (defaulted so existing call
    // sites / instrumented tests that don't wire copy still compile).
    onCopy: (String) -> Unit = {},
    onToolTap: (String) -> Unit = {},
) {
    // UC-89 — the auto-follow state machine. `autoFollow` is the single explicit "pinned to
    // bottom?" flag (replacing UC-78's implicit at-bottom decision so there is ONE scroll
    // mechanism, never two layered into a double-scroll). `unreadCount` is the badge value.
    // `lastBottomKey` tracks the last trailing key we reconciled (parity with the UC-79
    // anchor). All three are `rememberSaveable` so they survive rotation/process death.
    var autoFollow by rememberSaveable { mutableStateOf(true) }
    var unreadCount by rememberSaveable { mutableStateOf(0) }
    // UC-78/UC-79 (AC8) — keyed on the LAST item's KEY, not items.size. A top-prepend (older
    // page) grows the size WITHOUT changing the last item, so it must never yank the viewport
    // to the bottom (the UC-79 contract). A genuinely new trailing message changes the last
    // key and re-fires Effect B.
    var lastBottomKey by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // UC-89 (AC1) — "at bottom" within a small tolerance: the last visible item is within ~1
    // of the end. Uses `totalItemsCount` (not items.size) so it stays correct whether or not
    // the UC-79 loading-older row is present. An empty list counts as bottom.
    val atBottom by remember(listState) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            if (total == 0) return@derivedStateOf true
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= total - 2
        }
    }

    // UC-89 Effect A — reconcile auto-follow on every scroll SETTLE (transition of
    // isScrollInProgress → false), NOT on live `atBottom`. Settling at the bottom re-engages
    // follow and clears the badge (AC8, and the AC4 tail after a tap-driven animation lands);
    // settling away from the bottom suppresses follow so the button shows (AC2). Keying on
    // settle is deliberate: an append that momentarily pushes new content below the fold (so
    // `atBottom` flickers false for a frame) must NOT disengage follow — only a real scroll
    // gesture that comes to rest off-bottom should.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { inProgress ->
                if (!inProgress) {
                    if (atBottom) {
                        autoFollow = true
                        unreadCount = 0
                    } else {
                        autoFollow = false
                    }
                }
            }
    }

    // UC-89 Effect B — content-driven follow + unread counting. Keyed identically to the old
    // UC-78 anchor (last item's key) so a top-prepend still no-ops and live trailing growth
    // re-fires. REPLACES the UC-78 anchor body.
    LaunchedEffect(items.lastOrNull()?.key) {
        val lastKey = items.lastOrNull()?.key
        when {
            lastKey == null -> {
                // AC9 — empty list (conversation/target switch arrives as empty→backfill):
                // re-arm so the next content snaps to the bottom with follow on.
                lastBottomKey = null
                autoFollow = true
                unreadCount = 0
            }
            lastKey != lastBottomKey -> {
                val firstAnchor = lastBottomKey == null
                val target = items.size - 1
                when {
                    backfilling || firstAnchor -> {
                        // Replay / first content — instant snap, follow on (AC1/AC6/AC9).
                        listState.scrollToItem(target)
                        autoFollow = true
                        unreadCount = 0
                    }
                    autoFollow && !listState.isScrollInProgress -> {
                        // AC5/AC7 — followed: stick to the newest content, incl. a streaming
                        // message growing in place. The `!isScrollInProgress` guard is
                        // load-bearing: a message landing WHILE the user is mid drag/fling
                        // must not fire a programmatic scroll that fights the active gesture
                        // (UC-89 pitfall). Such a message falls through to the counting branch
                        // below and is reconciled when the gesture settles (Effect A) — do NOT
                        // "simplify" this into an unconditional animateScrollToItem, that
                        // reintroduces the double-scroll this state machine exists to remove.
                        listState.animateScrollToItem(target)
                        unreadCount = 0
                    }
                    else -> {
                        // AC6 — suppressed (scrolled up) OR a message arrived mid-gesture:
                        // never move the viewport; only update the unread badge. Count is
                        // gated to user-perceived messages (see [isUnreadCountable]) so one
                        // assistant reply (thinking + text + N tool blocks) is ONE increment.
                        val oldKey = lastBottomKey
                        val oldIdx = items.indexOfFirst { it.key == oldKey }
                        val added = if (oldIdx >= 0) {
                            // Genuine new trailing content appended after the previous anchor.
                            items.subList(oldIdx + 1, items.size).count { it.isUnreadCountable() }
                        } else {
                            // The previous anchor key is gone → the trailing message grew IN
                            // PLACE (its key folds text.hashCode, which changes as streaming
                            // text grows). A growing message stays ONE message: add 0 so
                            // token-by-token growth never inflates the badge. (Accepted rare
                            // edge per challenger #3: a same-frame in-place growth coalesced
                            // with a brand-new append undercounts the append by one.)
                            0
                        }
                        unreadCount += added
                    }
                }
                lastBottomKey = lastKey
            }
            // else: stable last key (recomposition/rotation/backfill-flip/dedup) — no-op (AC3).
        }
    }

    // UC-79 (AC2) — scroll-anchor preservation across an older-page prepend. The capture
    // (key + pixel offset of the top-visible real item) is taken at trigger time, BEFORE the
    // request; when the prepend lands (this effect re-fires on the new [items]) we re-pin that
    // same item at the same offset via scrollToItem(newIndex, offset) so the viewport doesn't
    // teleport. Cleared once restored; a non-prepend items change (live append) is a no-op
    // because no anchor was captured.
    var pendingAnchorKey by remember { mutableStateOf<String?>(null) }
    var pendingAnchorOffset by remember { mutableStateOf(0) }
    LaunchedEffect(items) {
        val key = pendingAnchorKey ?: return@LaunchedEffect
        val idx = items.indexOfFirst { it.key == key }
        if (idx >= 0) {
            listState.scrollToItem(idx, pendingAnchorOffset)
        }
        pendingAnchorKey = null
    }
    // UC-79 — release a stale captured anchor when paging is (re-)enabled. A page that
    // prepended NOTHING (cursor already at the start, or an empty page) doesn't change
    // [items], so the restore above never fires and the cascade guard would stay armed; a
    // later reconnect/target switch flips [atTranscriptStart] back to false, and this clears
    // it then. On a real prepend [atTranscriptStart] doesn't change, so this never races the
    // restore above.
    LaunchedEffect(atTranscriptStart) {
        if (!atTranscriptStart) pendingAnchorKey = null
    }

    // UC-79 (AC2) — scroll-up trigger: when the first visible item is within the prefetch
    // threshold of the top (and we're not already loading or at the start), capture the
    // anchor and request the next older page. The controller single-in-flights, so a fast
    // fling that fires this repeatedly never produces overlapping fetches.
    LaunchedEffect(listState, atTranscriptStart, loadingOlder, backfilling, items) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { firstVisible ->
                // `!backfilling` (AC1, UC-78): during the initial replay the list is small, so
                // firstVisibleItemIndex is trivially within the prefetch threshold — without this
                // gate the trigger would fire mid-backfill, capture an anchor, and let the
                // restore compete with the bottom-anchor on the next growth (landing one item
                // short of the bottom). No paging until the initial window has finished replaying.
                // `pendingAnchorKey == null` prevents a cascade: while a fired load's anchor
                // restore is still pending we never trigger again, and the restore repositions
                // the viewport (~one page down) so the trigger naturally rests until the user
                // scrolls back up near the top.
                if (!backfilling && !atTranscriptStart && !loadingOlder && pendingAnchorKey == null &&
                    items.isNotEmpty() && firstVisible <= LOAD_OLDER_PREFETCH_THRESHOLD
                ) {
                    val topVisibleKey = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.key is String && it.key != LOADING_OLDER_ROW_KEY }
                        ?.key as? String
                    pendingAnchorKey = topVisibleKey
                    pendingAnchorOffset = listState.firstVisibleItemScrollOffset
                    onLoadOlder()
                }
            }
    }

    // UC-89 — [Box] so the jump-to-bottom button can overlay the list at BottomEnd. The list
    // fills the box; the button sits inside the list area and above the composer (which lives
    // in the Scaffold bottom bar), so it can never obscure input (AC10).
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            // UC-85 — stable testTag so the deterministic gate can locate the transcript list.
            modifier = Modifier.fillMaxSize().testTag(ConversationTestTags.LIST),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // UC-79 (AC3) — top loading affordance while an older page is being fetched/parsed.
            if (loadingOlder) {
                item(key = LOADING_OLDER_ROW_KEY) { LoadingOlderRow() }
            }
            items(items = items, key = { it.key }) { item ->
                ConversationItemRow(item, onToolTap, fontScale, useAgentColor, onCopy)
            }
        }

        // UC-89 — the button is driven by `!autoFollow` (the settle-reconciled flag), NOT live
        // `!atBottom`, so it doesn't flash for a frame during a follow animation. AC4: tapping
        // re-engages follow, clears the badge, and animates to the true last item. The target
        // is `totalItemsCount - 1` (NOT items.size - 1) so the tap lands correctly even while a
        // UC-79 older page is loading (which adds the loading row at the top).
        JumpToBottomButton(
            visible = !autoFollow,
            unreadCount = unreadCount,
            onClick = {
                autoFollow = true
                unreadCount = 0
                scope.launch {
                    val target = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                    listState.animateScrollToItem(target)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }
}

/**
 * UC-89 — the jump-to-bottom affordance: a small circular FAB with a downward arrow and an
 * optional numeric unread [Badge], shown (fading in/out) only when auto-follow is suppressed
 * i.e. the user has scrolled up (AC2/AC3). Tapping it re-engages follow and scrolls to the
 * latest message (wired by the caller). `internal` so same-package instrumented tests can
 * locate it by content description.
 */
@Composable
internal fun JumpToBottomButton(
    visible: Boolean,
    unreadCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val description = stringResource(R.string.conversation_jump_to_bottom)
        BadgedBox(
            badge = {
                // AC3 — numeric unread badge; only while there is something unread. The
                // count is also exposed as a spoken content description for TalkBack.
                if (unreadCount > 0) {
                    val badgeDescription = stringResource(R.string.conversation_unread_badge, unreadCount)
                    Badge(modifier = Modifier.semantics { contentDescription = badgeDescription }) {
                        Text(
                            text = unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
        ) {
            SmallFloatingActionButton(onClick = onClick) {
                // The Icon's contentDescription is merged onto the button for TalkBack and
                // gives tests a stable locator (AC10/AC11).
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = description,
                )
            }
        }
    }
}

/** UC-79 (AC3) — a compact top-of-list spinner shown while an older page is loading. */
@Composable
private fun LoadingOlderRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(ConversationTestTags.LOADING_OLDER),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text("Loading earlier messages…", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
    }
}

@Composable
private fun ConversationItemRow(
    item: ConversationItem,
    onToolTap: (String) -> Unit,
    fontScale: Float,
    useAgentColor: Boolean,
    onCopy: (String) -> Unit = {},
) {
    when (item) {
        is ConversationItem.UserMessage ->
            Bubble(label = null, body = item.text, isUser = true, isSidechain = item.isSidechain, fontScale = fontScale, tint = null, onCopy = onCopy, testTag = ConversationTestTags.BUBBLE_USER)
        is ConversationItem.AssistantMessage -> {
            // UC-53 (AC3/AC4) — toggle ON + a non-null chromatic tint for this
            // source → subtle background; else today's neutral SurfaceLow.
            val tint = if (useAgentColor) bubbleTintForSource(item.source)?.let { subtleBubbleTint(it) } else null
            Bubble(label = labelFor(item.source), body = item.text, isUser = false, isSidechain = item.isSidechain, fontScale = fontScale, tint = tint, onCopy = onCopy, testTag = ConversationTestTags.BUBBLE_ASSISTANT)
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
        // UC-58 (AC1/AC2) — an inbound teammate/subagent message: a distinct, NON-user,
        // LEFT-aligned bubble labelled with the teammate's id and tinted by its colour
        // (reusing the shared UC-53 [agentColor] palette), so it is never confused with the
        // user's own right-aligned messages.
        is ConversationItem.TeammateMessage -> Bubble(
            label = item.teammateId.ifBlank { "teammate" },
            body = item.text,
            isUser = false,
            isSidechain = item.isSidechain,
            fontScale = fontScale,
            tint = null,
            labelColor = agentColor(item.color),
            onCopy = onCopy,
            testTag = ConversationTestTags.BUBBLE_TEAMMATE,
        )
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
internal fun ToolDetailDialog(
    state: ToolDetailState,
    onDismiss: () -> Unit,
    // UC-81 (AC3) — copy the FULL tool-popup info (untruncated input + output). Defaulted
    // so existing same-package instrumented tests constructing this dialog still compile.
    onCopy: (String) -> Unit = {},
) {
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
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // UC-81 (AC3/AC4) — Copy is offered only once the detail has loaded; for
                    // Loading / Unavailable there is nothing meaningful to copy, so it's hidden.
                    if (state is ToolDetailState.Loaded) {
                        TextButton(onClick = { onCopy(toolDetailCopyText(state)) }) {
                            Text(stringResource(R.string.conversation_tool_copy))
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

/**
 * UC-81 (AC3/AC4) — the exact text the tool-popup **Copy** button places on the clipboard:
 * the FULL, untruncated input and output, blank fields rendered as `(empty)` to match the
 * dialog's on-screen presentation. Pure + `internal` so QA can unit-test it directly.
 */
internal fun toolDetailCopyText(state: ToolDetailState.Loaded): String =
    "Input:\n${state.input.ifBlank { "(empty)" }}\n\nOutput:\n${state.result.ifBlank { "(empty)" }}"

/**
 * UC-66 (AC2/AC5/AC6) — the model-selection dialog. Reuses [ToolDetailDialog]'s
 * styling ([Dialog] + rounded [Surface], bounded max-height, scrollable body).
 * Renders the [ModelMenuState]:
 *
 * - [ModelMenuState.Loading] → a spinner (AC2 fetch in flight).
 * - [ModelMenuState.Loaded] → one selectable row per server-reported model, each
 *   showing its human label; the row whose id matches [selectedModelId] is
 *   highlighted with a `✓` and a tinted background (AC5, best-effort).
 * - [ModelMenuState.Empty] → "No models available".
 * - [ModelMenuState.Error] → the failure message.
 *
 * Tapping a row calls [onSelect] (which sends `/model <id>` and dismisses);
 * tapping Close / outside calls [onDismiss], leaving the model unchanged (AC6).
 */
@Composable
internal fun ModelSelectionDialog(
    state: ModelMenuState,
    selectedModelId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = SurfaceLow) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .padding(16.dp),
            ) {
                Text(text = "Select model", style = MaterialTheme.typography.titleSmall, color = OnSurface)
                Spacer(Modifier.height(12.dp))
                Box(modifier = Modifier.weight(1f, fill = false)) {
                    when (state) {
                        ModelMenuState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Loading…", style = MaterialTheme.typography.bodySmall, color = OnSurfaceMuted)
                        }
                        ModelMenuState.Empty -> Text(
                            text = "No models available",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnSurfaceMuted,
                        )
                        is ModelMenuState.Error -> Text(
                            text = "Could not load models: ${state.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Warning,
                        )
                        is ModelMenuState.Loaded -> Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        ) {
                            state.models.forEach { model ->
                                ModelRow(
                                    model = model,
                                    selected = model.id == selectedModelId,
                                    onClick = { onSelect(model.id) },
                                )
                            }
                        }
                        // Idle never reaches here (the caller gates on state != Idle), but the
                        // exhaustive when needs a branch.
                        ModelMenuState.Idle -> Unit
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

/** UC-66 — one tappable model row; highlighted (tint + `✓`) when it is the selected model (AC5). */
@Composable
private fun ModelRow(model: ModelInfo, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = model.label.ifBlank { model.id },
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else OnSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "✓",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    label: String?,
    body: String,
    isUser: Boolean,
    isSidechain: Boolean,
    fontScale: Float = 1f,
    tint: Color? = null,
    labelColor: Color = OnSurfaceMuted,
    // UC-81 — long-press a bubble to copy its FULL body to the clipboard (AC1/AC2/AC4).
    // Defaulted to a no-op so non-copy call sites / previews compile unchanged.
    onCopy: (String) -> Unit = {},
    // UC-85 — stable testTag for the deterministic gate (role-keyed: user/assistant/teammate).
    // Defaulted null so previews / non-gate call sites are unchanged.
    testTag: String? = null,
) {
    // UC-81 (AC6) — TalkBack-discoverable, named "Copy" action so the long-press copy
    // affordance is reachable without the gesture. Resolved here (composable context).
    val copyActionLabel = stringResource(R.string.conversation_copy_message_label)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val maxBubbleWidth = maxWidth * 0.8f
        Column(
            modifier = Modifier.fillMaxWidth().let { if (testTag != null) it.testTag(testTag) else it },
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            if (label != null) {
                Text(
                    text = if (isSidechain) "$label · subagent" else label,
                    style = AiSandboxMonoTypography.metadata.scaledBy(fontScale),
                    color = labelColor,
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
                    // UC-81 (AC6) — long-press copies; the tap is intentionally a no-op so
                    // the gesture layer adds copy WITHOUT introducing a click that could
                    // collide with existing bubble interactions. No `role = Role.Button`.
                    .combinedClickable(onClick = {}, onLongClick = { onCopy(body) })
                    // UC-81 (AC6) — also expose copy as a named TalkBack action for users who
                    // can't perform a long-press gesture.
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(copyActionLabel) {
                                onCopy(body)
                                true
                            },
                        )
                    }
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
 * UC-89 (AC3) — the unread badge counts USER-PERCEIVED messages only, not every transcript
 * block. A single assistant reply may be several [ConversationItem]s (thinking + text + N
 * tool blocks); gating the count to user/assistant/teammate messages makes one reply count
 * as ONE increment rather than several, matching the AC3 "how many new messages" intent.
 */
private fun ConversationItem.isUnreadCountable(): Boolean =
    this is ConversationItem.UserMessage ||
        this is ConversationItem.AssistantMessage ||
        this is ConversationItem.TeammateMessage

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
