package com.aisandbox.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aisandbox.android.terminal.StreamTarget
import com.aisandbox.android.terminal.TerminalStreamController.Companion.MAIN_TARGET_ID
import com.aisandbox.android.ui.theme.Accent
import com.aisandbox.android.ui.theme.AccentContainer
import com.aisandbox.android.ui.theme.AiSandboxMonoTypography
import com.aisandbox.android.ui.theme.OnAccent
import com.aisandbox.android.ui.theme.OnAccentContainer
import com.aisandbox.android.ui.theme.OnSurfaceVariant
import com.aisandbox.android.ui.theme.SurfaceLow

/**
 * UC-21 § C — the agent-team switcher. A horizontal row of selectable boxes
 * (styled like the [ModifierBar] segment) below the title/menu bar and above
 * the terminal. One box per stream target — the always-present main session
 * plus any running agent-team members.
 *
 * <ul>
 *   <li>AC#9 — each box is labeled with the agent's identity and carries its
 *       per-agent color dot.</li>
 *   <li>AC#10 — the main session is always present and always first.</li>
 *   <li>AC#11 — tapping a box selects it; the selected box is highlighted.</li>
 *   <li>AC#12 — the row is hidden when only the main target is present (no team
 *       running).</li>
 * </ul>
 */
@Composable
fun AgentSwitcherBar(
    targets: List<StreamTarget>,
    selectedTargetId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // AC#12 — hide unless at least one non-main target is present.
    if (targets.none { it.id != MAIN_TARGET_ID }) return

    // AC#10 — main always first; the rest keep server order.
    val ordered = targets.sortedByDescending { it.id == MAIN_TARGET_ID }

    Surface(modifier = modifier.fillMaxWidth(), color = SurfaceLow) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ordered.forEach { target ->
                AgentTile(
                    target = target,
                    selected = target.id == selectedTargetId,
                    onClick = { onSelect(target.id) },
                )
            }
        }
    }
}

@Composable
private fun AgentTile(
    target: StreamTarget,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) Accent else AccentContainer
    val fg = if (selected) OnAccent else OnAccentContainer
    val label = labelFor(target)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // AC#9 — per-agent color dot (the main session has no agent color).
        if (target.id != MAIN_TARGET_ID) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(agentColor(target.agentColor)),
            )
        }
        Text(
            text = label,
            style = AiSandboxMonoTypography.terminalSmall,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun labelFor(target: StreamTarget): String = when {
    target.id == MAIN_TARGET_ID -> "main"
    !target.agentName.isNullOrBlank() -> target.agentName!!
    target.title.isNotBlank() -> target.title
    else -> target.id
}

/**
 * Map a Claude Code agent color name to a display color. Unknown / null names
 * fall back to the mono-warm [Accent] so an unrecognised upstream color never
 * breaks the row.
 */
private fun agentColor(name: String?): Color = when (name?.lowercase()) {
    "red" -> Color(0xFFFF6B68)
    "green" -> Color(0xFF8AD6A5)
    "yellow" -> Color(0xFFFFD479)
    "blue" -> Color(0xFF7CA8FF)
    "magenta", "purple", "pink" -> Color(0xFFD79AE0)
    "cyan", "teal" -> Color(0xFF79D6D6)
    "orange" -> Color(0xFFFFB784)
    "white", "gray", "grey" -> OnSurfaceVariant
    else -> Accent
}
