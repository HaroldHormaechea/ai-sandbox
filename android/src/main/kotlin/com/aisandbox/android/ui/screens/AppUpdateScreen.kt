package com.aisandbox.android.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aisandbox.android.R
import com.aisandbox.android.ui.theme.ErrorTone
import com.aisandbox.android.ui.theme.OnSurface
import com.aisandbox.android.ui.theme.OnSurfaceMuted

/**
 * UC-87 — dedicated full-screen app self-update view, reached from the new third
 * sessions-list hamburger item "Look for app updates". Mirrors [McpScreen]'s
 * Scaffold + TopAppBar (with an ArrowBack [onBack]).
 *
 * <p>On open it auto-runs the version check exactly once (AC2) via a keyed
 * {@code LaunchedEffect} + the ViewModel's init-once guard (survives rotation).
 * Rendering is delegated to the stateless [AppUpdateContent] seam so an
 * instrumented test can drive every state against a fabricated
 * [AppUpdateUiState] without a ViewModel or network.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUpdateScreen(
    onBack: () -> Unit,
    viewModel: AppUpdateViewModel = viewModel(),
) {
    val context = LocalContext.current
    // AC2 — auto-check on open; the ViewModel guard makes it fire once for the
    // ViewModel's lifetime (not per recomposition / rotation).
    LaunchedEffect(Unit) { viewModel.checkOnce() }

    val state by viewModel.state.collectAsState()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_update_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.app_update_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            AppUpdateContent(
                state = state,
                onUpdate = viewModel::update,
                onRetry = viewModel::retry,
                onChangelog = { url -> openChangelog(context, url) },
            )
        }
    }
}

/**
 * UC-87 — stateless render seam for the app-update screen (mirrors UC-84's
 * {@code internal fun ServerUpdateContent}). Takes the [AppUpdateUiState] +
 * action callbacks so an instrumented test can assert each AC against a
 * fabricated state.
 *
 * <p>Stable testTags: {@code app_update_checking}, {@code app_update_up_to_date},
 * {@code app_update_action} (Update button), {@code app_update_progress}
 * (download), {@code app_update_retry}, {@code app_update_debug_notice},
 * {@code app_update_changelog}.
 */
@Composable
internal fun AppUpdateContent(
    state: AppUpdateUiState,
    onUpdate: () -> Unit,
    onRetry: () -> Unit,
    onChangelog: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val s = state) {
            is AppUpdateUiState.Checking -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp).testTag("app_update_checking"),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.app_update_checking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceMuted,
                    )
                }
            }

            is AppUpdateUiState.UpToDate -> {
                Text(
                    text = stringResource(R.string.app_update_up_to_date, s.current),
                    style = MaterialTheme.typography.bodyLarge,
                    color = OnSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("app_update_up_to_date"),
                )
            }

            is AppUpdateUiState.UpdateAvailable -> {
                Text(
                    // AC4 — vOLD → vNEW transition.
                    text = stringResource(R.string.app_update_transition, s.current, s.latest),
                    style = MaterialTheme.typography.titleMedium,
                    color = OnSurface,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = onUpdate,
                    modifier = Modifier.fillMaxWidth().testTag("app_update_action"),
                ) {
                    Text(stringResource(R.string.app_update_action))
                }
                if (!s.releaseHtmlUrl.isNullOrBlank()) {
                    val url = s.releaseHtmlUrl
                    TextButton(
                        onClick = { onChangelog(url) },
                        modifier = Modifier.testTag("app_update_changelog"),
                    ) {
                        Text(stringResource(R.string.app_update_changelog))
                    }
                }
            }

            is AppUpdateUiState.Downloading -> {
                Text(
                    text = stringResource(R.string.app_update_downloading, s.percent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted,
                )
                LinearProgressIndicator(
                    progress = { s.percent / 100f },
                    modifier = Modifier.fillMaxWidth().testTag("app_update_progress"),
                )
            }

            is AppUpdateUiState.Installing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.app_update_installing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceMuted,
                    )
                }
            }

            is AppUpdateUiState.DebugBuild -> {
                Text(
                    // AC9 — release-only; do not attempt an install on a debug build.
                    text = stringResource(R.string.app_update_debug_notice, s.current),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("app_update_debug_notice"),
                )
            }

            is AppUpdateUiState.Error -> {
                Text(
                    text = stringResource(R.string.app_update_error, s.detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ErrorTone,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onRetry,
                    modifier = Modifier.testTag("app_update_retry"),
                ) {
                    Text(stringResource(R.string.app_update_retry))
                }
            }
        }
    }
}

/** AC4 — open the release's GitHub page in an external browser (mirrors UC-84). */
private fun openChangelog(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        // No browser available — the version info is still shown on-screen.
    }
}
