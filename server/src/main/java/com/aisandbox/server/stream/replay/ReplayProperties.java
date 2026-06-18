package com.aisandbox.server.stream.replay;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * UC-85 — configuration for the deterministic-gate {@code replay} profile. Bound only when
 * that profile is active, so none of these knobs exist (or are read) in a normal server run.
 *
 * <ul>
 *   <li>{@code dir} — directory holding {@code manifest.json} and the {@code *.tail} fixtures,
 *       resolved relative to the server process working directory (the gate launches the
 *       bootJar with this set to the repo's {@code fixtures/replay/}). Default
 *       {@code fixtures/replay}.</li>
 *   <li>{@code answerTimeoutMs} — how long a replay tail parks at its {@code await-answer}
 *       gate waiting for the device's answer before giving up and continuing (so a wedged
 *       gate run can never hang a tail thread forever). Default 30s.</li>
 *   <li>{@code productionMarkerPath} — a filesystem path whose presence means "this is a real
 *       deployment"; if it exists while the {@code replay} profile is active the server aborts
 *       at boot ({@link ReplayProfileGuard}, AC-11 layer 3). Default the systemd prod config
 *       file {@code /etc/ai-sandbox-server/config.yaml}.</li>
 * </ul>
 */
@Component
@Profile("replay")
public class ReplayProperties {

    private final String dir;
    private final long answerTimeoutMs;
    private final String productionMarkerPath;

    public ReplayProperties(
            @Value("${ai-sandbox.server.replay.dir:fixtures/replay}") String dir,
            @Value("${ai-sandbox.server.replay.answer-timeout-ms:30000}") long answerTimeoutMs,
            @Value("${ai-sandbox.server.replay.production-marker-path:/etc/ai-sandbox-server/config.yaml}")
                    String productionMarkerPath) {
        this.dir = dir;
        this.answerTimeoutMs = answerTimeoutMs;
        this.productionMarkerPath = productionMarkerPath;
    }

    public String dir() {
        return dir;
    }

    public long answerTimeoutMs() {
        return answerTimeoutMs;
    }

    public String productionMarkerPath() {
        return productionMarkerPath;
    }
}
