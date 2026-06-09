package com.aisandbox.android.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aisandbox.android.ui.theme.ErrorTone
import com.aisandbox.android.ui.theme.OnSurfaceMuted
import com.aisandbox.android.ui.theme.OnSurfaceVariant
import com.aisandbox.android.ui.theme.Outline
import com.aisandbox.android.ui.theme.OutlineVariant
import com.aisandbox.android.ui.theme.Success
import com.aisandbox.android.ui.theme.SurfaceHigh
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
 *   <li><b>terminating</b> (UC-28): the session's teardown is in flight.
 *       Destructive-red treatment on the named theme `error` token
 *       ([ErrorTone]); the leading dot pulses (indeterminate "spinner-dot")
 *       to signal an in-progress destructive operation. Labelled
 *       "terminating".</li>
 *   <li><b>paused</b> (UC-46): a frozen, resumable container. Subdued on
 *       the raised surface tone with a hollow dot — distinct from
 *       {@code stopped} (still alive, just suspended). Labelled "paused".</li>
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
        "terminating" ->
            // UC-28 — destructive-red on the named `--error` theme token; the
            // dot pulses (see `pulse = true`) instead of adding a full
            // CircularProgressIndicator, staying within the existing dot
            // vocabulary.
            Palette(bg = ErrorTone.copy(alpha = 0.18f), fg = ErrorTone, dot = ErrorTone, dotFilled = true, pulse = true)
        "paused" ->
            // UC-46 — a frozen, resumable container. Subdued like `stopped`
            // but distinguished by a HOLLOW dot on the raised surface tone, so
            // "suspended" reads differently from "torn down". OnSurfaceVariant
            // (brighter than the muted stopped tone) signals it is still alive.
            Palette(bg = SurfaceHigh, fg = OnSurfaceVariant, dot = OnSurfaceVariant, dotFilled = false)
        else -> Palette(bg = SurfaceLow, fg = OnSurfaceMuted, dot = OutlineVariant, dotFilled = true)
    }
    val label = when (state) {
        "running" -> "running"
        "starting" -> "starting"
        "provisioning" -> "installing…"
        "terminating" -> "terminating"
        "paused" -> "paused"
        "stopped" -> "stopped"
        else -> state
    }
    // UC-28 — indeterminate pulse for the terminating dot. rememberInfiniteTransition
    // animates the dot's alpha; non-terminating pills hold a static 1f.
    val dotAlpha = if (palette.pulse) {
        val transition = rememberInfiniteTransition(label = "terminating-pulse")
        val animated by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 650),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "terminating-dot-alpha",
        )
        animated
    } else {
        1f
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
                    .alpha(dotAlpha)
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

/**
 * UC-49 — the "?" badge shown in a session row's trailing status area when that
 * session's Claude is blocked on an [AskUserQuestion] awaiting an answer (a "needs
 * your input" affordance). Mutually exclusive with the UC-48 working spinner —
 * [SessionsScreen]'s SessionRow shows this badge INSTEAD of the spinner when
 * pendingQuestion is true (pending takes precedence). Styled distinctly from BOTH
 * the running-green [StatusPill] and the (primary-toned) working spinner: a
 * [Warning]-amber chip carrying a bold "?", so "waiting on you" reads at a glance.
 *
 * Carries a [contentDescription] ("awaiting your answer") for accessibility and so
 * instrumentation/UI tests can assert the badge's presence by its semantics.
 */
const val PENDING_QUESTION_BADGE_DESCRIPTION = "awaiting your answer"

@Composable
fun PendingQuestionBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .semantics { contentDescription = PENDING_QUESTION_BADGE_DESCRIPTION }
            .size(20.dp)
            .clip(CircleShape)
            .background(Warning.copy(alpha = 0.18f))
            .border(width = 1.5.dp, color = Warning, shape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "?",
            style = MaterialTheme.typography.labelMedium,
            color = Warning,
        )
    }
}

private data class Palette(
    val bg: androidx.compose.ui.graphics.Color,
    val fg: androidx.compose.ui.graphics.Color,
    val dot: androidx.compose.ui.graphics.Color,
    val dotFilled: Boolean,
    val pulse: Boolean = false,
)
