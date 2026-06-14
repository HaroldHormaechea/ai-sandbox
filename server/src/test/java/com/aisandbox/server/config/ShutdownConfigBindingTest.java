package com.aisandbox.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * UC-74 (AC2) — the baked {@code application.yaml} MUST carry the lowered,
 * bounded shutdown budgets and the explicit per-phase shutdown cap, and they
 * MUST bind to the types the runtime reads. This is the unit-tier guard that
 * the values are actually present and well-formed (a typo'd key or a stray
 * {@code 60} sneaking back would otherwise only surface as a slow restart in
 * production):
 *
 * <ul>
 *   <li>{@code spring.lifecycle.timeout-per-shutdown-phase} binds to a
 *       {@link Duration} (Spring's {@code SmartLifecycle} phase cap), and</li>
 *   <li>{@code ai-sandbox.server.shutdown} binds to
 *       {@link ServerProperties.Shutdown} with the new {@code rest-grace=5} /
 *       {@code total-grace=10} values.</li>
 * </ul>
 *
 * <p>The {@code TimeoutStopSec > (phase + total-grace)} invariant against the
 * systemd unit file is asserted by
 * {@code com.aisandbox.server.systemd.UnitFileContractTest}.
 */
class ShutdownConfigBindingTest {

    private static Binder applicationYamlBinder() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        assertThat(sources)
                .as("application.yaml must be on the test classpath and parseable")
                .isNotEmpty();
        return new Binder(ConfigurationPropertySources.from(sources));
    }

    @Test
    void shutdown_grace_budgets_bind_to_the_lowered_uc74_values() throws IOException {
        ServerProperties.Shutdown shutdown = applicationYamlBinder()
                .bind("ai-sandbox.server.shutdown", Bindable.of(ServerProperties.Shutdown.class))
                .get();

        assertThat(shutdown.restGraceSeconds())
                .as("UC-74 lowered the per-close REST grace from 30s to 5s")
                .isEqualTo(5);
        assertThat(shutdown.totalGraceSeconds())
                .as("UC-74 lowered the total graceful-WS-drain budget from 60s to 10s")
                .isEqualTo(10);
    }

    @Test
    void explicit_per_shutdown_phase_timeout_is_present_and_bounded() throws IOException {
        Duration phase = applicationYamlBinder()
                .bind("spring.lifecycle.timeout-per-shutdown-phase", Bindable.of(Duration.class))
                .get();

        assertThat(phase)
                .as("UC-74 added an explicit SmartLifecycle phase cap (default would be 30s)")
                .isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void internal_grace_budgets_sum_strictly_below_the_systemd_stop_timeout() throws IOException {
        // AC2 invariant restated at the config tier (the systemd side is checked
        // in UnitFileContractTest): phase(15s) + total-grace(10s) = 25s << 70s.
        Binder binder = applicationYamlBinder();
        Duration phase = binder.bind("spring.lifecycle.timeout-per-shutdown-phase", Bindable.of(Duration.class))
                .get();
        ServerProperties.Shutdown shutdown = binder.bind(
                        "ai-sandbox.server.shutdown", Bindable.of(ServerProperties.Shutdown.class))
                .get();

        long internalGraceSeconds = phase.getSeconds() + shutdown.totalGraceSeconds();
        assertThat(internalGraceSeconds)
                .as("sum of internal shutdown grace budgets must stay well under TimeoutStopSec=70")
                .isLessThan(70L);
    }
}
