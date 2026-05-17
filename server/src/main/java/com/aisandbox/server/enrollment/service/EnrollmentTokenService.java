package com.aisandbox.server.enrollment.service;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.enrollment.dto.EnrollmentToken;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Server-side orchestration around {@link EnrollmentTokenStore}.
 *
 * <ul>
 *   <li>{@link #issue(String, Duration)} — generate a fresh ≥256-bit token
 *       and persist it. Used by {@code aisandboxctl client invite} when
 *       it shares a JVM with the server (currently never — the CLI runs
 *       its own JVM and instantiates {@link EnrollmentTokenStore}
 *       directly — but the method is exposed here for test convenience
 *       and future "issue via REST" follow-ups).</li>
 *   <li>{@link #redeem(String)} — single-use consume. Called by the
 *       facade; never by HTTP layer.</li>
 *   <li>{@link #purgeExpired()} — runs every {@code purgeIntervalMinutes}
 *       to drop stale token files. Keeps the on-disk footprint bounded
 *       even if the operator forgets that an invite was never redeemed.</li>
 * </ul>
 *
 * <p>Loaded in every profile so {@link EnrollmentFacade} can autowire
 * it; the @PostConstruct {@code ensureDir} swallows IOExceptions, so a
 * docs-only boot that points at an unwritable directory just logs a
 * warning and proceeds (the bean is never invoked at runtime under
 * docs-only).
 */
@Service
public class EnrollmentTokenService {

    private static final Logger LOG = LoggerFactory.getLogger(EnrollmentTokenService.class);

    /**
     * Token entropy: 32 bytes = 256 bits, hex-encoded → 64 chars. Matches
     * the proposal's "≥256-bit hex token via SecureRandom" requirement
     * (UC04 AC32 and proposal § B3).
     */
    static final int TOKEN_ENTROPY_BYTES = 32;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final EnrollmentTokenStore store;
    private final SecureRandom random;
    private final Clock clock;
    private final Duration defaultTtl;

    @Autowired
    public EnrollmentTokenService(EnrollmentTokenStore store, ServerProperties props) {
        this(store, props, new SecureRandom(), Clock.systemUTC());
    }

    /** Visible-for-testing constructor. */
    EnrollmentTokenService(EnrollmentTokenStore store, ServerProperties props, SecureRandom random, Clock clock) {
        this.store = store;
        this.random = random;
        this.clock = clock;
        this.defaultTtl = Duration.ofMinutes(props.enrollment().defaultTtlMinutes());
    }

    @PostConstruct
    void ensureDir() {
        try {
            store.ensureDir();
        } catch (IOException io) {
            // The dir is operator-owned (0700, owned by ai-sandbox-server)
            // and may already exist. Log and continue — the first issue
            // call will retry.
            LOG.warn("Cannot ensure enrollment dir on startup ({}): {}", store.dir(), io.toString());
        }
    }

    /**
     * Generate and persist a fresh single-use token. The caller is
     * responsible for displaying the QR / handing the token to the
     * operator's invite flow.
     */
    public EnrollmentToken issue(String name, Duration ttl) throws IOException {
        Duration effective = ttl == null ? defaultTtl : ttl;
        String token = generateHexToken();
        Instant exp = clock.instant().plus(effective);
        EnrollmentToken t = new EnrollmentToken(token, name, exp);
        store.save(t);
        return t;
    }

    /**
     * Single-use consume. Result discriminates the four UC04 outcomes —
     * see {@link EnrollmentTokenStore.RedemptionOutcome}.
     */
    public EnrollmentTokenStore.RedemptionOutcome redeem(String token) {
        return store.redeem(token, clock);
    }

    /**
     * Drop expired token files. Fires every minute by default — small
     * jobs, very cheap, no incentive to be lazy here.
     */
    @Scheduled(fixedDelayString = "${ai-sandbox.server.enrollment.purge-interval-ms:60000}")
    public void purgeExpired() {
        int removed = store.purgeExpired(clock);
        if (removed > 0) {
            LOG.info("Purged {} expired enrollment token(s) from {}", removed, store.dir());
        }
    }

    private String generateHexToken() {
        byte[] buf = new byte[TOKEN_ENTROPY_BYTES];
        random.nextBytes(buf);
        char[] out = new char[buf.length * 2];
        for (int i = 0; i < buf.length; i++) {
            int v = buf[i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(out);
    }
}
