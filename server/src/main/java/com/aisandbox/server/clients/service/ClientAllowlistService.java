package com.aisandbox.server.clients.service;

import com.aisandbox.server.clients.dto.AllowedClient;
import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * In-memory authoritative snapshot of the allowlist directory, keyed by
 * SHA-256 fingerprint. Wrapped in an {@link AtomicReference} so the
 * watcher's CAS swap is observably atomic to callers (trust manager,
 * facade, controller).
 *
 * <p>This is the only component allowed to touch the {@code snapshot}
 * reference. Callers read via {@link #snapshot()} and never mutate.
 */
@Service
public class ClientAllowlistService {

    private static final Logger LOG = LoggerFactory.getLogger(ClientAllowlistService.class);

    private final AtomicReference<Map<String, AllowedClient>> snapshot = new AtomicReference<>(Map.of());

    private final AllowlistDirectory directory;
    private final ClientCertParser parser;

    public ClientAllowlistService(AllowlistDirectory directory, ClientCertParser parser) {
        this.directory = directory;
        this.parser = parser;
    }

    /** Read-only view of the current allowlist, keyed by fingerprint. */
    public Map<String, AllowedClient> snapshot() {
        return snapshot.get();
    }

    public List<AllowedClient> list() {
        return List.copyOf(snapshot.get().values());
    }

    public boolean isAllowed(String fingerprintHex) {
        return snapshot.get().containsKey(fingerprintHex);
    }

    /**
     * Rescan the allowlist directory and atomically swap the snapshot.
     * Returns the set difference {@code old - new} so the caller can
     * terminate active connections for revoked fingerprints.
     *
     * <p>Soft-fails on parse errors of individual files — those entries
     * are skipped and logged; rest of the rebuild proceeds.
     */
    public java.util.Set<String> rebuild() {
        Map<String, AllowedClient> next = new LinkedHashMap<>();
        try {
            for (var path : directory.listCerts()) {
                String pem;
                try {
                    pem = directory.readCertPem(path);
                } catch (IOException io) {
                    LOG.warn("Skipping allowlist entry {} (unreadable): {}", path, io.toString());
                    continue;
                }
                String stem = stemOf(path.getFileName().toString());
                try {
                    AllowedClient client = parser.parse(stem, pem);
                    if (next.put(client.fingerprintHex(), client) != null) {
                        LOG.warn("Duplicate fingerprint in allowlist (entry={}); last wins", stem);
                    }
                } catch (CertificateException | IOException e) {
                    LOG.warn("Skipping allowlist entry {} (parse error): {}", path, e.toString());
                }
            }
        } catch (IOException io) {
            LOG.error("Cannot scan allowlist directory: {}", io.toString());
            return java.util.Set.of();
        }
        Map<String, AllowedClient> previous = snapshot.getAndSet(java.util.Map.copyOf(next));
        java.util.Set<String> revoked = new java.util.HashSet<>(previous.keySet());
        revoked.removeAll(next.keySet());
        LOG.info(
                "Allowlist rebuilt: {} entries (previously {}; {} revoked)",
                next.size(),
                previous.size(),
                revoked.size());
        return revoked;
    }

    private static String stemOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? filename : filename.substring(0, dot);
    }
}
