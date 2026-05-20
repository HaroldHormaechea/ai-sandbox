package com.aisandbox.android.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aisandbox.android.R
import com.aisandbox.android.net.Mismatch
import com.aisandbox.android.ui.theme.AiSandboxMonoTypography
import com.aisandbox.android.ui.theme.ErrorContainer
import com.aisandbox.android.ui.theme.ErrorTone

/**
 * UC04 AC7 + UC10 § AC8 hard-refusal screen — surfaced when the TLS
 * handshake with the configured server fails. Three variants share the
 * same chrome (error badge, title, body, "Quit" / "Scan new QR"
 * actions) and a shared "Show technical details" expander surfacing
 * the raw exception text + copy-to-clipboard.
 *
 * <ul>
 *   <li><b>[Mismatch.Pin]</b> — SPKI pin mismatch. Renders the
 *       pinned and observed SHA-256 hex blocks. Pre-UC10 the observed
 *       hex was a fixed sentinel forced by the OkHttp 5.3.2
 *       chain-cleaning trap; UC10's
 *       [com.aisandbox.android.net.SpkiPinningTrustManager] emits the
 *       real hex now.</li>
 *   <li><b>[Mismatch.Hostname]</b> — server's cert SAN doesn't claim
 *       the URL host. Renders the connect-host so the operator knows
 *       what to add via
 *       {@code aisandboxctl pki init --force --san <tag>:<host>}.</li>
 *   <li><b>[Mismatch.HandshakeError]</b> — generic TLS / I/O failure
 *       ({@code SSLException}, connection reset, malformed cert, etc.).
 *       Renders a generic "couldn't reach the server" block; the raw
 *       message is available behind the expander.</li>
 * </ul>
 *
 * <p>Distinct from UC04-7 cert-revoked: this is "the SERVER changed
 * identity", not "MY identity got revoked".
 */
@Composable
fun ServerIdentityChangedScreen(
    cause: Mismatch,
    onScanNewQr: () -> Unit,
    onQuit: () -> Unit,
) {
    val (titleRes, bodyRes) = when (cause) {
        is Mismatch.Pin ->
            R.string.server_identity_changed_pin_title to R.string.server_identity_changed_pin_body
        is Mismatch.Hostname ->
            R.string.server_identity_changed_hostname_title to R.string.server_identity_changed_hostname_body
        is Mismatch.HandshakeError ->
            R.string.server_identity_changed_handshake_title to R.string.server_identity_changed_handshake_body
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Error-tone circular badge with a warning icon — matches the
            // design's hard-refusal chrome; identical across all three variants.
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(ErrorContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = ErrorTone,
                )
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            // Variant-specific context block: pin hex pair for Pin,
            // expected-host for Hostname, nothing extra for HandshakeError
            // (the expander below carries the operator-troubleshooting
            // payload).
            when (cause) {
                is Mismatch.Pin -> PinContextBlock(cause)
                is Mismatch.Hostname -> HostnameContextBlock(cause)
                is Mismatch.HandshakeError -> {
                    // Generic block: no extra fingerprints / hosts — the
                    // rawMessage in the expander below is the operator's
                    // primary diagnostic surface.
                }
            }

            Spacer(Modifier.height(16.dp))
            TechnicalDetailsExpander(rawMessage = cause.rawMessage)

            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onQuit) {
                    Text(stringResource(R.string.server_identity_changed_quit))
                }
                Button(
                    onClick = onScanNewQr,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(
                        text = stringResource(R.string.server_identity_changed_rescan),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
    }
}

/**
 * Pin-variant context — mono-font expected / observed SHA-256 hex blocks.
 * The operator-readable diagnostic block round-trips cleanly through a
 * screenshot. Post-UC10 the observed hex is the REAL SPKI of the cert
 * the server presented — extracted from the structured
 * [com.aisandbox.android.net.SpkiPinningTrustManager] exception by
 * [com.aisandbox.android.net.TlsFailureTranslation.extractObservedSpkiHex].
 */
@Composable
private fun PinContextBlock(cause: Mismatch.Pin) {
    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        Text("expected:", style = AiSandboxMonoTypography.metadata)
        Text(cause.expectedHex, style = AiSandboxMonoTypography.fingerprint)
        Spacer(Modifier.height(8.dp))
        Text("observed:", style = AiSandboxMonoTypography.metadata)
        Text(cause.observedHex, style = AiSandboxMonoTypography.fingerprint)
    }
}

/**
 * Hostname-variant context — the URL host the request tried to reach,
 * surfaced verbatim so the operator can match it against the cert's SAN
 * (and produce the right `--san <tag>:<host>` argument for
 * `aisandboxctl pki init --force`).
 */
@Composable
private fun HostnameContextBlock(cause: Mismatch.Hostname) {
    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        Text("connect-host:", style = AiSandboxMonoTypography.metadata)
        Text(cause.expectedHost, style = AiSandboxMonoTypography.fingerprint)
    }
}

/**
 * UC10 § AC8 shared expander — collapsed by default, tap to reveal the
 * raw exception detail in mono font with a copy-to-clipboard button.
 * Implements the "raw exception surfacing" troubleshooting aid the
 * operator requested for post-UC10 stabilisation.
 *
 * <p>The copy button carries the test tag {@code "copy-raw-message"} —
 * pinned by the screen test as the contract for reaching the copy
 * action via Compose's semantics tree. Accessibility tests can locate
 * the same node.
 */
@Composable
private fun TechnicalDetailsExpander(rawMessage: String) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(
        if (expanded) R.string.server_identity_changed_hide_details
        else R.string.server_identity_changed_show_details
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            textAlign = TextAlign.Center,
        )
        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = rawMessage,
                    style = AiSandboxMonoTypography.metadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .weight(1f, fill = true),
                )
                IconButton(
                    onClick = {
                        // AnnotatedString preserves arbitrary text; styling
                        // doesn't need to round-trip through the clipboard.
                        clipboard.setText(AnnotatedString(rawMessage))
                    },
                    modifier = Modifier
                        .testTag("copy-raw-message")
                        .size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.server_identity_changed_copy_raw),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
