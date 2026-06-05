package com.aisandbox.android

import android.content.Context
import com.aisandbox.android.identity.KeyStoreIdentityManager
import com.aisandbox.android.net.AiSandboxHttpClient
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.ServerProfileStore
import com.aisandbox.android.net.SessionEventsClient
import com.aisandbox.android.net.SessionsApi
import com.aisandbox.android.net.StreamClient
import com.aisandbox.android.net.TerminatingSessionsStore
import com.aisandbox.android.terminal.TerminalStreamController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Hand-rolled service locator. UC04 AC29 forbids analytics / telemetry
 * SDKs, which rules out a Hilt-pulled-by-Firebase build path — and Hilt
 * is the only "industry default" DI for Android that's worth its annotation
 * surface. For 8 screens + 1 service, manual wiring through a single
 * container is simpler and easier to audit.
 *
 * <p>One instance per process; lives on the [AiSandboxApplication]. Access
 * via [requireContainer]; ViewModels / Composables should NOT hold long
 * references — re-resolve on each composition so a profile-replacement
 * tears down the stale HTTP client.
 */
class AppContainer(applicationContext: Context) {

    private val appContext: Context = applicationContext.applicationContext

    val identity: KeyStoreIdentityManager = KeyStoreIdentityManager()

    val profileStore: ServerProfileStore = ServerProfileStore(appContext)

    /**
     * UC-28 — process-scoped optimistic-terminating set, shared by the
     * sessions list and the terminal screen so the "awaiting termination"
     * pill + delete-guard survive back-navigation between them.
     */
    val terminatingSessions: TerminatingSessionsStore = TerminatingSessionsStore()

    /**
     * Process-lifetime scope for container-internal observers (UC-28 only,
     * today). Never cancelled — it lives exactly as long as the singleton
     * [AppContainer], which lives as long as the process.
     */
    private val containerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        // UC-28 — clear every optimistic terminating flag whenever the active
        // server profile switches (re-enrollment replaces the identity, or a
        // wipe clears it). drop(1) skips the first emission (the cold-start
        // value, which is not a "switch"); distinctUntilChanged ignores
        // re-emissions of the same profile. A stale `n` from server A must
        // never linger onto server B's session list.
        profileStore.profile
            .distinctUntilChanged()
            .drop(1)
            .onEach { terminatingSessions.clearAll() }
            .launchIn(containerScope)
    }

    /**
     * Build a per-profile HTTP client. Cheap; callers can call this each
     * time the profile changes. Caller is responsible for letting the
     * old client garbage-collect (don't hold a reference longer than the
     * profile is valid).
     */
    fun httpClient(profile: ServerProfile): AiSandboxHttpClient =
        AiSandboxHttpClient(profile = profile, identity = identity)

    fun sessionsApi(client: AiSandboxHttpClient): SessionsApi = SessionsApi(client)

    fun streamClient(client: AiSandboxHttpClient, sessionN: Int): StreamClient =
        StreamClient(http = client, sessionN = sessionN)

    /**
     * UC-32 — build a client for the live sessions-list push feed
     * ({@code /v1/sessions/events}). Mirrors [streamClient]: cheap, per-profile,
     * rebuilt by the [com.aisandbox.android.ui.screens.SessionEventsController]
     * on each reconnect attempt.
     */
    fun sessionEventsClient(client: AiSandboxHttpClient): SessionEventsClient =
        SessionEventsClient(http = client)

    // ── Terminal stream controllers (UC-21) ─────────────────────────────────
    // Process-scoped, keyed by session N. Owning the controller here (not in
    // the NavBackStackEntry-scoped ViewModel) is what lets the WebSocket +
    // emulator survive back-navigation (AC#8 continuity). Only one session
    // streams at a time: opening a different N closes the prior controller.
    private val controllers = HashMap<Int, TerminalStreamController>()

    @Synchronized
    fun terminalController(sessionN: Int): TerminalStreamController {
        controllers[sessionN]?.let { return it }
        // Single active controller — tear down any other session's stream.
        controllers.keys.filter { it != sessionN }.forEach { other ->
            controllers.remove(other)?.close("switch-session")
        }
        val controller = TerminalStreamController(
            appContext = appContext,
            sessionN = sessionN,
            profileStore = profileStore,
            httpClientFactory = ::httpClient,
            streamClientFactory = ::streamClient,
            onClosed = { closedN -> synchronized(this) { controllers.remove(closedN) } },
        )
        controllers[sessionN] = controller
        return controller
    }
}

/** Lookup helper for ViewModels / Composables; throws if the Application class is mis-wired. */
fun requireContainer(context: Context): AppContainer {
    val app = context.applicationContext as? AiSandboxApplication
        ?: error("Application is not AiSandboxApplication — manifest android:name is mis-wired.")
    return app.container
}
