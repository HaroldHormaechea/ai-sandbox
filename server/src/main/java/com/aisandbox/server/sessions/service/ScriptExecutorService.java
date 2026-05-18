package com.aisandbox.server.sessions.service;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.sessions.dto.SpawnCommand;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Translates validated DTOs to argument arrays and delegates to
 * {@link ProcessExecutor}. The argv assembly here is the only path
 * allowed to construct host-script invocations; AC24 (no shell
 * interpolation) hangs off of this rule.
 *
 * <p>UC05 § AC25,AC26,AC27 + UC06 § AC7 — every invocation also carries
 * four environment variables so the bundled scripts route writes off
 * the read-only install dir:
 *
 * <ul>
 *   <li>{@code AI_SANDBOX_COMPOSE_FILE} —
 *       {@code <hostscripts.repo-root>/docker-compose.yml}, consumed by
 *       {@code ai_sandbox_compose} in {@code lib.sh} / {@code lib.ps1}
 *       to prepend {@code -f}.</li>
 *   <li>{@code AI_SANDBOX_HOST_STATE_ROOT} —
 *       {@code sessions.host-state-root}, used both as
 *       {@code --project-directory} and as the cwd the scripts switch
 *       to before writing the counter / per-session dirs.</li>
 *   <li>{@code AI_SANDBOX_SECRETS_HOST_PATH} — {@code secrets.dir},
 *       interpolated by {@code docker-compose.yml}'s secrets bind-mount
 *       source.</li>
 *   <li>{@code AI_SANDBOX_CLAUDE_TEMPLATE_HOST_PATH} (UC06 § AC7) —
 *       sibling of {@code secrets.dir} (default
 *       {@code /etc/ai-sandbox-server/templates/claude-config/}),
 *       interpolated by {@code docker-compose.yml}'s claude-template
 *       RO bind-mount source. Captured by
 *       {@code aisandboxctl secrets seed}'s Claude pre-init step.</li>
 * </ul>
 */
@Service
public class ScriptExecutorService {

    private final HostScriptLocator locator;
    private final ProcessExecutor executor;
    private final ServerProperties props;

    @Autowired
    public ScriptExecutorService(HostScriptLocator locator, ProcessExecutor executor, ServerProperties props) {
        this.locator = locator;
        this.executor = executor;
        this.props = props;
    }

    /**
     * Back-compat 2-arg constructor for QA-owned test fixtures built
     * before UC05 added the {@link ServerProperties} dependency. With a
     * {@code null} props, {@link #composeEnv()} returns an empty map so
     * the executor falls back to a bare environment (the historical
     * behaviour). Production wiring always uses the 3-arg ctor via
     * {@link Autowired}; this overload exists only so existing test
     * code keeps compiling.
     */
    public ScriptExecutorService(HostScriptLocator locator, ProcessExecutor executor) {
        this(locator, executor, null);
    }

    public ProcessExecutor.Result spawn(SpawnCommand cmd, Duration timeout) throws IOException {
        List<String> argv = new ArrayList<>();
        argv.add(locator.spawnSh().toString());
        argv.add("--non-interactive");
        argv.add(cmd.workspaceMode().flag());
        argv.add(cmd.claudeConfigMode().flag());
        if (cmd.label() != null && !cmd.label().isEmpty()) {
            argv.add("--label");
            argv.add(cmd.label());
        }
        return executor.run(argv, locator.repoRoot(), composeEnv(), timeout);
    }

    public ProcessExecutor.Result clean(int n, Duration timeout) throws IOException {
        if (n < 0) {
            throw new IllegalArgumentException("session number must be >= 0");
        }
        List<String> argv =
                List.of(locator.cleanSh().toString(), "--non-interactive", "--session", Integer.toString(n));
        return executor.run(argv, locator.repoRoot(), composeEnv(), timeout);
    }

    private Map<String, String> composeEnv() {
        // Back-compat path for the 2-arg ctor: no props → empty env →
        // bare-environment ProcessBuilder, matching pre-UC05 behaviour.
        if (props == null) {
            return Map.of();
        }
        Map<String, String> env = new LinkedHashMap<>();
        env.put(
                "AI_SANDBOX_COMPOSE_FILE",
                locator.repoRoot().resolve("docker-compose.yml").toString());
        env.put("AI_SANDBOX_HOST_STATE_ROOT", props.sessions().hostStateRoot().toString());
        env.put("AI_SANDBOX_SECRETS_HOST_PATH", props.secrets().dir().toString());
        // UC06 § AC7 — Claude pre-init template lives as a sibling of
        // secrets.dir under /etc/ai-sandbox-server/. The leaf segment
        // matches `aisandboxctl secrets seed`'s --templates-dir default
        // (templates/claude-config). Deriving from secrets.dir avoids a
        // new ConfigurationProperties field and a forced re-bind of
        // existing /etc/ai-sandbox-server/config.yaml on upgrade — the
        // wizard runs as `secrets seed --force` per AC14's migration
        // path, no config rewrite needed.
        Path secretsDir = props.secrets().dir();
        Path templatesParent =
                (secretsDir.getParent() != null) ? secretsDir.getParent() : Path.of("/etc/ai-sandbox-server");
        Path claudeTemplate = templatesParent.resolve("templates").resolve("claude-config");
        env.put("AI_SANDBOX_CLAUDE_TEMPLATE_HOST_PATH", claudeTemplate.toString());
        return env;
    }
}
