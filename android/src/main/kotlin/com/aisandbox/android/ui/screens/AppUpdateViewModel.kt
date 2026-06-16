package com.aisandbox.android.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.BuildConfig
import com.aisandbox.android.install.ApkInstaller
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UC-87 — ViewModel for the dedicated app-update screen. A thin Android wrapper
 * (mirrors [ServerUpdateViewModel]): owns the [MutableStateFlow], supplies the
 * {@code BuildConfig} version + debug flag, wires the [com.aisandbox.android.net.GitHubReleaseClient]
 * and [ApkInstaller], and exposes read-only [state] plus pass-through actions.
 * All orchestration lives in the JVM-testable [AppUpdateCoordinator].
 */
class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AiSandboxApplication).container
    private val appContext = application.applicationContext

    private val client = container.gitHubReleaseClient()
    private val installer = ApkInstaller(appContext)

    private val _state = MutableStateFlow<AppUpdateUiState>(AppUpdateUiState.Checking)
    val state: StateFlow<AppUpdateUiState> = _state.asStateFlow()

    private val coordinator = AppUpdateCoordinator(
        state = _state,
        scope = viewModelScope,
        currentVersion = BuildConfig.VERSION_NAME,
        isDebugBuild = BuildConfig.DEBUG,
        releaseSupplier = { client.latestStableRelease() },
        downloader = { url, dest, onProgress -> client.download(url, dest, onProgress) },
        installer = { file -> installer.install(file) },
        // Cache-dir subfolder mapped by res/xml/file_paths.xml → <cache-path name="updates">.
        destFileSupplier = { File(File(appContext.cacheDir, "updates"), "android-release.apk") },
    )

    /**
     * Init-once auto-check (AC2). The screen calls this from a keyed
     * {@code LaunchedEffect}; the guard makes the check fire exactly once for
     * the ViewModel's lifetime — not again on recomposition or rotation (the
     * ViewModel survives configuration changes).
     */
    private var autoChecked = false

    fun checkOnce() {
        if (autoChecked) return
        autoChecked = true
        coordinator.check()
    }

    /** AC4 — user tapped Update. */
    fun update() = coordinator.update()

    /** AC5/AC8 — user tapped Retry after an error. */
    fun retry() = coordinator.retry()
}
