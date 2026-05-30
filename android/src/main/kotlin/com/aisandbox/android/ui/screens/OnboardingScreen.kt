package com.aisandbox.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aisandbox.android.R
import com.aisandbox.android.ui.components.QrScanner
import com.aisandbox.android.ui.theme.Accent
import com.aisandbox.android.ui.theme.AiSandboxMonoTypography
import com.aisandbox.android.ui.theme.BgWorkbench
import com.aisandbox.android.ui.theme.OnSurface
import com.aisandbox.android.ui.theme.OnSurfaceMuted
import com.aisandbox.android.ui.theme.OnSurfaceVariant
import com.aisandbox.android.ui.theme.Success
import com.aisandbox.android.ui.theme.SurfaceHigh
import com.aisandbox.android.ui.theme.SurfaceLow
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * UC04-1 — full-bleed camera onboarding screen.
 *
 * <p>Three visible stages (per the design): {@code scan} → {@code
 * imported} → {@code ready}. State machine + persistence are owned by
 * [OnboardingViewModel]; this composable is rendering-only.
 *
 * <p>Pre-state: {@link OnboardingState.NeedsCameraPermission} —
 * Android 13+ requires the runtime CAMERA permission; tap-through
 * launches the system permission dialog.
 *
 * <p>Mid-state: {@link OnboardingState.Scanning} — full-bleed CameraX
 * preview with a centred reticle. Tapping during scan does nothing —
 * the next valid QR triggers transition automatically.
 *
 * <p>Post-state: {@link OnboardingState.Imported} — server-url + pin +
 * cert metadata panel with the KeyStore badge and a Continue button
 * that hands off to [onContinue].
 */
@Composable
fun OnboardingScreen(
    onContinue: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(),
    onClose: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val cameraGrantedAtFirstCompose = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(cameraGrantedAtFirstCompose) {
        if (cameraGrantedAtFirstCompose && state is OnboardingState.NeedsCameraPermission) {
            viewModel.onCameraPermissionGranted()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.onCameraPermissionGranted() else viewModel.onCameraPermissionDenied()
    }

    // Camera-flash toggle driven by the top-right IconButton. Reset on any
    // state transition out of Scanning so the torch doesn't stay on across
    // a successful import or a failure return-to-scan.
    var torchOn by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state !is OnboardingState.Scanning && state !is OnboardingState.ConfirmReplace) {
            torchOn = false
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BgWorkbench)) {
        when (val s = state) {
            OnboardingState.NeedsCameraPermission -> CameraPermissionPanel(
                onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            )

            OnboardingState.Scanning -> ScanningPanel()

            is OnboardingState.ConfirmReplace -> {
                ScanningPanel() // keep the camera underneath the dialog
                ReplaceConfirmDialog(
                    onCancel = viewModel::onCancelReplace,
                    onConfirm = viewModel::onConfirmReplace,
                )
            }

            is OnboardingState.Enrolling -> EnrollingPanel(serverUrl = s.payload.serverUrl)

            is OnboardingState.Imported -> ImportedPanel(
                state = s,
                onContinue = onContinue,
            )

            is OnboardingState.Failure -> FailurePanel(
                code = s.code,
                message = s.message,
                onRetry = viewModel::onRestartScan,
            )
        }

        // The QR scanner lives in the same Box so the reticle + scan-line
        // animations layer on top of the preview without re-creating the
        // camera surface every state change. We keep the preview mounted
        // through Enrolling too so the reticle's success transition reads
        // as a single continuous animation rather than a panel swap.
        if (state is OnboardingState.Scanning ||
            state is OnboardingState.ConfirmReplace ||
            state is OnboardingState.Enrolling
        ) {
            QrScanner(
                modifier = Modifier.fillMaxSize(),
                enabled = state is OnboardingState.Scanning,
                torchOn = torchOn,
                onQr = viewModel::onQrPayload,
            )
            ScanReticle(
                modifier = Modifier.align(Alignment.Center),
                stage = state.reticleStage(),
            )
            // Top chrome — close on the left (only when the caller wired
            // a non-null onClose, i.e. we have somewhere to return to),
            // torch toggle on the right.
            TopChrome(
                modifier = Modifier.align(Alignment.TopCenter),
                onClose = onClose,
                torchOn = torchOn,
                onToggleTorch = { torchOn = !torchOn },
            )
        }
    }
}

/**
 * Reticle visual stage. Lets [ScanReticle] decide whether to show the
 * sweeping scan-line + accent corners (live scanning) or the success
 * check + green corners (token redeemed / identity imported).
 */
private enum class ReticleStage { Scanning, Success }

private fun OnboardingState.reticleStage(): ReticleStage = when (this) {
    is OnboardingState.Enrolling, is OnboardingState.Imported -> ReticleStage.Success
    else -> ReticleStage.Scanning
}

@Composable
private fun TopChrome(
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)?,
    torchOn: Boolean,
    onToggleTorch: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onClose != null) {
            GlassIconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.onboarding_close_label),
                    tint = Color.White,
                )
            }
        } else {
            Spacer(Modifier.size(40.dp))
        }
        GlassIconButton(onClick = onToggleTorch) {
            Icon(
                imageVector = if (torchOn) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                contentDescription = stringResource(R.string.onboarding_torch_label),
                tint = if (torchOn) Accent else Color.White,
            )
        }
    }
}

@Composable
private fun GlassIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            content()
        }
    }
}

// ── Sub-panels ───────────────────────────────────────────────────────────

@Composable
private fun CameraPermissionPanel(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.onboarding_camera_permission_required),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = OnSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant) {
            Text(stringResource(R.string.onboarding_camera_permission_grant))
        }
    }
}

@Composable
private fun ScanningPanel() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineSmall,
            color = OnSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = stringResource(R.string.onboarding_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ScanReticle(modifier: Modifier = Modifier, stage: ReticleStage = ReticleStage.Scanning) {
    val reticleSize = 240.dp
    val cornerArm = 36.dp
    val cornerStroke = 3.dp
    val cornerColor = if (stage == ReticleStage.Success) Success else Color.White

    Box(
        modifier = modifier.size(reticleSize),
        contentAlignment = Alignment.Center,
    ) {
        // Four reticle corners — color transitions to Success when the
        // VM lands on Enrolling/Imported, mirroring the design's 240 ms
        // border-color swap.
        ReticleCorner(
            modifier = Modifier.align(Alignment.TopStart),
            isTop = true, isStart = true, arm = cornerArm, stroke = cornerStroke, color = cornerColor,
        )
        ReticleCorner(
            modifier = Modifier.align(Alignment.TopEnd),
            isTop = true, isStart = false, arm = cornerArm, stroke = cornerStroke, color = cornerColor,
        )
        ReticleCorner(
            modifier = Modifier.align(Alignment.BottomStart),
            isTop = false, isStart = true, arm = cornerArm, stroke = cornerStroke, color = cornerColor,
        )
        ReticleCorner(
            modifier = Modifier.align(Alignment.BottomEnd),
            isTop = false, isStart = false, arm = cornerArm, stroke = cornerStroke, color = cornerColor,
        )

        when (stage) {
            ReticleStage.Scanning -> ScanLine(reticleSize = reticleSize)
            ReticleStage.Success -> Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = Success,
                modifier = Modifier.size(80.dp),
            )
        }
    }
}

/**
 * L-shaped reticle corner. Two thin rectangles drawn against the inner
 * edges of an [arm]×[arm] box; [isTop] / [isStart] pick which two edges.
 */
@Composable
private fun ReticleCorner(
    modifier: Modifier,
    isTop: Boolean,
    isStart: Boolean,
    arm: Dp,
    stroke: Dp,
    color: Color,
) {
    Box(modifier = modifier.size(arm)) {
        // Horizontal arm — runs along the top or bottom edge.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(stroke)
                .align(if (isTop) Alignment.TopStart else Alignment.BottomStart)
                .background(color),
        )
        // Vertical arm — runs along the start or end edge.
        Box(
            modifier = Modifier
                .size(width = stroke, height = arm)
                .align(if (isStart) Alignment.TopStart else Alignment.TopEnd)
                .background(color),
        )
    }
}

@Composable
private fun ScanLine(reticleSize: Dp) {
    val transition = rememberInfiniteTransition(label = "scanline")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scanline-progress",
    )
    // Sweep range: 12 dp of inset on each side so the line never touches
    // the corners. The line itself is a thin accent-colored bar with a
    // soft gradient on either side (proxy for the design's box-shadow glow).
    val travel = reticleSize - 24.dp
    val yOffset = 12.dp + travel * progress
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .offset(y = yOffset)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Accent.copy(alpha = 0.85f),
                            Accent,
                            Accent.copy(alpha = 0.85f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun ReplaceConfirmDialog(onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.onboarding_replace_existing_title)) },
        text = { Text(stringResource(R.string.onboarding_replace_existing_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.onboarding_replace_existing_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.new_session_cancel)) }
        },
    )
}

@Composable
private fun EnrollingPanel(serverUrl: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Accent)
        Spacer(Modifier.height(16.dp))
        Text(
            text = serverUrl,
            style = AiSandboxMonoTypography.metadata,
            color = OnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Redeeming enrollment token…",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ImportedPanel(state: OnboardingState.Imported, onContinue: () -> Unit) {
    val cert = state.cert
    val profile = state.profile
    val expiryFormatter = remember {
        SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgWorkbench)
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Icon(
            imageVector = Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = Success,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_imported_title),
            style = MaterialTheme.typography.headlineSmall,
            color = OnSurface,
        )
        Spacer(Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceLow)
                .padding(16.dp),
        ) {
            Column {
                MetadataRow(label = "server", value = profile.serverUrl)
                Spacer(Modifier.height(8.dp))
                MetadataRow(label = "pin", value = "sha256/${profile.pinSha256Hex.take(24)}…")
                Spacer(Modifier.height(8.dp))
                MetadataRow(label = "cn", value = cert.subjectX500Principal.name)
                Spacer(Modifier.height(8.dp))
                MetadataRow(label = "expires", value = expiryFormatter.format(cert.notAfter))
            }
        }

        Spacer(Modifier.height(16.dp))
        // KeyStore badge — accent-container background per the design.
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceHigh)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_imported_keystore_badge),
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceVariant,
            )
        }

        Spacer(Modifier.height(32.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_continue))
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Column {
        Text(label, style = AiSandboxMonoTypography.metadata, color = OnSurfaceMuted)
        Text(value, style = AiSandboxMonoTypography.fingerprint, color = OnSurface)
    }
}

@Composable
private fun FailurePanel(code: String, message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = code,
            style = AiSandboxMonoTypography.metadata,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onRetry) {
            Text("Try again")
        }
    }
}
