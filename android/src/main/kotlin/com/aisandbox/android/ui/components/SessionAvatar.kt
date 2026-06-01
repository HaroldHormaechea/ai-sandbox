package com.aisandbox.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aisandbox.android.ui.theme.AccentContainer
import com.aisandbox.android.ui.theme.AiSandboxMonoTypography
import com.aisandbox.android.ui.theme.OnAccentContainer
import com.aisandbox.android.ui.theme.Warning

/**
 * UC04 § Components — rounded-square avatar with the zero-padded session
 * number. Sizes:
 *
 * <ul>
 *   <li>Default (cards density) — 40 dp.</li>
 *   <li>Large (terminal toolbar) — 48 dp.</li>
 * </ul>
 *
 * AC9 — when the session is in the `starting` state (or the UC-27
 * `provisioning` state, which shares the same in-flight visual treatment),
 * the avatar takes a 2 dp warning-amber outline. The default fill is
 * accent-container so the avatar reads as "mine" against the dark surface.
 */
@Composable
fun SessionAvatar(
    n: Int,
    state: String,
    modifier: Modifier = Modifier,
    sizeDp: Int = 40,
) {
    val showStartingOutline = state == "starting" || state == "provisioning"
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(AccentContainer)
            .then(
                if (showStartingOutline) {
                    Modifier.border(width = 2.dp, color = Warning, shape = RoundedCornerShape(10.dp))
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = formatSessionN(n),
            style = AiSandboxMonoTypography.sessionId,
            color = OnAccentContainer,
        )
    }
}

/**
 * Format the session N as "01", "02"… "99", "100"+. Matches the
 * design's zero-padded display convention.
 */
fun formatSessionN(n: Int): String = if (n < 100) "%02d".format(n) else n.toString()
