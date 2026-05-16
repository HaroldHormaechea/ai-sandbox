package com.aisandbox.server.health.service;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.sessions.service.HostScriptLocator;
import com.aisandbox.server.sessions.service.ProcessExecutor;
import com.aisandbox.server.tls.ReloadableSslContextHolder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Lightweight health probe. Three orthogonal checks (AC19 /v1/healthz):
 *
 * <ul>
 *   <li>Docker reachable — {@code docker info} exits 0.</li>
 *   <li>UC02 scripts present + executable.</li>
 *   <li>TLS material loaded (the holder's atomic ref is non-null).</li>
 * </ul>
 */
@Service
public class HealthService {

    public record HealthSnapshot(boolean dockerOk, boolean scriptsOk, boolean tlsOk, String detail) {
        public boolean healthy() {
            return dockerOk && scriptsOk && tlsOk;
        }
    }

    private final ProcessExecutor executor;
    private final HostScriptLocator locator;
    private final ReloadableSslContextHolder tlsHolder;

    public HealthService(
            ProcessExecutor executor,
            HostScriptLocator locator,
            ServerProperties props,
            org.springframework.beans.factory.ObjectProvider<ReloadableSslContextHolder> tlsProvider) {
        this.executor = executor;
        this.locator = locator;
        this.tlsHolder = tlsProvider.getIfAvailable();
        // props retained for future extension — keep on the constructor for symmetry.
        java.util.Objects.requireNonNull(props);
    }

    public HealthSnapshot probe() {
        boolean dockerOk = dockerReachable();
        boolean scriptsOk = scriptsReady();
        boolean tlsOk = tlsLoaded();
        String detail = "docker=" + dockerOk + ",scripts=" + scriptsOk + ",tls=" + tlsOk;
        return new HealthSnapshot(dockerOk, scriptsOk, tlsOk, detail);
    }

    private boolean dockerReachable() {
        try {
            ProcessExecutor.Result r = executor.run(
                    List.of("docker", "info", "--format", "{{.ServerVersion}}"), null, Duration.ofSeconds(3));
            return r.exitCode() == 0;
        } catch (IOException io) {
            return false;
        }
    }

    private boolean scriptsReady() {
        for (Path p : new Path[] {locator.spawnSh(), locator.cleanSh(), locator.attachSh()}) {
            if (!Files.isRegularFile(p) || !Files.isExecutable(p)) {
                return false;
            }
        }
        return true;
    }

    private boolean tlsLoaded() {
        if (tlsHolder == null) {
            return true; // docs-only profile path
        }
        try {
            return tlsHolder.current() != null;
        } catch (IllegalStateException e) {
            return false;
        }
    }
}
