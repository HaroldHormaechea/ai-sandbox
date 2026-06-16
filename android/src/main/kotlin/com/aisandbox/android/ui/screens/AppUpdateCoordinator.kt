package com.aisandbox.android.ui.screens

import android.util.Log
import com.aisandbox.android.net.AppReleaseResolver
import com.aisandbox.android.net.GitHubReleaseClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * UC-87 — the discrete states of the dedicated app-update screen. A single
 * [MutableStateFlow] drives the UI (mirrors UC-84's
 * [ServerUpdateCoordinator]/[ServerUpdateUiState] one-StateFlow contract).
 */
sealed interface AppUpdateUiState {
    /** Querying GitHub for the latest stable release (loader, AC2). */
    data object Checking : AppUpdateUiState

    /** Installed version >= latest release — already current (AC3). */
    data class UpToDate(val current: String) : AppUpdateUiState

    /** A strictly newer stable release exists (AC4). [releaseHtmlUrl] backs the external Changelog link. */
    data class UpdateAvailable(val current: String, val latest: String, val releaseHtmlUrl: String?) :
        AppUpdateUiState

    /** Downloading the APK asset; [percent] is 0..100 (AC5). */
    data class Downloading(val percent: Int) : AppUpdateUiState

    /** Download complete; the system package-installer has been handed the APK (AC6). */
    data object Installing : AppUpdateUiState

    /** A check / download / install failed, or the build can't self-update — surfaced cleanly + retryable (AC5/AC6/AC8). */
    data class Error(val code: String, val detail: String) : AppUpdateUiState

    /** This is a debug-signed build; in-app update is release-only (AC9). No network/download is attempted. */
    data class DebugBuild(val current: String) : AppUpdateUiState
}

/**
 * UC-87 — Android-free orchestration core for the app self-update screen:
 * check → (offer) → download → install. Extracted from [AppUpdateViewModel] so
 * the flow is unit-testable on a plain JVM (mirrors [ServerUpdateCoordinator]).
 *
 * <p>Every Android / network touch point is constructor-injected as a seam:
 *
 * <ul>
 *   <li>[currentVersion] — {@code BuildConfig.VERSION_NAME}.</li>
 *   <li>[isDebugBuild] — {@code BuildConfig.DEBUG}; short-circuits to
 *       [AppUpdateUiState.DebugBuild] with no network call (AC9).</li>
 *   <li>[releaseSupplier] — fetches the newest stable release (the
 *       [GitHubReleaseClient] in prod; a fake in tests).</li>
 *   <li>[downloader] — streams the APK with progress.</li>
 *   <li>[installer] — hands the downloaded file to the system installer.</li>
 *   <li>[destFileSupplier] — where the APK is written.</li>
 * </ul>
 */
class AppUpdateCoordinator(
    private val state: MutableStateFlow<AppUpdateUiState>,
    private val scope: CoroutineScope,
    private val currentVersion: String,
    private val isDebugBuild: Boolean,
    private val releaseSupplier: suspend () -> GitHubReleaseClient.ReleaseCheckResult,
    private val downloader: suspend (url: String, destFile: File, onProgress: (Int) -> Unit) -> GitHubReleaseClient.DownloadResult,
    private val installer: suspend (File) -> InstallOutcome,
    private val destFileSupplier: () -> File,
) {

    /** The Available result from the last successful check, so [update] knows the APK URL. */
    private var pending: GitHubReleaseClient.ReleaseCheckResult.Available? = null

    /**
     * AC2/AC3/AC9 — run the version check. On a debug build, short-circuits to
     * [AppUpdateUiState.DebugBuild] without any network call. Otherwise resolves
     * the latest stable release and compares it against [currentVersion] by tag
     * semver (AC10).
     */
    fun check() {
        scope.launch {
            state.value = AppUpdateUiState.Checking
            pending = null
            if (isDebugBuild) {
                // AC9 — a .debug build has a different applicationId + signing key; it
                // cannot be updated in place by the release APK. State it, do nothing else.
                state.value = AppUpdateUiState.DebugBuild(currentVersion)
                return@launch
            }
            when (val r = releaseSupplier()) {
                is GitHubReleaseClient.ReleaseCheckResult.Available -> {
                    // AC3/AC10 — current >= latest ⇒ up to date; strictly newer ⇒ offer.
                    if (AppReleaseResolver.compareSemver(currentVersion, r.latestVersion) >= 0) {
                        state.value = AppUpdateUiState.UpToDate(currentVersion)
                    } else {
                        pending = r
                        state.value =
                            AppUpdateUiState.UpdateAvailable(currentVersion, r.latestVersion, r.releaseHtmlUrl)
                    }
                }
                GitHubReleaseClient.ReleaseCheckResult.NoRelease ->
                    // No android-v* release to compare against — treat as up to date.
                    state.value = AppUpdateUiState.UpToDate(currentVersion)
                GitHubReleaseClient.ReleaseCheckResult.RateLimited ->
                    state.value = AppUpdateUiState.Error(
                        "rate_limited",
                        "GitHub is rate-limiting update checks right now. Try again in a little while.",
                    )
                is GitHubReleaseClient.ReleaseCheckResult.Unreachable ->
                    state.value = AppUpdateUiState.Error("unreachable", r.detail)
                is GitHubReleaseClient.ReleaseCheckResult.CheckFailed ->
                    state.value = AppUpdateUiState.Error("check_failed", r.detail)
            }
        }
    }

    /**
     * AC5/AC6 — only when an update is offered: download the APK (with visible
     * progress) then hand it to the installer. On download failure/cancel the
     * partial file is DELETED before a retryable error is surfaced; the flow
     * never crashes.
     */
    fun update() {
        val avail = pending ?: return
        val apkUrl = avail.apkAssetUrl
        if (apkUrl.isNullOrBlank()) {
            state.value = AppUpdateUiState.Error(
                "no_asset",
                "This release has no installable APK asset to download.",
            )
            return
        }
        scope.launch {
            state.value = AppUpdateUiState.Downloading(0)
            val dest = destFileSupplier()
            val result = downloader(apkUrl, dest) { pct ->
                state.value = AppUpdateUiState.Downloading(pct)
            }
            when (result) {
                is GitHubReleaseClient.DownloadResult.Success -> {
                    state.value = AppUpdateUiState.Installing
                    when (val out = installer(result.file)) {
                        InstallOutcome.Launched -> {
                            // System installer UI is now front-of-screen; nothing more to do.
                        }
                        InstallOutcome.NeedsPermission ->
                            // AC6 — guide the user to grant "install unknown apps", then retry.
                            state.value = AppUpdateUiState.Error(
                                "install_permission",
                                "Allow installing apps from this source in the screen that just opened, " +
                                    "then tap Retry.",
                            )
                        is InstallOutcome.Failed -> {
                            dest.delete()
                            state.value = AppUpdateUiState.Error("install_failed", out.detail)
                        }
                    }
                }
                is GitHubReleaseClient.DownloadResult.Failed -> {
                    // AC5 — delete the partial download before surfacing a retryable error.
                    dest.delete()
                    state.value = AppUpdateUiState.Error("download_failed", result.detail)
                }
            }
        }
    }

    /** Re-run the check (used by the screen's Retry control after an error). */
    fun retry() {
        Log.d(TAG, "retry → re-check")
        check()
    }

    private companion object {
        const val TAG = "AppUpdateCoord"
    }
}

/**
 * UC-87 — pure (Android-free) result of handing an APK to the system installer,
 * so [AppUpdateCoordinator] stays JVM-testable. The concrete
 * {@code com.aisandbox.android.install.ApkInstaller} returns these values.
 */
sealed interface InstallOutcome {
    /** The system package-installer was launched for the APK. */
    data object Launched : InstallOutcome

    /** "Install unknown apps" is not granted yet; the per-app settings screen was opened (AC6). */
    data object NeedsPermission : InstallOutcome

    /** Could not launch the installer. */
    data class Failed(val detail: String) : InstallOutcome
}
