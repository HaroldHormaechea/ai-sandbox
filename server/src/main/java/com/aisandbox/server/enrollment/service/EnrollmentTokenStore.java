package com.aisandbox.server.enrollment.service;

import com.aisandbox.server.enrollment.dto.EnrollmentToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On-disk store for {@link EnrollmentToken}s — the serialization point
 * for the {@code POST /v1/enrollment} single-use semantics (UC04 § Edge
 * cases, "Token-store concurrency").
 *
 * <h2>Concurrency contract</h2>
 *
 * Two simultaneous redemptions of the same token MUST NOT both succeed.
 * The store enforces this with a {@code synchronized} block guarding the
 * read-verify-delete critical section; within the JVM only one caller
 * can be inside {@link #redeem(String, Clock)} at a time. The
 * {@code Files.deleteIfExists} call is the atomic primitive on POSIX
 * (the same trick {@code AllowlistDirectory#deleteByName} uses). Cross-
 * process safety is provided by the same atomic primitive — at most one
 * deleter wins.
 *
 * <h2>Single-server constraint</h2>
 *
 * This MVP runs one server per host; the synchronized block + POSIX
 * atomic delete is sufficient. If we ever scale beyond one server, the
 * "recently redeemed" memory keeps a small in-process tombstone set so
 * the losing caller in a race can be distinguished from a never-
 * existing token.
 *
 * <h2>File layout</h2>
 *
 * <pre>
 *   &lt;enrollment.dir&gt;/&lt;first FILENAME_PREFIX_LEN chars of token&gt;.json
 * </pre>
 *
 * Each file is written via tmp + atomic rename, mode 0600.
 *
 * <p>This class is plain — not Spring-managed — so the {@code aisandboxctl
 * client invite} CLI can construct it directly without dragging in the
 * full Spring context. The server-side {@code @Bean} factory lives in
 * {@link com.aisandbox.server.config.PrimaryConfiguration}.
 */
public class EnrollmentTokenStore {

    private static final Logger LOG = LoggerFactory.getLogger(EnrollmentTokenStore.class);

    /**
     * Number of leading-hex chars of the token used as the on-disk filename
     * prefix. 16 hex chars = 64 bits — collisions are vanishingly unlikely
     * across the active-token window even with millions of issued tokens.
     */
    public static final int FILENAME_PREFIX_LEN = 16;

    private static final Set<PosixFilePermission> MODE_600 =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private static final Set<PosixFilePermission> MODE_700 = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    /**
     * Soft cap on the in-memory recently-redeemed tombstone set. At one
     * redemption per minute (the AC34 default rate limit) this covers
     * well over the AC32 10-minute TTL window.
     */
    private static final int RECENTLY_REDEEMED_CAP = 1024;

    private final Path dir;
    private final ObjectMapper mapper;
    private final Object lock = new Object();
    private final Map<String, Long> recentlyRedeemed = new LinkedHashMap<>(64, 0.75f, false);

    public EnrollmentTokenStore(Path dir) {
        this.dir = dir;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** Read-only view of the configured enrollment directory. */
    public Path dir() {
        return dir;
    }

    /**
     * Lazily ensure {@link #dir} exists, mode 0700. Idempotent. Run
     * once on startup by {@link EnrollmentTokenService} and once by the
     * CLI before the first save.
     */
    public void ensureDir() throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            try {
                Files.setPosixFilePermissions(dir, MODE_700);
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX FS (Windows) — the operator is on their own.
            }
        }
    }

    /**
     * Persist a new token. Overwrites any existing file at the same
     * prefix (operator-driven re-invite under an unlucky collision).
     */
    public void save(EnrollmentToken token) throws IOException {
        ensureDir();
        Path file = fileFor(token.token());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        byte[] body = mapper.writeValueAsBytes(new TokenJson(token.token(), token.name(), token.expiresAt()));
        Files.write(tmp, body);
        try {
            Files.setPosixFilePermissions(tmp, MODE_600);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX FS — caller accepts the looser default.
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException amns) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Attempt to consume the supplied {@code token}. Returns the parsed
     * token on success; the empty Optional encodes "invalid OR expired OR
     * redeemed" — the caller maps that to the correct
     * {@link com.aisandbox.server.api.error.ErrorCode}.
     *
     * <p>Discrimination rules (see {@link #wasRecentlyRedeemed(String)}):
     * <ul>
     *   <li>file exists, parses, in-window → {@code Success(token)}, delete.</li>
     *   <li>file exists, parses, past expiry → {@code Empty}, delete; caller
     *       reports {@code enrollment_token_expired}.</li>
     *   <li>file exists, parses, wrong full token (prefix collision) →
     *       {@code Empty}; caller reports {@code enrollment_token_invalid}.</li>
     *   <li>file missing, token in tombstone set → {@code Empty}; caller
     *       reports {@code enrollment_token_redeemed}.</li>
     *   <li>file missing, token NOT in tombstone → {@code Empty}; caller
     *       reports {@code enrollment_token_invalid}.</li>
     * </ul>
     */
    public RedemptionOutcome redeem(String token, Clock clock) {
        Path file = fileFor(token);
        synchronized (lock) {
            if (!Files.exists(file)) {
                return wasRecentlyRedeemed(token) ? RedemptionOutcome.ALREADY_REDEEMED : RedemptionOutcome.UNKNOWN;
            }
            EnrollmentToken parsed;
            try {
                TokenJson raw = mapper.readValue(file.toFile(), TokenJson.class);
                parsed = new EnrollmentToken(raw.token(), raw.name(), raw.exp());
            } catch (IOException io) {
                LOG.warn("Cannot parse enrollment token at {}: {}", file, io.toString());
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
                return RedemptionOutcome.UNKNOWN;
            }
            if (!parsed.token().equals(token)) {
                // Prefix collision — extremely unlikely. Treat as unknown
                // and leave the legitimate token's file alone (we matched
                // by prefix, not by full token).
                return RedemptionOutcome.UNKNOWN;
            }
            Instant now = clock.instant();
            if (parsed.isExpiredAt(now)) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
                return RedemptionOutcome.expired(parsed);
            }
            boolean deleted;
            try {
                deleted = Files.deleteIfExists(file);
            } catch (IOException io) {
                throw new UncheckedIOException(io);
            }
            if (!deleted) {
                // Lost the race to another process / hand-edit.
                rememberRedeemed(token, now);
                return RedemptionOutcome.ALREADY_REDEEMED;
            }
            rememberRedeemed(token, now);
            return RedemptionOutcome.success(parsed);
        }
    }

    /**
     * Delete every token file whose expiry is at or before {@code now}.
     * Returns the number of files removed.
     */
    public int purgeExpired(Clock clock) {
        Instant now = clock.instant();
        int removed = 0;
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        synchronized (lock) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.json")) {
                List<Path> toDelete = new ArrayList<>();
                for (Path p : entries) {
                    try {
                        TokenJson raw = mapper.readValue(p.toFile(), TokenJson.class);
                        if (raw.exp() == null || !raw.exp().isAfter(now)) {
                            toDelete.add(p);
                        }
                    } catch (IOException io) {
                        LOG.warn("Unparseable enrollment token at {}, dropping: {}", p, io.toString());
                        toDelete.add(p);
                    }
                }
                for (Path p : toDelete) {
                    try {
                        if (Files.deleteIfExists(p)) {
                            removed++;
                        }
                    } catch (IOException io) {
                        LOG.warn("Cannot delete expired enrollment token {}: {}", p, io.toString());
                    }
                }
            } catch (IOException io) {
                LOG.warn("Cannot scan enrollment dir {} for expired tokens: {}", dir, io.toString());
            }
        }
        return removed;
    }

    /** Compute the on-disk path for a token. */
    public Path fileFor(String token) {
        String prefix = token.length() <= FILENAME_PREFIX_LEN ? token : token.substring(0, FILENAME_PREFIX_LEN);
        return dir.resolve(prefix + ".json");
    }

    // ── Recently-redeemed tombstone (in-memory) ──────────────────────────

    private void rememberRedeemed(String token, Instant now) {
        recentlyRedeemed.put(token, now.toEpochMilli());
        if (recentlyRedeemed.size() > RECENTLY_REDEEMED_CAP) {
            // LinkedHashMap insertion order — the first key is the oldest.
            var it = recentlyRedeemed.entrySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    private boolean wasRecentlyRedeemed(String token) {
        return recentlyRedeemed.containsKey(token);
    }

    /**
     * On-disk JSON shape for one token. Jackson record-binding is fine
     * with field names that match exactly. {@code exp} is RFC 3339 via
     * the {@code JavaTimeModule}.
     */
    private record TokenJson(String token, String name, Instant exp) {}

    /**
     * Result of {@link #redeem(String, Clock)} — distinguishes the three
     * UC04 failure modes from a successful single-use consumption.
     */
    public sealed interface RedemptionOutcome
            permits RedemptionOutcome.Success,
                    RedemptionOutcome.Unknown,
                    RedemptionOutcome.Expired,
                    RedemptionOutcome.AlreadyRedeemed {

        EnrollmentToken token();

        record Success(EnrollmentToken token) implements RedemptionOutcome {}

        record Unknown() implements RedemptionOutcome {
            @Override
            public EnrollmentToken token() {
                return null;
            }
        }

        record Expired(EnrollmentToken token) implements RedemptionOutcome {}

        record AlreadyRedeemed() implements RedemptionOutcome {
            @Override
            public EnrollmentToken token() {
                return null;
            }
        }

        Unknown UNKNOWN = new Unknown();
        AlreadyRedeemed ALREADY_REDEEMED = new AlreadyRedeemed();

        static RedemptionOutcome success(EnrollmentToken t) {
            return new Success(t);
        }

        static RedemptionOutcome expired(EnrollmentToken t) {
            return new Expired(t);
        }
    }
}
