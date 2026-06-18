package com.aisandbox.server.stream.replay;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * UC-85 (AC-11, layer 3) — the boot-time production-safety fail-fast for the deterministic-gate
 * {@code replay} profile. The {@code replay} profile substitutes recorded fixtures for the live
 * docker transcript, exposes synthetic sessions, and replaces answer-injection with a recorded
 * echo. None of that may ever run on a real deployment. This guard aborts startup if the
 * {@code replay} profile is active alongside a <b>production marker</b> — by default the systemd
 * production config file ({@code /etc/ai-sandbox-server/config.yaml}). Combined with the two
 * other layers (the test-asserted bean-absence outside replay, and the packaging-surface
 * assertions that no shipped artifact activates {@code replay}), this makes activating replay on
 * a production host impossible-by-construction rather than merely discouraged.
 */
@Component
@Profile("replay")
public class ReplayProfileGuard {

    private static final Logger LOG = LoggerFactory.getLogger(ReplayProfileGuard.class);

    private final ReplayProperties props;

    public ReplayProfileGuard(ReplayProperties props) {
        this.props = props;
    }

    @PostConstruct
    void assertNotProduction() {
        String marker = props.productionMarkerPath();
        if (marker != null && !marker.isBlank() && Files.exists(Path.of(marker))) {
            throw new IllegalStateException("FATAL: the deterministic-gate 'replay' profile is active but a "
                    + "production marker exists at '" + marker + "'. The replay profile must NEVER run on a "
                    + "production deployment (it serves recorded fixtures and echoes answers instead of "
                    + "driving a live session). Aborting startup. (UC-85 AC-11)");
        }
        LOG.warn("ai-sandbox 'replay' profile is ACTIVE — serving recorded fixtures, synthetic sessions, and "
                + "answer echoes. This is the deterministic functional gate; do NOT use on production.");
    }
}
