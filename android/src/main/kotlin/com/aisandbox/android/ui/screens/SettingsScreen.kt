package com.aisandbox.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aisandbox.android.BuildConfig
import com.aisandbox.android.R
import com.aisandbox.android.net.NetworkEvent
import com.aisandbox.android.net.NetworkEvents
import com.aisandbox.android.requireContainer
import com.aisandbox.android.terminal.KeyboardSettingsStore
import com.aisandbox.android.ui.components.ConnectedPill
import com.aisandbox.android.ui.settings.AppearanceSettingsStore
import com.aisandbox.android.ui.settings.ConversationFontSize
import com.aisandbox.android.ui.theme.AiSandboxMonoTypography
import com.aisandbox.android.ui.theme.ErrorTone
import com.aisandbox.android.ui.theme.OnSurface
import com.aisandbox.android.ui.theme.OnSurfaceMuted
import com.aisandbox.android.ui.theme.OnSurfaceVariant
import com.aisandbox.android.ui.theme.SurfaceLow
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.launch

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
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // UC-53 (AC1) — Appearance group on top: the new font-size + agent-color
            // preferences, plus the UC-36 keyboard toggle kept as a top-level pref (AC5).
            GroupHeader(stringResource(R.string.settings_group_appearance))
            AppearanceSection(container = container)
            KeyboardSection(container = container)

            // UC-84 — server self-update controls (own group).
            GroupHeader(stringResource(R.string.settings_group_maintenance))
            ServerUpdateSection()

            // UC-53 (AC1) — the previously top-level read-only sections + footer are
            // now demoted under an Info group.
            GroupHeader(stringResource(R.string.settings_group_info))
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

/**
 * UC-53 (AC2/AC3) — the Appearance preferences: a discrete S/M/L/XL font-size
 * control that rescales the conversation/agent view only, and the
 * "use agent color in bubbles" toggle (default OFF). Both are pure projections of
 * the process-scoped [AppearanceSettingsStore]; flipping either persists
 * immediately (AC6) and the conversation view re-reads it reactively.
 */
@Composable
private fun AppearanceSection(container: com.aisandbox.android.AppContainer) {
    val scope = rememberCoroutineScope()
    val fontSize by container.appearanceSettings.fontSize
        .collectAsStateWithLifecycle(initialValue = AppearanceSettingsStore.DEFAULT_FONT_SIZE)
    val useAgentColor by container.appearanceSettings.useAgentColorInBubbles
        .collectAsStateWithLifecycle(initialValue = AppearanceSettingsStore.DEFAULT_USE_AGENT_COLOR)

    Section(title = stringResource(R.string.settings_section_appearance)) {
        // ── Font size (AC2) ──────────────────────────────────────────────
        Text(
            text = stringResource(R.string.settings_appearance_font_size),
            style = MaterialTheme.typography.titleSmall,
            color = OnSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.settings_appearance_font_size_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceMuted,
        )
        Spacer(Modifier.height(8.dp))
        val options = ConversationFontSize.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, size ->
                SegmentedButton(
                    selected = size == fontSize,
                    onClick = { scope.launch { container.appearanceSettings.setFontSize(size) } },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(stringResource(fontSizeLabelRes(size)))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Agent color in bubbles (AC3) ─────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_appearance_agent_color),
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_appearance_agent_color_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = useAgentColor,
                onCheckedChange = { enabled ->
                    scope.launch { container.appearanceSettings.setUseAgentColorInBubbles(enabled) }
                },
            )
        }
    }
}

/** UC-53 — map a [ConversationFontSize] step to its short S/M/L/XL label string. */
private fun fontSizeLabelRes(size: ConversationFontSize): Int = when (size) {
    ConversationFontSize.SMALL -> R.string.settings_font_size_s
    ConversationFontSize.MEDIUM -> R.string.settings_font_size_m
    ConversationFontSize.LARGE -> R.string.settings_font_size_l
    ConversationFontSize.XLARGE -> R.string.settings_font_size_xl
}

/**
 * UC-36 (AC#7) — the conversational-keyboard toggle. Pure projection of
 * [com.aisandbox.android.terminal.KeyboardSettingsStore] (held on the container);
 * flipping the switch persists immediately and the terminal screen picks it up via
 * its ViewModel flow + `restartInput`.
 */
@Composable
private fun KeyboardSection(container: com.aisandbox.android.AppContainer) {
    val scope = rememberCoroutineScope()
    val conversational by container.keyboardSettings.conversational
        .collectAsStateWithLifecycle(initialValue = KeyboardSettingsStore.DEFAULT_CONVERSATIONAL)
    Section(title = stringResource(R.string.settings_section_keyboard)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_keyboard_conversational),
                    style = MaterialTheme.typography.titleSmall,
                    color = OnSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_keyboard_conversational_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = conversational,
                onCheckedChange = { enabled ->
                    scope.launch { container.keyboardSettings.setConversational(enabled) }
                },
            )
        }
    }
}

/**
 * UC-84 — the "Server updates" section. An explicit "Look for server updates"
 * button (AC3 — never auto-runs on screen open) drives a [ServerUpdateViewModel]
 * check; the section then renders per [ServerUpdateUiState]: up-to-date, an
 * "Update to version X" button + external-browser "Changelog" link (AC6), an
 * in-app confirm dialog before apply (AC7), an "updating…" state that tolerates
 * the restart disconnect and reports the now-running version (AC9), or a clean
 * error that leaves everything running (AC14).
 */
@Composable
private fun ServerUpdateSection(viewModel: ServerUpdateViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    Section(title = stringResource(R.string.settings_section_server_updates)) {
        when (val s = state) {
            is ServerUpdateUiState.Checking -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.settings_update_checking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceMuted,
                    )
                }
            }

            is ServerUpdateUiState.UpToDate -> {
                Text(
                    stringResource(R.string.settings_update_up_to_date, s.current),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface,
                )
                Spacer(Modifier.height(12.dp))
                CheckButton(viewModel, labelRes = R.string.settings_update_recheck)
            }

            is ServerUpdateUiState.UpdateAvailable -> {
                Text(
                    stringResource(R.string.settings_update_current, s.current),
                    style = AiSandboxMonoTypography.metadata,
                    color = OnSurfaceMuted,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.requestConfirm() },
                    modifier = Modifier.fillMaxWidth().testTag("settings_update_apply"),
                ) {
                    Text(stringResource(R.string.settings_update_available, s.latest))
                }
                if (!s.releaseHtmlUrl.isNullOrBlank()) {
                    TextButton(
                        onClick = { openChangelog(context, s.releaseHtmlUrl) },
                        modifier = Modifier.testTag("settings_update_changelog"),
                    ) {
                        Text(stringResource(R.string.settings_update_changelog))
                    }
                }
            }

            is ServerUpdateUiState.Confirming -> {
                // Keep the available state visible behind the dialog.
                Text(
                    stringResource(R.string.settings_update_available, s.latest),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface,
                )
                AlertDialog(
                    onDismissRequest = { viewModel.cancelConfirm() },
                    title = { Text(stringResource(R.string.settings_update_confirm_title)) },
                    text = { Text(stringResource(R.string.settings_update_confirm_body, s.latest)) },
                    confirmButton = {
                        TextButton(
                            onClick = { viewModel.confirmApply() },
                            modifier = Modifier.testTag("settings_update_confirm"),
                        ) {
                            Text(stringResource(R.string.settings_update_confirm_yes))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.cancelConfirm() }) {
                            Text(stringResource(R.string.settings_update_confirm_cancel))
                        }
                    },
                )
            }

            is ServerUpdateUiState.Updating -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.settings_update_updating),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceMuted,
                    )
                }
            }

            is ServerUpdateUiState.Done -> {
                Text(
                    stringResource(R.string.settings_update_done, s.newVersion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurface,
                )
                Spacer(Modifier.height(12.dp))
                CheckButton(viewModel, labelRes = R.string.settings_update_recheck)
            }

            is ServerUpdateUiState.Error -> {
                Text(
                    stringResource(R.string.settings_update_error, "${s.code}: ${s.detail}"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ErrorTone,
                )
                Spacer(Modifier.height(12.dp))
                CheckButton(viewModel, labelRes = R.string.settings_update_recheck)
            }

            ServerUpdateUiState.Idle -> {
                Text(
                    stringResource(R.string.settings_update_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                )
                Spacer(Modifier.height(12.dp))
                CheckButton(viewModel, labelRes = R.string.settings_update_check)
            }
        }
    }
}

@Composable
private fun CheckButton(viewModel: ServerUpdateViewModel, labelRes: Int) {
    Button(
        onClick = { viewModel.check() },
        modifier = Modifier.fillMaxWidth().testTag("settings_update_check"),
    ) {
        Text(stringResource(labelRes))
    }
}

/** AC6 — open the release's GitHub page in an external browser. */
private fun openChangelog(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        // No browser available — silently ignore (the version info is still shown).
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

// ── Group + section + row helpers ───────────────────────────────────────

/**
 * UC-53 (AC1) — a top-level group divider ("Appearance" / "Info") sitting above a
 * run of [Section]s. Larger/bolder than a [Section] title so the two reading
 * levels (group vs. section) are visually distinct.
 */
@Composable
private fun GroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = OnSurface,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = 8.dp),
    )
}

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

