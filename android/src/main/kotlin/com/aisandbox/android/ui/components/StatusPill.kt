package com.aisandbox.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aisandbox.android.ui.theme.OnSurfaceMuted
import com.aisandbox.android.ui.theme.OnSurfaceVariant
import com.aisandbox.android.ui.theme.Outline
import com.aisandbox.android.ui.theme.OutlineVariant
import com.aisandbox.android.ui.theme.Success
import com.aisandbox.android.ui.theme.SurfaceLow
import com.aisandbox.android.ui.theme.Warning

/**
 * UC04 § Components — status pill driven by the server's
 * `running | starting | provisioning | stopped` value set (AC37 + UC-27).
 * Visual rules:
 *
 * <ul>
 *   <li><b>running</b>: filled chip in success-green territory; solid
 *       dot leading the label.</li>
 *   <li><b>starting</b>: outlined chip in warning-amber tone, hollow
 *       dot. AC9 also outlines the avatar in 2 dp warning-amber on
 *       optimistic insertion — that's a separate component decision in
 *       [SessionAvatar].</li>
 *   <li><b>provisioning</b> (UC-27): the container is up but still
 *       installing its spawn-time toolchains. Reuses the `starting`
 *       amber/hollow-dot treatment; labelled "installing…".</li>
 *   <li><b>stopped</b>: gray subdued chip.</li>
 * </ul>
 *
 * The displayed label is mapped from the wire token ("installing…" for
 * `provisioning`, otherwise the token verbatim); unknown tokens render
 * raw so a future server state still shows something meaningful.
 */
@Composable
fun StatusPill(state: String, modifier: Modifier = Modifier) {
    val palette = when (state) {
        "running" -> Palette(bg = Success.copy(alpha = 0.18f), fg = Success, dot = Success, dotFilled = true)
        "starting", "provisioning" ->
            Palette(bg = Warning.copy(alpha = 0.18f), fg = Warning, dot = Warning, dotFilled = false)
        else -> Palette(bg = SurfaceLow, fg = OnSurfaceMuted, dot = OutlineVariant, dotFilled = true)
    }
    val label = when (state) {
        "running" -> "running"
        "starting" -> "starting"
        "provisioning" -> "installing…"
        "stopped" -> "stopped"
        else -> state
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(palette.bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .then(
                        if (palette.dotFilled) Modifier.background(palette.dot)
                        else Modifier.border(width = 1.5.dp, color = palette.dot, shape = CircleShape),
                    ),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = palette.fg,
            )
        }
    }
}

/**
 * Simple chip-like "connected" status used in Settings → Server section.
 * Same shape as [StatusPill] but with a fixed label.
 */
@Composable
fun ConnectedPill(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Success.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Success))
            Text(
                text = "connected",
                style = MaterialTheme.typography.labelSmall,
                color = Success,
            )
        }
    }
}

/**
 * Subdued "x clients attached" badge (when > 0) for the delete dialog
 * and the terminal toolbar.
 */
@Composable
fun AttachedBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(SurfaceLow)
            .border(width = 1.dp, color = Outline.copy(alpha = 0.5f), shape = RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = "attached · $count",
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceVariant,
        )
    }
}

private data class Palette(val bg: androidx.compose.ui.graphics.Color, val fg: androidx.compose.ui.graphics.Color, val dot: androidx.compose.ui.graphics.Color, val dotFilled: Boolean)
