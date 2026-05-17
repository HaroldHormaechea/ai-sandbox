package com.aisandbox.server.clients.facade;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.clients.dto.AllowedClient;
import com.aisandbox.server.clients.service.AllowlistDirectory;
import com.aisandbox.server.clients.service.ClientAllowlistService;
import com.aisandbox.server.clients.service.ClientCertParser;
import com.aisandbox.server.identity.ActiveConnectionRegistry;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Use-case-level entry point for the allowlist domain. Owns the audit
 * emission and the immediate-rebuild contract (so a {@code POST /v1/clients}
 * response reflects the new state without waiting for the watcher's
 * debounced rescan).
 *
 * <p>Per {@code profile-java-server-architecture}, this is the layer where
 * {@code @Transactional} would live if there were a transactional resource.
 * The allowlist is filesystem-backed; the equivalent atomicity guarantee
 * comes from {@link AllowlistDirectory#write}'s tmp+rename and the
 * service's {@link java.util.concurrent.atomic.AtomicReference} swap.
 */
@Component
public class ClientAllowlistFacade {

    private static final Logger LOG = LoggerFactory.getLogger(ClientAllowlistFacade.class);

    private final ClientAllowlistService service;
    private final AllowlistDirectory directory;
    private final ClientCertParser parser;
    private final ActiveConnectionRegistry registry;
    private final AuditLogger audit;

    public ClientAllowlistFacade(
            ClientAllowlistService service,
            AllowlistDirectory directory,
            ClientCertParser parser,
            ActiveConnectionRegistry registry,
            AuditLogger audit) {
        this.service = service;
        this.directory = directory;
        this.parser = parser;
        this.registry = registry;
        this.audit = audit;
    }

    public List<AllowedClient> listClients() {
        return service.list();
    }

    /**
     * Add a client cert to the allowlist. Validates the PEM, writes
     * atomically, and triggers an immediate rebuild. Returns the parsed
     * record on success.
     */
    public AllowedClient addClient(String name, String certPem) throws CertificateException, IOException {
        AllowedClient parsed = parser.parse(name, certPem);
        directory.write(name, certPem);
        service.rebuild();
        audit.logEvent(
                AuditAction.CLIENT_ADD, "ok", "name", name, "fingerprint", parsed.fingerprintHex(), "cn", parsed.cn());
        return parsed;
    }

    /**
     * Remove a client cert. {@code cnOrFingerprint} may be either the
     * filename stem (typically the CN) or the SHA-256 fingerprint.
     */
    public boolean removeClient(String cnOrFingerprint) throws IOException {
        Optional<AllowedClient> target = service.snapshot().values().stream()
                .filter(c -> c.name().equals(cnOrFingerprint)
                        || c.cn().equals(cnOrFingerprint)
                        || c.fingerprintHex().equalsIgnoreCase(cnOrFingerprint))
                .findFirst();
        if (target.isEmpty()) {
            return false;
        }
        AllowedClient client = target.get();
        boolean removed = directory.deleteByName(client.name());
        if (removed) {
            var revoked = service.rebuild();
            // UC04 § B2 — orchestration entry: graceful WS close (so the
            // Android client sees AC26 "Identity revoked") then TCP-layer
            // tear-down. terminate() is still public for back-compat but
            // is no longer called directly from production code.
            registry.revoke(revoked);
            audit.logEvent(
                    AuditAction.CLIENT_REMOVE,
                    "ok",
                    "name",
                    client.name(),
                    "fingerprint",
                    client.fingerprintHex(),
                    "cn",
                    client.cn(),
                    "trigger",
                    "api");
        } else {
            LOG.info("removeClient: nothing to delete for {}", cnOrFingerprint);
        }
        return removed;
    }
}
