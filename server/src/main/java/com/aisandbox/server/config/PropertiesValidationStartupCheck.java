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
 *   <li>The allowlist <em>directory</em> is missing.</li>
 *   <li>UC02 scripts {@code spawn.sh}, {@code attach.sh}, {@code clean.sh}
 *       and the UC-46 {@code lifecycle.sh} are missing or non-executable.</li>
 *   <li>The audit-log directory is missing or not writable.</li>
 * </ul>
 *
 * <p>An <em>empty</em> allowlist directory is NOT a boot failure: it is the
 * intended fresh-install bootstrap state (a brand-new {@code .deb} install
 * has no client certs yet). The server starts and logs a warning. With
 * {@code clientAuth=OPTIONAL} the mTLS enforcement filter rejects every
 * request with 401 until the operator authorizes a client — either by
 * minting a cert ({@code aisandboxctl client mint <name>}) or by enrolling a
 * device ({@code aisandboxctl client invite <name>} + QR). Both paths
 * hot-reload via the allowlist watcher with no restart required.
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
                LOG.warn(
                        "Allowlist directory is empty ({}): the server is up but NO clients are authorized yet — "
                                + "every request is rejected with 401 until you authorize one. Authorize a client by "
                                + "minting a cert (`aisandboxctl client mint <name>`) or enrolling a device "
                                + "(`aisandboxctl client invite <name>` + QR). Both hot-reload; no restart required.",
                        allowlistDir);
            }
        } catch (IOException io) {
            throw new IllegalStateException("Cannot read allowlist directory " + allowlistDir, io);
        }

        Path repoRoot = props.hostscripts().repoRoot();
        for (String script : new String[] {"spawn.sh", "attach.sh", "clean.sh", "lifecycle.sh"}) {
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
