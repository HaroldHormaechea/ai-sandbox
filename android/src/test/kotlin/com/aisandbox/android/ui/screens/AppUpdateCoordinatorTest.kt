package com.aisandbox.android.ui.screens

import com.aisandbox.android.net.GitHubReleaseClient
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * UC-87 — the Android-free orchestration core [AppUpdateCoordinator]:
 * check → (offer) → download → install. Every Android/network touch point is a
 * constructor-injected seam, so the whole flow is driven on a plain JVM with
 * fakes — no emulator, no network (mirrors [ServerUpdateCoordinatorTest]).
 *
 * <p>Terminal states are awaited off the driving [MutableStateFlow] under a
 * real-time {@code withTimeout} guard (the coordinator launches on
 * {@code Dispatchers.IO}).
 *
 * <h2>AC → test map</h2>
 *
 * <ul>
 *   <li>AC9 (debug short-circuit, no network) — {@link #debug_build_short_circuits_with_no_network_call()}.</li>
 *   <li>AC3 (up to date) — {@link #current_ge_latest_is_up_to_date()},
 *       {@link #no_release_is_treated_as_up_to_date()}.</li>
 *   <li>AC4 (offer) — {@link #strictly_newer_release_offers_an_update()}.</li>
 *   <li>AC10 (semver, not versionCode/lexical) — {@link #ordering_uses_semver_not_string_compare()}.</li>
 *   <li>AC8 (graceful errors, retryable) — {@link #rate_limited_surfaces_a_retryable_error()},
 *       {@link #unreachable_surfaces_a_retryable_error()},
 *       {@link #check_failed_surfaces_a_retryable_error()}.</li>
 *   <li>AC5/AC6 (download → install) — {@link #update_downloads_with_progress_then_installs()}.</li>
 *   <li>AC5/AC8 (download failure cleans up) — {@link #download_failure_deletes_partial_file_and_errors()}.</li>
 *   <li>AC6 (install permission gate) — {@link #install_needs_permission_surfaces_retryable_error()}.</li>
 *   <li>install failure cleanup — {@link #install_failure_deletes_file_and_errors()}.</li>
 *   <li>missing asset — {@link #update_with_no_apk_asset_surfaces_no_asset_error()}.</li>
 *   <li>guard — {@link #update_before_an_offer_is_a_no_op()}.</li>
 * </ul>
 */
class AppUpdateCoordinatorTest {

    private lateinit var tmpDir: File

    @BeforeEach
    fun setUp() {
        tmpDir = Files.createTempDirectory("uc87-coord").toFile()
    }

    @AfterEach
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    private fun destFile() = File(tmpDir, "android-release.apk")

    /** Build a coordinator with overridable seams; unspecified seams are inert defaults. */
    private fun coordinator(
        state: MutableStateFlow<AppUpdateUiState>,
        currentVersion: String = "0.4.15",
        isDebugBuild: Boolean = false,
        releaseSupplier: suspend () -> GitHubReleaseClient.ReleaseCheckResult =
            { GitHubReleaseClient.ReleaseCheckResult.NoRelease },
        downloader: suspend (String, File, (Int) -> Unit) -> GitHubReleaseClient.DownloadResult =
            { _, dest, _ -> GitHubReleaseClient.DownloadResult.Success(dest) },
        installer: suspend (File) -> InstallOutcome = { InstallOutcome.Launched },
        dest: () -> File = ::destFile,
    ) = AppUpdateCoordinator(
        state = state,
        scope = CoroutineScope(Dispatchers.IO),
        currentVersion = currentVersion,
        isDebugBuild = isDebugBuild,
        releaseSupplier = releaseSupplier,
        downloader = downloader,
        installer = installer,
        destFileSupplier = dest,
    )

    private fun available(latest: String, apkUrl: String? = "https://gh/dl/android-release.apk") =
        GitHubReleaseClient.ReleaseCheckResult.Available(latest, "https://gh/android-v$latest", apkUrl)

    private suspend fun MutableStateFlow<AppUpdateUiState>.await(
        predicate: (AppUpdateUiState) -> Boolean,
    ): AppUpdateUiState = withTimeout(15_000) { first(predicate) }

    // ── AC9 — debug short-circuit, NO network ───────────────────────────────

    @Test
    fun debug_build_short_circuits_with_no_network_call() = runBlocking {
        val supplierCalled = AtomicBoolean(false)
        val state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Checking)
        coordinator(
            state,
            isDebugBuild = true,
            releaseSupplier = { supplierCalled.set(true); GitHubReleaseClient.ReleaseCheckResult.NoRelease },
        ).check()

        val s = state.await { it is AppUpdateUiState.DebugBuild }
        assertThat((s as AppUpdateUiState.DebugBuild).current).isEqualTo("0.4.15")
        // AC9 — a debug build never touches the network.
        assertThat(supplierCalled.get()).isFalse
    }

    // ── AC3 — up to date ────────────────────────────────────────────────────

    @Test
    fun current_ge_latest_is_up_to_date() = runBlocking {
        val state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Checking)
        coordinator(
            state,
            currentVersion = "0.4.15",
            releaseSupplier = { available("0.4.15") }, // equal ⇒ up to date
        ).check()
        val s = state.await { it is AppUpdateUiState.UpToDate || it is AppUpdateUiState.Error }
        assertThat((s as AppUpdateUiState.UpToDate).current).isEqualTo("0.4.15")
    }

    @Test
    fun no_release_is_treated_as_up_to_date() = runBlocking {
        val state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Checking)
        coordinator(state, releaseSupplier = { GitHubReleaseClient.ReleaseCheckResult.NoRelease }).check()
        val s = state.await { it is AppUpdateUiState.UpToDate || it is AppUpdateUiState.Error }
        assertThat(s).isInstanceOf(AppUpdateUiState.UpToDate::class.java)
    }

    // ── AC4 — strictly newer ⇒ offer ────────────────────────────────────────

    @Test
    fun strictly_newer_release_offers_an_update() = runBlocking {
        val state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Checking)
        coordinator(
            state,
            currentVersion = "0.4.15",
            releaseSupplier = { available("0.5.0") },
        ).check()
        val s = state.await { it is AppUpdateUiState.UpdateAvailable || it is AppUpdateUiState.Error }
        val offer = s as AppUpdateUiState.UpdateAvailable
        assertThat(offer.current).isEqualTo("0.4.15")
        assertThat(offer.latest).isEqualTo("0.5.0")
        assertThat(offer.releaseHtmlUrl).isEqualTo("https://gh/android-v0.5.0")
    }

    // ── AC10 — ordering by semver, not lexical/versionCode ──────────────────

    @Test
    fun ordering_uses_semver_not_string_compare() = runBlocking {
        // current 0.10.0 vs latest 0.9.0: a string compare says "0.10.0" < "0.9.0"
        // and would wrongly offer a downgrade. Semver says current is newer ⇒ up to date.
        val state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Checking)
        coordinator(
            state,
            currentVersion = "0.10.0",
            releaseSupplier = { available("0.9.0") },
        ).check()
        val s = state.await { it is AppUpdateUiState.UpToDate || it is AppUpdateUiState.UpdateAvailable }
        assertThat(s).isInstanceOf(AppUpdateUiState.UpToDate::class.java)
    }

    // ── AC8 — graceful, retryable errors (never a crash) ────────────────────

    @Test
    fun rate_limited_surfaces_a_retryable_error() = runBlocking {
        val state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Checking)
        coordinator(state, releaseSupplier = { GitHubReleaseClient.ReleaseCheckResult.RateLimited }).check()
        val s = state.await { it is AppUpdateUiState.Error }
        assertThat((s as AppUpdateUiState.Error).code).isEqualTo("rate_limited")
    }

    @Test
    fun unreachable_surfaces_a_retryable_error() = runBlocking {
        val state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Checking)
        coordinator(
            state,
            releaseSupplier = { GitHubReleaseClient.ReleaseCheckResult.Unreachable("offline") },
        ).check()
        val s = state.await { it is AppUpdateUiState.Error }
        assertThat((s as AppUpdateUiState.Error).code).isEqualTo("unreachable")
        assertThat(s.detail).isEqualTo("offline")
    }

    @Test
    fun check_failed_surfaces_a_retryable_error() = runBlocking {
        val state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Checking)
        coordinator(
            state,
            releaseSupplier = { GitHubReleaseClient.ReleaseCheckResult.CheckFailed("bad shape") },
        ).check()
        val s = state.await { it is AppUpdateUiState.Error }
        assertThat((s as AppUpdateUiState.Error).code).isEqualTo("check_failed")
    }

    // ── AC5/AC6 — download → install ────────────────────────────────────────

    @Test
    fun update_downloads_then_installs() = runBlocking {
        val state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Checking)
        val installed = AtomicBoolean(false)
        val coord = coordinator(
            state,
            currentVersion = "0.4.15",
            releaseSupplier = { available("0.5.0") },
            downloader = { _, dest, _ ->
                dest.parentFile?.mkdirs()
                dest.writeBytes(ByteArray(8))
                GitHubReleaseClient.DownloadResult.Success(dest)
            },
            installer = { installed.set(true); InstallOutcome.Launched },
        )
        // Drive the check first so `pending` is set, then accept the update.
        coord.check()
        state.await { it is AppUpdateUiState.UpdateAvailable }

        coord.update()
        val terminal = state.await { it is AppUpdateUiState.Installing || it is AppUpdateUiState.Error }
        // AC5/AC6 — a successful download hands the APK to the installer.
        assertThat(terminal).isInstanceOf(AppUpdateUiState.Installing::class.java)
        assertThat(installed.get()).isTrue
    }

    // ── AC5 — visible download progress (deterministic, no StateFlow conflation race) ──

    @Test
    fun download_progress_is_surfaced_to_the_ui() = runBlocking {
        val state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Checking)
        // The downloader emits 42% then HOLDS until the test confirms it observed
        // the Downloading state — so the conflated StateFlow cannot skip past it.
        val sawProgress = kotlinx.coroutines.CompletableDeferred<Unit>()
        val coord = coordinator(
            state,
            currentVersion = "0.4.15",
            releaseSupplier = { available("0.5.0") },
            downloader = { _, dest, onProgress ->
                onProgress(42)
                sawProgress.await() // hold at 42% until the test has seen it
                GitHubReleaseClient.DownloadResult.Success(dest)
            },
            installer = { InstallOutcome.Launched },
        )
        coord.check()
        state.await { it is AppUpdateUiState.UpdateAvailable }

        coord.update()
        val downloading = state.await { it is AppUpdateUiState.Downloading && it.percent == 42 }
        assertThat((downloading as AppUpdateUiState.Downloading).percent).isEqualTo(42) // AC5
        sawProgress.complete(Unit) // release the download → install
        state.await { it is AppUpdateUiState.Installing }
    }

    // ── AC5/AC8 — download failure deletes the partial file ─────────────────

    @Test
    fun download_failure_deletes_partial_file_and_errors() = runBlocking {
        val state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Checking)
        val dest = destFile()
        dest.parentFile?.mkdirs()
        dest.writeBytes(ByteArray(16)) // a partial download left on disk
        assertThat(dest.exists()).isTrue

        val coord = coordinator(
            state,
            currentVersion = "0.4.15",
            releaseSupplier = { available("0.5.0") },
            downloader = { _, _, _ -> GitHubReleaseClient.DownloadResult.Failed("network drop") },
            dest = { dest },
        )
        coord.check()
        state.await { it is AppUpdateUiState.UpdateAvailable }
        coord.update()

        val s = state.await { it is AppUpdateUiState.Error }
        assertThat((s as AppUpdateUiState.Error).code).isEqualTo("download_failed")
        // AC5 — the partial file is removed before the retryable error is shown.
        assertThat(dest.exists()).isFalse
    }

    // ── AC6 — install needs the "unknown apps" grant ────────────────────────

    @Test
    fun install_needs_permission_surfaces_retryable_error() = runBlocking {
        val state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Checking)
        val coord = coordinator(
            state,
            currentVersion = "0.4.15",
            releaseSupplier = { available("0.5.0") },
            downloader = { _, dest, _ -> GitHubReleaseClient.DownloadResult.Success(dest) },
            installer = { InstallOutcome.NeedsPermission },
        )
        coord.check()
        state.await { it is AppUpdateUiState.UpdateAvailable }
        coord.update()

        val s = state.await { it is AppUpdateUiState.Error }
        // AC6 — guides the user to grant, with a Retry path (never a silent dead-end).
        assertThat((s as AppUpdateUiState.Error).code).isEqualTo("install_permission")
    }

    @Test
    fun install_failure_deletes_file_and_errors() = runBlocking {
        val state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Checking)
        val dest = destFile()
        val coord = coordinator(
            state,
            currentVersion = "0.4.15",
            releaseSupplier = { available("0.5.0") },
            downloader = { _, d, _ ->
                d.parentFile?.mkdirs(); d.writeBytes(ByteArray(8))
                GitHubReleaseClient.DownloadResult.Success(d)
            },
            installer = { InstallOutcome.Failed("installer missing") },
            dest = { dest },
        )
        coord.check()
        state.await { it is AppUpdateUiState.UpdateAvailable }
        coord.update()

        val s = state.await { it is AppUpdateUiState.Error }
        assertThat((s as AppUpdateUiState.Error).code).isEqualTo("install_failed")
        assertThat(dest.exists()).isFalse
    }

    // ── Missing asset & pre-offer guard ─────────────────────────────────────

    @Test
    fun update_with_no_apk_asset_surfaces_no_asset_error() = runBlocking {
        val state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Checking)
        val coord = coordinator(
            state,
            currentVersion = "0.4.15",
            releaseSupplier = { available("0.5.0", apkUrl = null) }, // resolved but no APK asset
        )
        coord.check()
        state.await { it is AppUpdateUiState.UpdateAvailable }
        coord.update()
        val s = state.await { it is AppUpdateUiState.Error }
        assertThat((s as AppUpdateUiState.Error).code).isEqualTo("no_asset")
    }

    @Test
    fun update_before_an_offer_is_a_no_op() = runBlocking {
        val state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Checking)
        val downloaderCalled = AtomicBoolean(false)
        val coord = coordinator(
            state,
            downloader = { _, dest, _ ->
                downloaderCalled.set(true); GitHubReleaseClient.DownloadResult.Success(dest)
            },
        )
        // No prior successful check ⇒ pending is null ⇒ update() returns immediately.
        coord.update()
        // Give any (erroneously launched) coroutine a chance to run.
        withTimeout(2_000) {
            kotlinx.coroutines.delay(200)
        }
        assertThat(downloaderCalled.get()).isFalse
        assertThat(state.value).isEqualTo(AppUpdateUiState.Checking)
    }
}
