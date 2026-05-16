package com.aisandbox.server.sessions.service;

import com.aisandbox.server.config.ServerProperties;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * Resolves the absolute paths of the UC02 host scripts ({@code spawn.sh},
 * {@code clean.sh}, {@code attach.sh}) and validates their executable bit
 * at startup. The repo root is supplied via
 * {@code ai-sandbox.server.hostscripts.repo-root}.
 */
@Component
public class HostScriptLocator {

    private final Path repoRoot;
    private final Path spawnSh;
    private final Path cleanSh;
    private final Path attachSh;

    public HostScriptLocator(ServerProperties props) {
        this.repoRoot = props.hostscripts().repoRoot();
        this.spawnSh = repoRoot.resolve("spawn.sh");
        this.cleanSh = repoRoot.resolve("clean.sh");
        this.attachSh = repoRoot.resolve("attach.sh");
    }

    @PostConstruct
    public void validate() {
        for (Path p : new Path[] {spawnSh, cleanSh, attachSh}) {
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
}
