package com.aisandbox.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aisandbox.android.BuildConfig
import com.aisandbox.android.R
import com.aisandbox.android.net.NetworkEvent
import com.aisandbox.android.net.NetworkEvents
import com.aisandbox.android.requireContainer
import com.aisandbox.android.ui.components.ConnectedPill
import com.aisandbox.android.ui.theme.AiSandboxMonoTypography
import com.aisandbox.android.ui.theme.ErrorTone
import com.aisandbox.android.ui.theme.OnSurface
import com.aisandbox.android.ui.theme.OnSurfaceMuted
import com.aisandbox.android.ui.theme.OnSurfaceVariant
import com.aisandbox.android.ui.theme.SurfaceLow
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * UC04-5 settings screen — four sections (Server / Client identity /
 * WebSocket / Diagnostics) plus the AC27 footer.
 *
 * <p>No ViewModel — every section is a pure projection of
 * [com.aisandbox.android.net.ServerProfileStore] +
 * [com.aisandbox.android.identity.KeyStoreIdentityManager]; the few
 * interactive bits (copy-to-clipboard, simulate-cert-revoke) are
 * surfaced via callbacks straight to the Composable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { requireContainer(context) }
    val profile by container.profileStore.profile.collectAsStateWithLifecycle(initialValue = null)
    val cert = remember(profile) { container.identity.leafCertificate() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServerSection(profile = profile, onCopy = { copy(context, "server pin", it) })
            IdentitySection(cert = cert, profile = profile, onCopy = { copy(context, "fingerprint", it) })
            WebSocketSection()
            DiagnosticsSection(onSimulateRevoke = {
                // Emit the CertRevoked NetworkEvent so the root composable
                // routes to UC04-7 — pure local-loop, no server call.
                NetworkEvents.tryEmit(NetworkEvent.CertRevoked)
            })
            Spacer(Modifier.height(8.dp))
            Footer()
        }
    }
}

// ── Sections ────────────────────────────────────────────────────────────

@Composable
private fun ServerSection(
    profile: com.aisandbox.android.net.ServerProfile?,
    onCopy: (String) -> Unit,
) {
    Section(title = stringResource(R.string.settings_section_server)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_server_url), style = AiSandboxMonoTypography.metadata, color = OnSurfaceMuted)
                Text(
                    text = profile?.serverUrl ?: "—",
                    style = AiSandboxMonoTypography.fingerprint,
                    color = OnSurface,
                )
            }
            ConnectedPill()
        }
        Spacer(Modifier.height(12.dp))
        CopyableRow(
            label = stringResource(R.string.settings_server_pin),
            value = profile?.pinSha256Hex?.let { "sha256/${it.take(48)}…" } ?: "—",
            onCopy = { profile?.pinSha256Hex?.let(onCopy) },
        )
    }
}

@Composable
private fun IdentitySection(
    cert: java.security.cert.X509Certificate?,
    profile: com.aisandbox.android.net.ServerProfile?,
    onCopy: (String) -> Unit,
) {
    val expiryFormatter = remember {
        SimpleDateFormat("yyyy-MM-dd 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
    Section(title = stringResource(R.string.settings_section_identity)) {
        Text(
            text = stringResource(R.string.settings_identity_keystore_badge),
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (cert != null && profile != null) {
            // 2×2 grid: ISSUED / EXPIRES on the first row, SERIAL / KEY on the second.
            Row {
                MetadataField(
                    label = stringResource(R.string.settings_identity_issued),
                    value = expiryFormatter.format(cert.notBefore),
                    modifier = Modifier.weight(1f),
                )
                MetadataField(
                    label = stringResource(R.string.settings_identity_expires),
                    value = expiryFormatter.format(cert.notAfter),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row {
                MetadataField(
                    label = stringResource(R.string.settings_identity_serial),
                    value = cert.serialNumber.toString(16).take(16),
                    modifier = Modifier.weight(1f),
                )
                MetadataField(
                    label = stringResource(R.string.settings_identity_key),
                    value = "RSA ${(cert.publicKey as? java.security.interfaces.RSAPublicKey)?.modulus?.bitLength() ?: 0}",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            CopyableRow(
                label = "fingerprint",
                value = computeFingerprintHex(cert),
                onCopy = { onCopy(computeFingerprintHex(cert)) },
            )
        } else {
            Text("No identity imported.", style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
        }
    }
}

@Composable
private fun WebSocketSection() {
    Section(title = stringResource(R.string.settings_section_websocket)) {
        MetadataField(label = stringResource(R.string.settings_websocket_subprotocol), value = "ai-sandbox.v1")
        Spacer(Modifier.height(12.dp))
        MetadataField(
            label = stringResource(R.string.settings_websocket_ping),
            value = stringResource(R.string.settings_websocket_ping_value),
        )
        Spacer(Modifier.height(12.dp))
        MetadataField(label = stringResource(R.string.settings_websocket_size), value = "auto on viewport change")
    }
}

@Composable
private fun DiagnosticsSection(onSimulateRevoke: () -> Unit) {
    Section(title = stringResource(R.string.settings_section_diagnostics)) {
        Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onSimulateRevoke)) {
            Text(
                text = stringResource(R.string.settings_diagnostics_simulate_revoke),
                style = MaterialTheme.typography.titleSmall,
                color = ErrorTone,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_diagnostics_simulate_revoke_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
            )
        }
    }
}

@Composable
private fun Footer() {
    Text(
        text = stringResource(
            R.string.settings_footer,
            BuildConfig.VERSION_NAME,
            29,
        ),
        style = AiSandboxMonoTypography.terminalSmall,
        color = OnSurfaceMuted,
        modifier = Modifier.fillMaxWidth(),
    )
}

// ── Section + row helpers ───────────────────────────────────────────────

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title.uppercase(Locale.US),
            style = MaterialTheme.typography.labelMedium,
            color = OnSurfaceMuted,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceLow)
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) { content() }
        }
    }
}

@Composable
private fun MetadataField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = AiSandboxMonoTypography.metadata, color = OnSurfaceMuted)
        Text(value, style = AiSandboxMonoTypography.fingerprint, color = OnSurface)
    }
}

@Composable
private fun CopyableRow(label: String, value: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = AiSandboxMonoTypography.metadata, color = OnSurfaceMuted)
            Text(value, style = AiSandboxMonoTypography.fingerprint, color = OnSurface)
        }
        TextButton(onClick = onCopy) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.settings_copy))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.settings_copy))
        }
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────

private fun copy(context: Context, label: String, value: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun computeFingerprintHex(cert: java.security.cert.X509Certificate): String {
    return try {
        val der = cert.encoded
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(der)
        digest.joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) {
        ""
    }
}

