package com.aisandbox.android

import androidx.test.core.app.ApplicationProvider
import com.aisandbox.android.identity.BouncyCastleClientProvider
import java.security.Security
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
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
 * <h2>Wiring under Robolectric 4.x</h2>
 *
 * <p>{@code Robolectric.buildApplication(...)} was removed from
 * Robolectric 4.x in favour of {@code @Config(application = ...)}
 * plus {@code ApplicationProvider.getApplicationContext()}: the
 * runner instantiates the declared {@code Application} subclass and
 * invokes {@code onCreate()} as part of test-environment bootstrap —
 * BEFORE the {@code @Test} method runs. That is exactly the lifecycle
 * point this test cares about: by the time the test body executes,
 * {@code AiSandboxApplication.onCreate} has either succeeded (and
 * the BC provider must be present) or thrown (in which case the
 * runner aborts the test class with an initialization error and we
 * never get here).
 *
 * <h2>Why this lives separately from {@link com.aisandbox.android.identity.BouncyCastleClientProviderTest}</h2>
 *
 * <p>The companion test asserts {@link BouncyCastleClientProvider#register}
 * works as a unit (idempotency, distinct-name registration, TLS
 * invariance). This test asserts the WIRING — i.e., that
 * {@code AiSandboxApplication.onCreate} actually calls it. Splitting
 * the concerns means a wiring regression (e.g., a future refactor
 * that accidentally moves {@code register()} to a lazy code path)
 * fails here even if the provider class itself stays correct.
 *
 * <h2>Robolectric scope and downstream-init tolerance</h2>
 *
 * <p>{@code AiSandboxApplication.onCreate} does three things in order:
 * (1) register the BC provider, (2) construct {@code AppContainer}
 * (which eagerly builds {@code KeyStoreIdentityManager} +
 * {@code ServerProfileStore}; the latter touches AndroidX DataStore),
 * (3) register the foreground-service notification channel. Step 1 is
 * what this test owns. If steps 2 or 3 throw under Robolectric's
 * shadow, the runner reports the initialization error — that is a
 * Robolectric-shadow problem to debug at that level, not a UC13
 * regression. Tests for AC1 / AC2 (the BC unwrap path) live in
 * {@link com.aisandbox.android.identity.KeyStoreIdentityManagerPkcs12ImportTest}
 * and do NOT depend on this test's downstream init succeeding.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = AiSandboxApplication::class)
class AiSandboxApplicationBootstrapTest {

    @Test
    fun onCreate_registers_BouncyCastleClientProvider_before_returning() {
        // Touch the application to ensure the runner has fully
        // bootstrapped it (defensive — the runner instantiates the
        // application during environment bring-up regardless).
        val app: AiSandboxApplication = ApplicationProvider.getApplicationContext()
        assertThat(app)
            .describedAs("Robolectric did not instantiate AiSandboxApplication — check @Config(application = ...)")
            .isNotNull()

        val provider = Security.getProvider(BouncyCastleClientProvider.NAME)
        assertThat(provider)
            .describedAs(
                "After AiSandboxApplication.onCreate() returns, the BC-ai-sandbox-client provider must be registered (AC3 / Risk #14)",
            )
            .isNotNull()
    }
}
