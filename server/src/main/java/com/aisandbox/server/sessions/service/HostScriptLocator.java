package com.aisandbox.server.sessions.service;

import com.aisandbox.server.config.ServerProperties;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Resolves the absolute paths of the UC02 host scripts ({@code spawn.sh},
 * {@code clean.sh}, {@code attach.sh}) and validates their executable bit
 * at startup.
 *
 * <p>The repo root is supplied via
 * {@code ai-sandbox.server.hostscripts.repo-root}. Under the
 * {@code docs-only} profile (used by {@code :server:generateOpenApiDocs})
 * the validation is skipped so the OAS render can boot without a real
 * UC02 checkout on disk; production deployments still go through
 * {@code PropertiesValidationStartupCheck}, which performs the same
 * checks plus a few others.
 */
@Component
public class HostScriptLocator {

    private final Path repoRoot;
    private final Path spawnSh;
    private final Path cleanSh;
    private final Path attachSh;
    private final Path lifecycleSh;
    private final Environment environment;

    public HostScriptLocator(ServerProperties props, Environment environment) {
        this.repoRoot = props.hostscripts().repoRoot();
        this.spawnSh = repoRoot.resolve("spawn.sh");
        this.cleanSh = repoRoot.resolve("clean.sh");
        this.attachSh = repoRoot.resolve("attach.sh");
        // UC-46 — the new lifecycle.sh sibling drives docker compose
        // stop/start/pause/unpause for a single session.
        this.lifecycleSh = repoRoot.resolve("lifecycle.sh");
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        if (environment != null
                && java.util.Arrays.asList(environment.getActiveProfiles()).contains("docs-only")) {
            return;
        }
        for (Path p : new Path[] {spawnSh, cleanSh, attachSh, lifecycleSh}) {
            if (!Files.isRegularFile(p)) {
                throw new IllegalStateException("Host script missing: " + p);
            }
            if (!Files.isExecutable(p)) {
                throw new IllegalStateException("Host script not executable: " + p);
            }
        }
    }

    public Path repoRoot() {
        return repoRoot;
    }

    public Path spawnSh() {
        return spawnSh;
    }

    public Path cleanSh() {
        return cleanSh;
    }

    public Path attachSh() {
        return attachSh;
    }

    public Path lifecycleSh() {
        return lifecycleSh;
    }
}
