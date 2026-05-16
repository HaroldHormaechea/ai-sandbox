package com.aisandbox.server.stream.facade;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.sessions.facade.internal.PerSessionMutexRegistry;
import com.aisandbox.server.sessions.service.SessionRegistryService;
import com.aisandbox.server.stream.service.StreamRegistryService;
import com.aisandbox.server.stream.service.StreamRegistryService.ActiveStream;
import com.aisandbox.server.stream.service.StreamRegistryService.StreamId;
import com.aisandbox.server.stream.service.TmuxBridgeService;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;

/**
 * Use-case-level entry point for the WebSocket stream domain. Owns the
 * stream-cap checks at handshake time (AC28), the per-N mutex when
 * spinning up a bridge (AC25), the audit emissions (AC42), and the
 * draining flag honoured by graceful shutdown.
 */
@Component
public class StreamFacade {

    private static final Logger LOG = LoggerFactory.getLogger(StreamFacade.class);

    private final SessionRegistryService sessionRegistry;
    private final StreamRegistryService streamRegistry;
    private final TmuxBridgeService tmux;
    private final PerSessionMutexRegistry perN;
    private final AuditLogger audit;
    private final ServerProperties props;
    private volatile boolean draining = false;

    public StreamFacade(
            SessionRegistryService sessionRegistry,
            StreamRegistryService streamRegistry,
            TmuxBridgeService tmux,
            PerSessionMutexRegistry perN,
            AuditLogger audit,
            ServerProperties props) {
        this.sessionRegistry = sessionRegistry;
        this.streamRegistry = streamRegistry;
        this.tmux = tmux;
        this.perN = perN;
        this.audit = audit;
        this.props = props;
    }

    /** Sealed result of the cap / existence check run before upgrading the WebSocket. */
    public sealed interface AuthorizeResult permits Allowed, SessionNotFound, CapExceeded, Draining {}

    public record Allowed() implements AuthorizeResult {}

    public record SessionNotFound(int n) implements AuthorizeResult {}

    public record CapExceeded(String scope) implements AuthorizeResult {}

    public record Draining() implements AuthorizeResult {}

    public AuthorizeResult authorizeOpen(int n, ClientIdentity identity) {
        if (draining) {
            return new Draining();
        }
        try {
            if (!sessionRegistry.exists(n)) {
                return new SessionNotFound(n);
            }
        } catch (IOException io) {
            LOG.warn("authorizeOpen({}): enumeration failed; treating as not-found: {}", n, io.toString());
            return new SessionNotFound(n);
        }
        if (streamRegistry.globalCount() >= props.streams().globalCap()) {
            return new CapExceeded("global");
        }
        if (streamRegistry.countFor(identity.fingerprintHex())
                >= props.streams().perClientCap()) {
            return new CapExceeded("per-client");
        }
        return new Allowed();
    }

    public StreamId openStream(int n, ClientIdentity identity, WebSocketSession session) throws IOException {
        ReentrantLock l = perN.get(n);
        try {
            if (!l.tryLock(1_000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                throw new IOException("Per-session mutex contention for N=" + n);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException(ie);
        }
        try {
            if (!sessionRegistry.exists(n)) {
                throw new java.util.NoSuchElementException("session " + n + " disappeared during open");
            }
            StreamId id = StreamId.fresh();
            ActiveStream as = new ActiveStream(id, n, identity.fingerprintHex(), session);
            if (!streamRegistry.register(as)) {
                throw new IOException("Stream registry rejected (cap race)");
            }
            audit.logEvent(
                    AuditAction.STREAM_OPEN,
                    "ok",
                    "n",
                    n,
                    "streamId",
                    id.value(),
                    "fingerprint",
                    identity.fingerprintHex(),
                    "cn",
                    identity.cn());
            return id;
        } finally {
            l.unlock();
        }
    }

    public void closeStream(StreamId id, int closeCode, String reason) {
        ActiveStream a = null;
        for (ActiveStream s : streamRegistry.snapshot()) {
            if (s.id.equals(id)) {
                a = s;
                break;
            }
        }
        streamRegistry.unregister(id);
        if (a != null) {
            audit.logEvent(
                    AuditAction.STREAM_CLOSE,
                    "ok",
                    "n",
                    a.n,
                    "streamId",
                    id.value(),
                    "fingerprint",
                    a.fingerprintHex,
                    "closeCode",
                    closeCode,
                    "reason",
                    reason == null ? "" : reason);
        }
    }

    public void setDraining(boolean d) {
        this.draining = d;
    }

    public boolean isDraining() {
        return draining;
    }

    public TmuxBridgeService tmux() {
        return tmux;
    }

    /**
     * Accessor exposed so {@link com.aisandbox.server.stream.handler.SessionStreamHandler}
     * can refresh {@code lastIo} on the underlying stream record without
     * needing a second constructor parameter. Wiring through the facade
     * keeps the handler's constructor signature stable for unit tests.
     */
    public StreamRegistryService streamRegistry() {
        return streamRegistry;
    }
}
