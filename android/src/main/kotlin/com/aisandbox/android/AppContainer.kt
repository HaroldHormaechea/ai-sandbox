package com.aisandbox.android

import android.content.Context
import com.aisandbox.android.identity.KeyStoreIdentityManager
import com.aisandbox.android.net.AiSandboxHttpClient
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.ServerProfileStore
import com.aisandbox.android.net.SessionsApi
import com.aisandbox.android.net.StreamClient

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
}

/** Lookup helper for ViewModels / Composables; throws if the Application class is mis-wired. */
fun requireContainer(context: Context): AppContainer {
    val app = context.applicationContext as? AiSandboxApplication
        ?: error("Application is not AiSandboxApplication — manifest android:name is mis-wired.")
    return app.container
}
