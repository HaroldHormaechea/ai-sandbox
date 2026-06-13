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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aisandbox.android.R
import com.aisandbox.android.ui.theme.Warning

/**
 * UC-62 — the "SERVER SSH SESSION" badge that distinguishes the pinned server
 * host-shell row from ordinary Claude rows (AC4). Reuses the [StatusPill] pill
 * shape/treatment (rounded chip, leading glyph, label) so it reads as a
 * sibling of the row's other chips, but tinted [Warning]-amber and carrying a
 * terminal glyph — a deliberate "this row crosses into the host" signal,
 * visually distinct from the green running pill.
 *
 * Carries a [contentDescription] so TalkBack and instrumentation can find it.
 */
@Composable
fun ServerSshBadge(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.server_ssh_badge)
    Box(
        modifier = modifier
            .semantics { contentDescription = label }
            .clip(RoundedCornerShape(50))
            .background(Warning.copy(alpha = 0.18f))
            .border(width = 1.dp, color = Warning, shape = RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                tint = Warning,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Warning,
            )
        }
    }
}
