package com.aisandbox.server.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fails the boot if the operator's configuration references missing or
 * unreadable filesystem material — AC6 in the use-case.
 *
 * <p>Specifically refuses to come up when:
 *
 * <ul>
 *   <li>The server key or certificate is unreadable.</li>
 *   <li>The allowlist folder is empty (refuse-to-start policy).</li>
 *   <li>UC02 scripts {@code spawn.sh}, {@code attach.sh}, {@code clean.sh}
 *       are missing or non-executable.</li>
 *   <li>The audit-log directory is missing or not writable.</li>
 * </ul>
 *
 * <p>Docker socket reachability is a runtime-affecting precondition but
 * not a strict boot failure — it surfaces through {@code GET /v1/healthz}
 * once the listener is up.
 *
 * <p>Disabled under the {@code docs-only} profile so OAS generation does
 * not need a populated /etc tree.
 */
@Component
@Profile("!docs-only")
public class PropertiesValidationStartupCheck implements ApplicationListener<ApplicationStartedEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(PropertiesValidationStartupCheck.class);

    private final ServerProperties props;

    public PropertiesValidationStartupCheck(ServerProperties props) {
        this.props = props;
    }

    @Override
    public void onApplicationEvent(ApplicationStartedEvent event) {
        verify();
    }

    void verify() {
        Path pkiDir = props.pki().dir();
        Path serverCrt = pkiDir.resolve("server.crt");
        Path serverKey = pkiDir.resolve("server.key");
        requireReadable(serverCrt, "server certificate");
        requireReadable(serverKey, "server private key");

        Path allowlistDir = props.clients().dir();
        if (!Files.isDirectory(allowlistDir)) {
            throw new IllegalStateException("Allowlist directory missing: " + allowlistDir);
        }
        try (var entries = Files.list(allowlistDir)) {
            if (entries.findAny().isEmpty()) {
                throw new IllegalStateException("Allowlist directory is empty (" + allowlistDir
                        + "): refuse-to-start policy. Mint a client cert via `aisandboxctl client mint <name>`.");
            }
        } catch (IOException io) {
            throw new IllegalStateException("Cannot read allowlist directory " + allowlistDir, io);
        }

        Path repoRoot = props.hostscripts().repoRoot();
        for (String script : new String[] {"spawn.sh", "attach.sh", "clean.sh"}) {
            Path p = repoRoot.resolve(script);
            if (!Files.isRegularFile(p) || !Files.isExecutable(p)) {
                throw new IllegalStateException("Required UC02 host script not present or not executable: " + p);
            }
        }

        Path auditDir = props.audit().file().getParent();
        if (auditDir == null || !Files.isDirectory(auditDir) || !Files.isWritable(auditDir)) {
            throw new IllegalStateException("Audit log directory missing or not writable: " + auditDir
                    + ". Run `sudo aisandboxctl pki init` to bootstrap the host's directory tree.");
        }

        LOG.info(
                "Startup preflight passed (pki={}, allowlist={}, scripts={}, audit={})",
                pkiDir,
                allowlistDir,
                repoRoot,
                auditDir);
    }

    private static void requireReadable(Path p, String label) {
        if (!Files.isRegularFile(p) || !Files.isReadable(p)) {
            throw new IllegalStateException(label + " not readable: " + p);
        }
    }
}
