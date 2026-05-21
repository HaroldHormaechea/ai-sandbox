package com.aisandbox.server.enrollment.facade;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.clients.dto.AllowedClient;
import com.aisandbox.server.clients.facade.ClientAllowlistFacade;
import com.aisandbox.server.enrollment.dto.EnrollmentToken;
import com.aisandbox.server.enrollment.dto.MintedBundle;
import com.aisandbox.server.enrollment.service.EnrollmentCertMintService;
import com.aisandbox.server.enrollment.service.EnrollmentRateLimiterService;
import com.aisandbox.server.enrollment.service.EnrollmentTokenService;
import com.aisandbox.server.enrollment.service.EnrollmentTokenStore;
import java.io.IOException;
import java.security.cert.CertificateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Use-case facade for {@code POST /v1/enrollment} (UC04 AC33).
 *
 * <p>Orchestrates the four steps of an Android-client enrollment in one
 * place so the controller is mechanical translation only:
 *
 * <ol>
 *   <li>Per-IP rate limit ({@link EnrollmentRateLimiterService}).</li>
 *   <li>Single-use token redemption ({@link EnrollmentTokenService}).</li>
 *   <li>Fresh client cert + PKCS#12 mint ({@link EnrollmentCertMintService}).</li>
 *   <li>Cross-domain hand-off to {@link ClientAllowlistFacade#addClient(String, String)}
 *       so the new cert lands in the allowlist folder atomically and the
 *       {@code AllowlistWatcher} picks it up on the very next handshake.</li>
 * </ol>
 *
 * <p>This is a facade per {@code profile-java-server-architecture} rule 6
 * — it never reaches into a repository, it composes services + a peer
 * facade. No {@code @Transactional} (filesystem-only state); the
 * persistence-atomicity contract is the {@link EnrollmentTokenStore}'s
 * synchronized block + the {@code AllowlistDirectory} tmp+rename.
 *
 * <p>Loaded in every profile so the {@link EnrollmentController} can
 * autowire it; under {@code docs-only} the bean exists but is never
 * invoked (the docs-only profile doesn't bind the TLS listener).
 */
@Component
public class EnrollmentFacade {

    private static final Logger LOG = LoggerFactory.getLogger(EnrollmentFacade.class);

    private final EnrollmentRateLimiterService rateLimiter;
    private final EnrollmentTokenService tokenService;
    private final EnrollmentCertMintService certMint;
    private final ClientAllowlistFacade allowlistFacade;
    private final AuditLogger audit;

    public EnrollmentFacade(
            EnrollmentRateLimiterService rateLimiter,
            EnrollmentTokenService tokenService,
            EnrollmentCertMintService certMint,
            ClientAllowlistFacade allowlistFacade,
            AuditLogger audit) {
        this.rateLimiter = rateLimiter;
        this.tokenService = tokenService;
        this.certMint = certMint;
        this.allowlistFacade = allowlistFacade;
        this.audit = audit;
    }

    /**
     * Redeem the supplied {@code token} on behalf of the request coming
     * from {@code sourceIp}. Returns the minted bundle on success;
     * otherwise throws one of the four typed exceptions, which the
     * {@code ProblemDetailsAdvice} maps to the correct
     * {@link com.aisandbox.server.api.error.ErrorCode}.
     *
     * @throws RateLimitedException     429 — IP burned through the cap.
     * @throws TokenInvalidException    401 — token unknown.
     * @throws TokenExpiredException    401 — token past expiry.
     * @throws TokenRedeemedException   401 — token already consumed.
     */
    public MintedBundle redeem(String token, String sourceIp)
            throws RateLimitedException, TokenInvalidException, TokenExpiredException, TokenRedeemedException,
                    IOException, CertificateException {
        if (!rateLimiter.tryAcquire(sourceIp)) {
            audit.logEvent(AuditAction.CLIENT_ENROLL_REJECT, "rate-limited", "sourceIp", safe(sourceIp));
            throw new RateLimitedException(sourceIp);
        }

        // UC11 § AC7 — split the original redeem call into verify (no
        // side effects) + markRedeemed (delete the on-disk file +
        // tombstone). The mark step happens only AFTER the cert has
        // been successfully written to the allowlist, so a failed
        // cert write leaves the token alive for a retry.
        EnrollmentTokenStore.RedemptionOutcome outcome = tokenService.verify(token);
        switch (outcome) {
            case EnrollmentTokenStore.RedemptionOutcome.Success s -> {
                /* fall through below */
            }
            case EnrollmentTokenStore.RedemptionOutcome.Unknown u -> {
                audit.logEvent(AuditAction.CLIENT_ENROLL_REJECT, "token-invalid", "sourceIp", safe(sourceIp));
                throw new TokenInvalidException();
            }
            case EnrollmentTokenStore.RedemptionOutcome.Expired e -> {
                audit.logEvent(
                        AuditAction.CLIENT_ENROLL_REJECT,
                        "token-expired",
                        "sourceIp",
                        safe(sourceIp),
                        "name",
                        safe(e.token() == null ? null : e.token().name()));
                throw new TokenExpiredException();
            }
            case EnrollmentTokenStore.RedemptionOutcome.AlreadyRedeemed r -> {
                audit.logEvent(AuditAction.CLIENT_ENROLL_REJECT, "token-redeemed", "sourceIp", safe(sourceIp));
                throw new TokenRedeemedException();
            }
        }
        EnrollmentToken consumed = ((EnrollmentTokenStore.RedemptionOutcome.Success) outcome).token();

        MintedBundle bundle = certMint.mint(consumed.name());

        // Cross-domain facade-to-facade hand-off — never reach into the
        // allowlist service directly. If this throws (IOException /
        // CertificateException), the token has NOT been marked redeemed
        // yet, so the operator can retry with the same QR.
        AllowedClient added = allowlistFacade.addClient(consumed.name(), bundle.certPem());

        // Cert is on disk. Now and only now mark the token redeemed.
        // If the file is already gone (race loser, hand-edit), the
        // tombstone still gets set so subsequent verify() calls return
        // AlreadyRedeemed — warn-log but don't fail the request, since
        // the operator's identity has been successfully provisioned.
        if (!tokenService.markRedeemed(token)) {
            LOG.warn(
                    "Enrollment token already consumed on disk when marking redeemed (race loser?); "
                            + "cert was successfully written for client={}",
                    added.name());
        }

        audit.logEvent(
                AuditAction.CLIENT_ENROLL,
                "ok",
                "name",
                added.name(),
                "fingerprint",
                added.fingerprintHex(),
                "cn",
                added.cn(),
                "sourceIp",
                safe(sourceIp));
        LOG.info("Enrolled client name={} fp={} fromIp={}", added.name(), added.fingerprintHex(), safe(sourceIp));
        return bundle;
    }

    private static String safe(String s) {
        return s == null ? "-" : s;
    }

    // ── Typed failure modes (mapped to ErrorCode in ProblemDetailsAdvice) ─

    /** 429 — per-IP enrollment rate limit tripped (AC34). */
    public static final class RateLimitedException extends RuntimeException {
        private final String sourceIp;

        public RateLimitedException(String sourceIp) {
            super("Enrollment rate limit tripped for " + (sourceIp == null ? "<unknown>" : sourceIp));
            this.sourceIp = sourceIp;
        }

        public String sourceIp() {
            return sourceIp;
        }
    }

    /** 401 — token is unknown (never issued, or expired-and-purged). */
    public static final class TokenInvalidException extends RuntimeException {
        public TokenInvalidException() {
            super("Enrollment token unknown.");
        }
    }

    /** 401 — token was issued but is past its expiry. */
    public static final class TokenExpiredException extends RuntimeException {
        public TokenExpiredException() {
            super("Enrollment token expired.");
        }
    }

    /** 401 — token was already consumed in a prior request. */
    public static final class TokenRedeemedException extends RuntimeException {
        public TokenRedeemedException() {
            super("Enrollment token already redeemed.");
        }
    }
}
