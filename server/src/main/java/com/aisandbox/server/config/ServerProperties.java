package com.aisandbox.server.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * Externalised configuration for the management server, mapped from
 * {@code /etc/ai-sandbox-server/config.yaml} (or a custom location passed
 * via {@code --spring.config.import=...}). Every field has a sensible
 * default so {@code aisandboxctl pki init} can drop a fully-functional
 * sample.
 *
 * <p>The shape mirrors the YAML schema documented in
 * {@code server/sample-config.yaml}. Changes here MUST be reflected there.
 *
 * <p>Validation runs at context start; a malformed config produces a
 * fail-fast error from {@link PropertiesValidationStartupCheck} (which
 * additionally enforces filesystem invariants).
 */
@Validated
@ConfigurationProperties(prefix = "ai-sandbox.server")
public record ServerProperties(
        @NotNull Tls tls,
        @NotNull Pki pki,
        @NotNull Clients clients,
        @NotNull Hostscripts hostscripts,
        @NotNull Limits limits,
        @NotNull Audit audit,
        @NotNull Shutdown shutdown,
        @NotNull Streams streams,
        @NotNull Enrollment enrollment,
        @NotNull Sessions sessions,
        @NotNull Secrets secrets) {

    /**
     * Canonical constructor — explicitly tagged
     * {@link ConstructorBinding} so Spring picks it (not the
     * back-compat ctors below) when binding from YAML. Without this,
     * Spring sees multiple ctors on the record and falls back to the
     * no-arg ctor lookup, which records don't have.
     */
    @ConstructorBinding
    public ServerProperties {}

    /**
     * Backwards-compatible 9-arg constructor for QA-owned test fixtures
     * built before UC05 added the {@link Sessions} and {@link Secrets}
     * fields. Production binding always supplies them from
     * {@code application.yaml} / {@code /etc/ai-sandbox-server/config.yaml}
     * so this constructor is never reached by Spring; only QA test code
     * uses it. New tests should call the canonical 11-arg constructor.
     */
    public ServerProperties(
            Tls tls,
            Pki pki,
            Clients clients,
            Hostscripts hostscripts,
            Limits limits,
            Audit audit,
            Shutdown shutdown,
            Streams streams,
            Enrollment enrollment) {
        this(
                tls,
                pki,
                clients,
                hostscripts,
                limits,
                audit,
                shutdown,
                streams,
                enrollment,
                new Sessions(Path.of("/var/lib/ai-sandbox-server/sessions")),
                new Secrets(Path.of("/etc/ai-sandbox-server/secrets")));
    }

    /**
     * Backwards-compatible 8-arg constructor for QA-owned test fixtures
     * built before UC04 added the {@link Enrollment} field. Production
     * binding always supplies {@link Enrollment} from
     * {@code application.yaml} / {@code /etc/ai-sandbox-server/config.yaml}
     * so this constructor is never reached by Spring; only QA test code
     * uses it. New tests should call the canonical 11-arg constructor.
     */
    public ServerProperties(
            Tls tls,
            Pki pki,
            Clients clients,
            Hostscripts hostscripts,
            Limits limits,
            Audit audit,
            Shutdown shutdown,
            Streams streams) {
        this(
                tls,
                pki,
                clients,
                hostscripts,
                limits,
                audit,
                shutdown,
                streams,
                new Enrollment(Path.of("/var/lib/ai-sandbox-server/enrollment"), 10, 1, 60));
    }

    public record Tls(@Min(1) int port, @NotBlank String bindAddress) {}

    public record Pki(@NotNull Path dir) {}

    public record Clients(@NotNull Path dir) {}

    public record Hostscripts(@NotNull Path repoRoot) {}

    public record Limits(
            @Min(1) int perIpNewConnPerWindow,
            @Min(1) int perIpWindowSeconds,
            @Min(1) int perIpConcurrent,
            @Min(1) int spawnTimeoutSeconds,
            @Min(1) int maxRequestBytes) {}

    public record Audit(@NotNull Path file, @Min(1) int retentionDays) {}

    public record Shutdown(@Min(1) int restGraceSeconds, @Min(1) int totalGraceSeconds) {}

    public record Streams(
            @Min(1) int idleTimeoutSeconds,
            @Min(1) int perClientCap,
            @Min(1) int globalCap,
            @Min(1) int maxBinaryFrameBytes,
            @Min(1) int maxTextFrameBytes,
            @Min(1) int outputRingBytes,
            @Min(1) int keepalivePingSeconds,
            @Min(1) int keepalivePongTimeoutSeconds) {

        /**
         * UC-37 — bounded backfill window (transcript lines) replayed when a
         * structured-conversation channel opens or reconnects (AC6/AC22), so a
         * long-running session's large transcript never floods the client. The
         * conversation channel reuses the rest of the {@code streams} caps
         * (per-client / global / text-frame size); the backfill bound is
         * intentionally a defaulted accessor rather than a new YAML-bound,
         * {@code @Min}-validated component so existing {@code config.yaml},
         * {@code sample-config.yaml}, and QA test fixtures bind unchanged. Promote
         * to a bound field if per-deployment tuning is ever needed.
         */
        public int conversationBackfillLines() {
            return CONVERSATION_BACKFILL_LINES_DEFAULT;
        }

        /**
         * UC-41 — byte cap on the on-demand tool-detail payload (full input + result)
         * returned by a {@code fetch-detail} frame (AC6), bounding a pathological
         * multi-MB tool result so the dialog can't OOM the device. Like
         * {@link #conversationBackfillLines()} this is a defaulted accessor rather than
         * a new YAML-bound, {@code @Min}-validated component, so existing
         * {@code config.yaml} / {@code sample-config.yaml} / QA fixtures bind unchanged.
         * Matches {@code ConversationEventMapper.CONVERSATION_DETAIL_MAX_BYTES}.
         */
        public int conversationDetailMaxBytes() {
            return CONVERSATION_DETAIL_MAX_BYTES_DEFAULT;
        }
    }

    /** UC-37 default backfill window — see {@link Streams#conversationBackfillLines()}. */
    public static final int CONVERSATION_BACKFILL_LINES_DEFAULT = 200;

    /** UC-41 default tool-detail byte cap (48&nbsp;KB) — see {@link Streams#conversationDetailMaxBytes()}. */
    public static final int CONVERSATION_DETAIL_MAX_BYTES_DEFAULT = 49152;

    /**
     * UC04 enrollment-endpoint configuration. Tokens issued by
     * {@code aisandboxctl client invite <name>} land as small JSON
     * files under {@link #dir()}; {@code POST /v1/enrollment} reads
     * them, deletes them atomically on redemption (single-use), and
     * mints a fresh client cert. The endpoint is the only mTLS-exempt
     * path on the server — see {@code docs/THREAT_MODEL.md} §
     * "Enrollment trust boundary" for the trust-surface analysis.
     */
    public record Enrollment(
            @NotNull Path dir,
            @Min(1) int defaultTtlMinutes,
            @Min(1) int rateLimitPerWindow,
            @Min(1) int rateLimitWindowSeconds) {}

    /**
     * UC05 § AC11,AC25,AC26 — per-session host-state root. Compose is
     * invoked with {@code --project-directory <hostStateRoot>} so the
     * counter file, lockdir, and per-session {@code workspace-N/} +
     * {@code claude-config-N/} directories land here instead of inside
     * the read-only install dir at {@code /opt/ai-sandbox-server/host/}.
     * Default in production: {@code /var/lib/ai-sandbox-server/sessions}.
     */
    public record Sessions(@NotNull Path hostStateRoot) {}

    /**
     * UC05 § AC14,AC27 — operator-managed secrets directory bind-mounted
     * read-only into every sandbox container at {@code /etc/secrets}.
     * Holds SSH keys and an optional {@code gh-token} populated by the
     * operator after {@code aisandboxctl pki init}. Default in
     * production: {@code /etc/ai-sandbox-server/secrets}.
     */
    public record Secrets(@NotNull Path dir) {}
}
