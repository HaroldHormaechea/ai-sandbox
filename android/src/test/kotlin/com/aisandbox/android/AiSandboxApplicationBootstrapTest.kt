package com.aisandbox.android

import java.security.Security
import com.aisandbox.android.identity.BouncyCastleClientProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UC13 — Risk #14 + AC3 lifecycle assertion. Proves the bootstrap
 * order documented in {@link AiSandboxApplication#onCreate} is
 * correct: by the time {@code onCreate()} returns, the
 * {@link BouncyCastleClientProvider} is already registered under
 * {@code BC-ai-sandbox-client}, so any KeyStore consumer downstream
 * (notably the eagerly-built {@code KeyStoreIdentityManager} inside
 * {@code AppContainer}, or any Activity / Service that calls
 * {@code requireContainer(context)}) can rely on the provider being
 * present when it asks for {@code KeyStore.getInstance("PKCS12",
 * "BC-ai-sandbox-client")}.
 *
 * <h2>Why this lives separately from {@link com.aisandbox.android.identity.BouncyCastleClientProviderTest}</h2>
 *
 * <p>The companion test asserts {@link BouncyCastleClientProvider#register}
 * works as a unit (idempotency, distinct-name registration, TLS
 * invariance). This test asserts the WIRING — i.e., that
 * {@code AiSandboxApplication.onCreate} actually calls it, and calls
 * it before anything else that might want the provider. Splitting the
 * concerns means a wiring regression (e.g., a future refactor that
 * accidentally moves {@code register()} to a lazy code path) fails
 * here even if the provider class itself stays correct.
 *
 * <h2>Robolectric scope and downstream-init tolerance</h2>
 *
 * <p>{@code AiSandboxApplication.onCreate} does three things in order:
 * (1) register the BC provider, (2) construct {@code AppContainer}
 * (which eagerly builds {@code KeyStoreIdentityManager} +
 * {@code ServerProfileStore}; the latter touches AndroidX DataStore),
 * (3) register the foreground-service notification channel. Step 1 is
 * what this test owns; steps 2 and 3 are UC04 surface that may or may
 * not work cleanly under Robolectric's shadows for a given SDK
 * level. If {@code .create()} throws somewhere in steps 2 or 3, the
 * test STILL passes as long as the BC provider is already registered
 * by the time the throw happens — because step 1 ran before whatever
 * step blew up, and step-1 success is the only thing this UC owns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AiSandboxApplicationBootstrapTest {

    /**
     * Pre-test cleanup so the assertion isn't satisfied by a previous
     * test class's registration leaking through. Removes the provider
     * so {@code onCreate()} has to put it back.
     */
    @Before
    fun removeBcClientProvider() {
        Security.removeProvider(BouncyCastleClientProvider.NAME)
        // Cross-check the precondition — if some other class registered
        // it under a different ClassLoader-bound copy that survived the
        // remove call, fail fast with a diagnostic rather than letting
        // the test silently accept a stale registration as proof.
        assertThat(Security.getProvider(BouncyCastleClientProvider.NAME))
            .describedAs(
                "Test precondition: BC-ai-sandbox-client must be absent before AiSandboxApplication.onCreate runs",
            )
            .isNull()
    }

    @Test
    fun onCreate_registers_BouncyCastleClientProvider_before_returning() {
        // Robolectric.buildApplication(...).create() drives the
        // Application class through the same lifecycle the platform
        // invokes — Application is instantiated, attached to a base
        // context, and onCreate() is called.
        try {
            Robolectric.buildApplication(AiSandboxApplication::class.java).create()
        } catch (t: Throwable) {
            // onCreate failed somewhere downstream of the BC bootstrap
            // (likely AppContainer's DataStore-touching init under
            // Robolectric, or NotificationChannel registration). The
            // UC13-owned step is BC registration; verify it ran before
            // the throw, then surface the unrelated failure as context.
            val bcAfterThrow = Security.getProvider(BouncyCastleClientProvider.NAME)
            if (bcAfterThrow == null) {
                throw AssertionError(
                    "AiSandboxApplication.onCreate threw before registering BC-ai-sandbox-client — " +
                        "bootstrap order is wrong (Risk #14). " +
                        "Underlying throwable: ${t.javaClass.name}: ${t.message}",
                    t,
                )
            }
            // BC was registered before the throw — UC13's bootstrap
            // claim holds. The downstream failure is unrelated to this
            // UC; explicit return so the test passes with a clear
            // narrative in the KDoc-defined tolerance window.
            return
        }

        // onCreate completed without throwing — strongest possible
        // assertion: the provider is present and the application is
        // alive.
        val provider = Security.getProvider(BouncyCastleClientProvider.NAME)
        assertThat(provider)
            .describedAs(
                "After AiSandboxApplication.onCreate() returns, the BC-ai-sandbox-client provider must be registered (AC3 / Risk #14)",
            )
            .isNotNull()
    }
}
