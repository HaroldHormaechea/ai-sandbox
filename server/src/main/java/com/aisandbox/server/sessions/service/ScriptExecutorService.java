package com.aisandbox.server.sessions.service;

import com.aisandbox.server.sessions.dto.SpawnCommand;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Translates validated DTOs to argument arrays and delegates to
 * {@link ProcessExecutor}. The argv assembly here is the only path
 * allowed to construct host-script invocations; AC24 (no shell
 * interpolation) hangs off of this rule.
 */
@Service
public class ScriptExecutorService {

    private final HostScriptLocator locator;
    private final ProcessExecutor executor;

    public ScriptExecutorService(HostScriptLocator locator, ProcessExecutor executor) {
        this.locator = locator;
        this.executor = executor;
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
        return executor.run(argv, locator.repoRoot(), timeout);
    }

    public ProcessExecutor.Result clean(int n, Duration timeout) throws IOException {
        if (n < 0) {
            throw new IllegalArgumentException("session number must be >= 0");
        }
        List<String> argv =
                List.of(locator.cleanSh().toString(), "--non-interactive", "--session", Integer.toString(n));
        return executor.run(argv, locator.repoRoot(), timeout);
    }
}
