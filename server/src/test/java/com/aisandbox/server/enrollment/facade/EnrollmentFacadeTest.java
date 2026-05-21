package com.aisandbox.server.enrollment.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.clients.dto.AllowedClient;
import com.aisandbox.server.clients.facade.ClientAllowlistFacade;
import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.enrollment.dto.EnrollmentToken;
import com.aisandbox.server.enrollment.dto.MintedBundle;
import com.aisandbox.server.enrollment.service.EnrollmentCertMintService;
import com.aisandbox.server.enrollment.service.EnrollmentRateLimiterService;
import com.aisandbox.server.enrollment.service.EnrollmentTokenService;
import com.aisandbox.server.enrollment.service.EnrollmentTokenStore;
import com.aisandbox.server.enrollment.service.EnrollmentTokenStore.RedemptionOutcome;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * UC04 + UC11 — facade orchestration for {@code POST /v1/enrollment}.
 *
 * <p>The facade composes rate-limiter + token-service + cert-mint
 * service + cross-domain hand-off to {@link ClientAllowlistFacade}.
 * UC04 pinned the four exception flows (RateLimited / Unknown /
 * Expired / AlreadyRedeemed), the happy-path order-of-operations, and
 * the audit trail emitted along the way.
 *
 * <p>UC11 § AC7–AC9 restructures the facade so the token is NOT marked
 * redeemed until the cert has been successfully written to the
 * allowlist. The post-UC11 order is:
 *
 * <pre>
 *   rate → tokens.verify → certs.mint → allowFacade.addClient → tokens.markRedeemed → audit
 * </pre>
 *
 * <p>These tests pin:
 * <ul>
 *   <li>The new InOrder sequence on the happy path (AC9).</li>
 *   <li>Token survives a cert-write {@link IOException} — markRedeemed
 *       is NEVER called; audit outcome is {@code cert-write-failed};
 *       a follow-up redeem with the same token succeeds (AC8).</li>
 *   <li>{@link FileAlreadyExistsException} from the allowlist write
 *       surfaces as {@link EnrollmentFacade.CertAlreadyExistsException}
 *       with audit outcome {@code cert-already-exists}; markRedeemed
 *       is NEVER called (AC7 § S3.4).</li>
 *   <li>Single-shot guarantee: a successful redemption marks the token
 *       redeemed exactly once; a follow-up redeem with the same token
 *       throws {@link EnrollmentFacade.TokenRedeemedException} (AC9).</li>
 * </ul>
 */
class EnrollmentFacadeTest {

    private static final String SOURCE_IP = "198.51.100.10";
    private static final String FAKE_TOKEN =
            "fake-test-token-not-a-real-key" + "0".repeat(33); // 63+ chars; obvious placeholder.

    private static EnrollmentToken token(String name, Instant exp) {
        return new EnrollmentToken(FAKE_TOKEN, name, exp);
    }

    private static AllowedClient allowed(String name) {
        return new AllowedClient(name, name, "fa".repeat(32), BigInteger.ONE, Instant.EPOCH);
    }

    private static MintedBundle bundle(String name) {
        return new MintedBundle(
                name, "-----BEGIN CERTIFICATE-----\nXXX\n-----END CERTIFICATE-----", new byte[] {1, 2, 3});
    }

    @Test
    void success_runs_in_documented_order_then_emits_audit_ok() throws Exception {
        // UC11 § AC9 — post-UC11 sequence:
        //   rate → tokens.verify → certs.mint → allowFacade.addClient →
        //   tokens.markRedeemed → audit (CLIENT_ENROLL ok).
        EnrollmentRateLimiterService rate = mock(EnrollmentRateLimiterService.class);
        EnrollmentTokenService tokens = mock(EnrollmentTokenService.class);
        EnrollmentCertMintService certs = mock(EnrollmentCertMintService.class);
        ClientAllowlistFacade allowFacade = mock(ClientAllowlistFacade.class);
        AuditLogger audit = mock(AuditLogger.class);

        when(rate.tryAcquire(SOURCE_IP)).thenReturn(true);
        EnrollmentToken consumed = token("alice-phone", Instant.now().plusSeconds(600));
        when(tokens.verify(FAKE_TOKEN)).thenReturn(new RedemptionOutcome.Success(consumed));
        MintedBundle minted = bundle("alice-phone");
        when(certs.mint("alice-phone")).thenReturn(minted);
        AllowedClient added = allowed("alice-phone");
        when(allowFacade.addClient(eq("alice-phone"), any(String.class))).thenReturn(added);
        when(tokens.markRedeemed(FAKE_TOKEN)).thenReturn(true);

        EnrollmentFacade facade = new EnrollmentFacade(rate, tokens, certs, allowFacade, audit);
        MintedBundle out = facade.redeem(FAKE_TOKEN, SOURCE_IP);
        assertThat(out).isSameAs(minted);

        // Order: rate → tokens.verify → certs → allowFacade → tokens.markRedeemed.
        // The cross-domain hand-off must hit the SIBLING FACADE (profile-
        // java-server-architecture rule 6), not the allowlist service
        // directly. markRedeemed MUST come AFTER addClient succeeds
        // (UC11 § AC7 — transactional rollback).
        InOrder order = Mockito.inOrder(rate, tokens, certs, allowFacade);
        order.verify(rate).tryAcquire(SOURCE_IP);
        order.verify(tokens).verify(FAKE_TOKEN);
        order.verify(certs).mint("alice-phone");
        order.verify(allowFacade).addClient(eq("alice-phone"), eq(minted.certPem()));
        order.verify(tokens).markRedeemed(FAKE_TOKEN);

        // Exactly one markRedeemed call on the happy path (AC9 single-shot).
        verify(tokens, times(1)).markRedeemed(FAKE_TOKEN);

        // Audit: exactly one CLIENT_ENROLL "ok" with the freshly-minted
        // fingerprint + name + source IP.
        ArgumentCaptor<AuditAction> action = ArgumentCaptor.forClass(AuditAction.class);
        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        verify(audit).logEvent(action.capture(), outcome.capture(), any(Object[].class));
        assertThat(action.getValue()).isEqualTo(AuditAction.CLIENT_ENROLL);
        assertThat(outcome.getValue()).isEqualTo("ok");
    }

    @Test
    void rate_limited_throws_RateLimitedException_and_short_circuits() throws Exception {
        EnrollmentRateLimiterService rate = mock(EnrollmentRateLimiterService.class);
        EnrollmentTokenService tokens = mock(EnrollmentTokenService.class);
        EnrollmentCertMintService certs = mock(EnrollmentCertMintService.class);
        ClientAllowlistFacade allowFacade = mock(ClientAllowlistFacade.class);
        AuditLogger audit = mock(AuditLogger.class);

        when(rate.tryAcquire(SOURCE_IP)).thenReturn(false);

        EnrollmentFacade facade = new EnrollmentFacade(rate, tokens, certs, allowFacade, audit);
        assertThatThrownBy(() -> facade.redeem(FAKE_TOKEN, SOURCE_IP))
                .isInstanceOf(EnrollmentFacade.RateLimitedException.class)
                .satisfies(t -> assertThat(((EnrollmentFacade.RateLimitedException) t).sourceIp())
                        .isEqualTo(SOURCE_IP));

        // Short-circuit — no token verify, no cert mint, no allowlist hand-off,
        // no markRedeemed.
        verify(tokens, never()).verify(any());
        verify(tokens, never()).markRedeemed(any());
        verify(certs, never()).mint(any());
        verify(allowFacade, never()).addClient(any(), any());

        // Audit emits a reject line.
        ArgumentCaptor<AuditAction> action = ArgumentCaptor.forClass(AuditAction.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit).logEvent(action.capture(), reason.capture(), any(Object[].class));
        assertThat(action.getValue()).isEqualTo(AuditAction.CLIENT_ENROLL_REJECT);
        assertThat(reason.getValue()).isEqualTo("rate-limited");
    }

    @Test
    void unknown_token_throws_TokenInvalidException_and_emits_reject_audit() throws Exception {
        EnrollmentRateLimiterService rate = mock(EnrollmentRateLimiterService.class);
        EnrollmentTokenService tokens = mock(EnrollmentTokenService.class);
        EnrollmentCertMintService certs = mock(EnrollmentCertMintService.class);
        ClientAllowlistFacade allowFacade = mock(ClientAllowlistFacade.class);
        AuditLogger audit = mock(AuditLogger.class);

        when(rate.tryAcquire(SOURCE_IP)).thenReturn(true);
        when(tokens.verify(FAKE_TOKEN)).thenReturn(new RedemptionOutcome.Unknown());

        EnrollmentFacade facade = new EnrollmentFacade(rate, tokens, certs, allowFacade, audit);
        assertThatThrownBy(() -> facade.redeem(FAKE_TOKEN, SOURCE_IP))
                .isInstanceOf(EnrollmentFacade.TokenInvalidException.class);

        verify(certs, never()).mint(any());
        verify(allowFacade, never()).addClient(any(), any());
        verify(tokens, never()).markRedeemed(any());
        ArgumentCaptor<AuditAction> action = ArgumentCaptor.forClass(AuditAction.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit).logEvent(action.capture(), reason.capture(), any(Object[].class));
        assertThat(action.getValue()).isEqualTo(AuditAction.CLIENT_ENROLL_REJECT);
        assertThat(reason.getValue()).isEqualTo("token-invalid");
    }

    @Test
    void expired_token_throws_TokenExpiredException_and_emits_reject_audit() throws Exception {
        EnrollmentRateLimiterService rate = mock(EnrollmentRateLimiterService.class);
        EnrollmentTokenService tokens = mock(EnrollmentTokenService.class);
        EnrollmentCertMintService certs = mock(EnrollmentCertMintService.class);
        ClientAllowlistFacade allowFacade = mock(ClientAllowlistFacade.class);
        AuditLogger audit = mock(AuditLogger.class);

        when(rate.tryAcquire(SOURCE_IP)).thenReturn(true);
        EnrollmentToken consumed = token("alice-phone", Instant.parse("2026-05-17T09:00:00Z"));
        when(tokens.verify(FAKE_TOKEN)).thenReturn(new RedemptionOutcome.Expired(consumed));

        EnrollmentFacade facade = new EnrollmentFacade(rate, tokens, certs, allowFacade, audit);
        assertThatThrownBy(() -> facade.redeem(FAKE_TOKEN, SOURCE_IP))
                .isInstanceOf(EnrollmentFacade.TokenExpiredException.class);

        verify(certs, never()).mint(any());
        verify(allowFacade, never()).addClient(any(), any());
        verify(tokens, never()).markRedeemed(any());
        ArgumentCaptor<AuditAction> action = ArgumentCaptor.forClass(AuditAction.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit).logEvent(action.capture(), reason.capture(), any(Object[].class));
        assertThat(action.getValue()).isEqualTo(AuditAction.CLIENT_ENROLL_REJECT);
        assertThat(reason.getValue()).isEqualTo("token-expired");
    }

    @Test
    void already_redeemed_throws_TokenRedeemedException_and_emits_reject_audit() throws Exception {
        EnrollmentRateLimiterService rate = mock(EnrollmentRateLimiterService.class);
        EnrollmentTokenService tokens = mock(EnrollmentTokenService.class);
        EnrollmentCertMintService certs = mock(EnrollmentCertMintService.class);
        ClientAllowlistFacade allowFacade = mock(ClientAllowlistFacade.class);
        AuditLogger audit = mock(AuditLogger.class);

        when(rate.tryAcquire(SOURCE_IP)).thenReturn(true);
        when(tokens.verify(FAKE_TOKEN)).thenReturn(new RedemptionOutcome.AlreadyRedeemed());

        EnrollmentFacade facade = new EnrollmentFacade(rate, tokens, certs, allowFacade, audit);
        assertThatThrownBy(() -> facade.redeem(FAKE_TOKEN, SOURCE_IP))
                .isInstanceOf(EnrollmentFacade.TokenRedeemedException.class);

        verify(certs, never()).mint(any());
        verify(allowFacade, never()).addClient(any(), any());
        verify(tokens, never()).markRedeemed(any());
        ArgumentCaptor<AuditAction> action = ArgumentCaptor.forClass(AuditAction.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit).logEvent(action.capture(), reason.capture(), any(Object[].class));
        assertThat(action.getValue()).isEqualTo(AuditAction.CLIENT_ENROLL_REJECT);
        assertThat(reason.getValue()).isEqualTo("token-redeemed");
    }

    @Test
    void cert_write_failure_leaves_token_unredeemed_and_emits_cert_write_failed_audit() throws Exception {
        // UC11 § AC8 (mock-based) — when the cross-domain hand-off to
        // ClientAllowlistFacade.addClient throws an IOException, the
        // facade rethrows it AND must NOT call tokens.markRedeemed.
        // The audit line carries the cert-write-failed outcome so
        // dashboards group it under the "cert-mint-or-write failed"
        // bucket alongside the in-process mint exception.
        EnrollmentRateLimiterService rate = mock(EnrollmentRateLimiterService.class);
        EnrollmentTokenService tokens = mock(EnrollmentTokenService.class);
        EnrollmentCertMintService certs = mock(EnrollmentCertMintService.class);
        ClientAllowlistFacade allowFacade = mock(ClientAllowlistFacade.class);
        AuditLogger audit = mock(AuditLogger.class);

        when(rate.tryAcquire(SOURCE_IP)).thenReturn(true);
        EnrollmentToken consumed = token("alice-phone", Instant.now().plusSeconds(600));
        when(tokens.verify(FAKE_TOKEN)).thenReturn(new RedemptionOutcome.Success(consumed));
        when(certs.mint("alice-phone")).thenReturn(bundle("alice-phone"));
        when(allowFacade.addClient(eq("alice-phone"), any(String.class)))
                .thenThrow(new IOException("simulated FS failure"));

        EnrollmentFacade facade = new EnrollmentFacade(rate, tokens, certs, allowFacade, audit);
        assertThatThrownBy(() -> facade.redeem(FAKE_TOKEN, SOURCE_IP))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("simulated FS failure");

        // The pivot of UC11: markRedeemed MUST NOT be called when the
        // cert write failed. The operator must be able to retry the
        // same QR.
        verify(tokens, never()).markRedeemed(any());

        // Audit outcome is cert-write-failed.
        ArgumentCaptor<AuditAction> action = ArgumentCaptor.forClass(AuditAction.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit).logEvent(action.capture(), reason.capture(), any(Object[].class));
        assertThat(action.getValue()).isEqualTo(AuditAction.CLIENT_ENROLL_REJECT);
        assertThat(reason.getValue()).isEqualTo("cert-write-failed");
    }

    @Test
    void file_already_exists_surfaces_as_CertAlreadyExistsException_with_cert_already_exists_audit() throws Exception {
        // UC11 § AC7 § S3.4 — FileAlreadyExistsException from
        // AllowlistDirectory.write (the ATOMIC_MOVE collision) is
        // pattern-matched into a typed CertAlreadyExistsException so
        // the WebExceptionHandler can map it to 409. markRedeemed must
        // NOT be called; the operator can revoke the existing cert
        // and retry the same QR.
        EnrollmentRateLimiterService rate = mock(EnrollmentRateLimiterService.class);
        EnrollmentTokenService tokens = mock(EnrollmentTokenService.class);
        EnrollmentCertMintService certs = mock(EnrollmentCertMintService.class);
        ClientAllowlistFacade allowFacade = mock(ClientAllowlistFacade.class);
        AuditLogger audit = mock(AuditLogger.class);

        when(rate.tryAcquire(SOURCE_IP)).thenReturn(true);
        EnrollmentToken consumed = token("alice-phone", Instant.now().plusSeconds(600));
        when(tokens.verify(FAKE_TOKEN)).thenReturn(new RedemptionOutcome.Success(consumed));
        when(certs.mint("alice-phone")).thenReturn(bundle("alice-phone"));
        when(allowFacade.addClient(eq("alice-phone"), any(String.class)))
                .thenThrow(new FileAlreadyExistsException("/etc/ai-sandbox-server/clients/alice-phone.crt"));

        EnrollmentFacade facade = new EnrollmentFacade(rate, tokens, certs, allowFacade, audit);
        assertThatThrownBy(() -> facade.redeem(FAKE_TOKEN, SOURCE_IP))
                .isInstanceOf(EnrollmentFacade.CertAlreadyExistsException.class)
                .satisfies(t -> assertThat(((EnrollmentFacade.CertAlreadyExistsException) t).name())
                        .isEqualTo("alice-phone"))
                .hasCauseInstanceOf(FileAlreadyExistsException.class);

        verify(tokens, never()).markRedeemed(any());

        ArgumentCaptor<AuditAction> action = ArgumentCaptor.forClass(AuditAction.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit).logEvent(action.capture(), reason.capture(), any(Object[].class));
        assertThat(action.getValue()).isEqualTo(AuditAction.CLIENT_ENROLL_REJECT);
        assertThat(reason.getValue()).isEqualTo("cert-already-exists");
    }

    @Test
    void mint_failure_leaves_token_unredeemed_and_emits_cert_write_failed_audit() throws Exception {
        // Sibling to the cert-write-failure case: failure at the in-
        // process cert mint step also leaves the token alive. Same
        // audit bucket (cert-write-failed) so dashboards can group
        // both failure points under one mint-or-write umbrella.
        EnrollmentRateLimiterService rate = mock(EnrollmentRateLimiterService.class);
        EnrollmentTokenService tokens = mock(EnrollmentTokenService.class);
        EnrollmentCertMintService certs = mock(EnrollmentCertMintService.class);
        ClientAllowlistFacade allowFacade = mock(ClientAllowlistFacade.class);
        AuditLogger audit = mock(AuditLogger.class);

        when(rate.tryAcquire(SOURCE_IP)).thenReturn(true);
        EnrollmentToken consumed = token("alice-phone", Instant.now().plusSeconds(600));
        when(tokens.verify(FAKE_TOKEN)).thenReturn(new RedemptionOutcome.Success(consumed));
        when(certs.mint("alice-phone")).thenThrow(new IOException("simulated mint failure"));

        EnrollmentFacade facade = new EnrollmentFacade(rate, tokens, certs, allowFacade, audit);
        assertThatThrownBy(() -> facade.redeem(FAKE_TOKEN, SOURCE_IP))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("simulated mint failure");

        verify(allowFacade, never()).addClient(any(), any());
        verify(tokens, never()).markRedeemed(any());

        ArgumentCaptor<AuditAction> action = ArgumentCaptor.forClass(AuditAction.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit).logEvent(action.capture(), reason.capture(), any(Object[].class));
        assertThat(action.getValue()).isEqualTo(AuditAction.CLIENT_ENROLL_REJECT);
        assertThat(reason.getValue()).isEqualTo("cert-write-failed");
    }

    @Test
    void real_store_token_survives_cert_write_failure_and_succeeds_on_retry(@TempDir Path tmp) throws Exception {
        // UC11 § AC8 (real-store-based) — the pre-fix / post-fix flip.
        // Using a real EnrollmentTokenStore + EnrollmentTokenService
        // over a @TempDir, with a mocked ClientAllowlistFacade that
        // throws IOException on the first call and succeeds on the
        // second, we prove that:
        //   (1) the first redeem rethrows the IOException;
        //   (2) the on-disk token file is still there afterwards;
        //   (3) a follow-up redeem of the SAME token succeeds.
        EnrollmentTokenStore store = new EnrollmentTokenStore(tmp);
        ServerProperties props = new ServerProperties(
                new ServerProperties.Tls(0, "127.0.0.1"),
                new ServerProperties.Pki(Path.of("/x")),
                new ServerProperties.Clients(Path.of("/y")),
                new ServerProperties.Hostscripts(Path.of("/z")),
                new ServerProperties.Limits(10, 10, 10, 5, 65536),
                new ServerProperties.Audit(Path.of("/a.log"), 7),
                new ServerProperties.Shutdown(1, 2),
                new ServerProperties.Streams(7200, 10, 100, 262144, 16384, 262144, 30, 15),
                new ServerProperties.Enrollment(tmp, 10, 1, 60));
        // EnrollmentTokenService's clock-injecting ctor is package-
        // private; use the public ctor (which builds its own SecureRandom
        // + Clock.systemUTC()). The test runs within a few seconds, so a
        // 10-minute TTL gives ample margin.
        EnrollmentTokenService tokens = new EnrollmentTokenService(store, props);

        // Pre-load a token via the real service so the on-disk shape
        // matches the production write path.
        EnrollmentToken issued = tokens.issue("alice-phone", Duration.ofMinutes(10));
        String fullToken = issued.token();

        EnrollmentRateLimiterService rate = mock(EnrollmentRateLimiterService.class);
        EnrollmentCertMintService certs = mock(EnrollmentCertMintService.class);
        ClientAllowlistFacade allowFacade = mock(ClientAllowlistFacade.class);
        AuditLogger audit = mock(AuditLogger.class);

        when(rate.tryAcquire(SOURCE_IP)).thenReturn(true);
        when(certs.mint("alice-phone")).thenReturn(bundle("alice-phone"));
        // First call throws; second call succeeds. Pre-UC11 the token
        // would be gone after the first call (so the second redeem
        // would hit AlreadyRedeemed and the test would fail). Post-
        // UC11 the file survives and the retry succeeds.
        AllowedClient addedOnRetry = allowed("alice-phone");
        when(allowFacade.addClient(eq("alice-phone"), any(String.class)))
                .thenThrow(new IOException("simulated FS failure"))
                .thenReturn(addedOnRetry);

        EnrollmentFacade facade = new EnrollmentFacade(rate, tokens, certs, allowFacade, audit);

        // (1) First redeem rethrows IOException.
        assertThatThrownBy(() -> facade.redeem(fullToken, SOURCE_IP))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("simulated FS failure");

        // (2) Token file still on disk — verify() still returns Success.
        assertThat(store.fileFor(fullToken))
                .as("UC11 § AC7 — token file must survive a failed cert write")
                .exists();
        assertThat(store.verify(fullToken, Clock.systemUTC())).isInstanceOf(RedemptionOutcome.Success.class);

        // (3) Follow-up redeem of the same token succeeds.
        MintedBundle out = facade.redeem(fullToken, SOURCE_IP);
        assertThat(out.name()).isEqualTo("alice-phone");

        // After the successful retry the token IS consumed.
        assertThat(store.fileFor(fullToken)).doesNotExist();
        assertThat(store.verify(fullToken, Clock.systemUTC())).isInstanceOf(RedemptionOutcome.AlreadyRedeemed.class);
    }

    @Test
    void happy_path_single_shot_followup_with_same_token_throws_TokenRedeemed(@TempDir Path tmp) throws Exception {
        // UC11 § AC9 — the regression guard against the rollback
        // accidentally allowing multi-use tokens: a successful
        // enrollment marks the token redeemed and a subsequent
        // redeem of the same token throws TokenRedeemedException.
        EnrollmentTokenStore store = new EnrollmentTokenStore(tmp);
        ServerProperties props = new ServerProperties(
                new ServerProperties.Tls(0, "127.0.0.1"),
                new ServerProperties.Pki(Path.of("/x")),
                new ServerProperties.Clients(Path.of("/y")),
                new ServerProperties.Hostscripts(Path.of("/z")),
                new ServerProperties.Limits(10, 10, 10, 5, 65536),
                new ServerProperties.Audit(Path.of("/a.log"), 7),
                new ServerProperties.Shutdown(1, 2),
                new ServerProperties.Streams(7200, 10, 100, 262144, 16384, 262144, 30, 15),
                new ServerProperties.Enrollment(tmp, 10, 1, 60));
        EnrollmentTokenService tokens = new EnrollmentTokenService(store, props);
        EnrollmentToken issued = tokens.issue("alice-phone", Duration.ofMinutes(10));
        String fullToken = issued.token();

        EnrollmentRateLimiterService rate = mock(EnrollmentRateLimiterService.class);
        EnrollmentCertMintService certs = mock(EnrollmentCertMintService.class);
        ClientAllowlistFacade allowFacade = mock(ClientAllowlistFacade.class);
        AuditLogger audit = mock(AuditLogger.class);

        when(rate.tryAcquire(SOURCE_IP)).thenReturn(true);
        when(certs.mint("alice-phone")).thenReturn(bundle("alice-phone"));
        when(allowFacade.addClient(eq("alice-phone"), any(String.class))).thenReturn(allowed("alice-phone"));

        EnrollmentFacade facade = new EnrollmentFacade(rate, tokens, certs, allowFacade, audit);
        facade.redeem(fullToken, SOURCE_IP);

        // Follow-up redeem must hit AlreadyRedeemed → TokenRedeemedException.
        assertThatThrownBy(() -> facade.redeem(fullToken, SOURCE_IP))
                .isInstanceOf(EnrollmentFacade.TokenRedeemedException.class);
    }

    @Test
    void cross_domain_handoff_goes_through_sibling_facade_not_allowlist_service() throws Exception {
        // profile-java-server-architecture rule 6 — cross-domain work
        // (enrollment domain → allowlist domain) routes via facade-to-
        // facade. The facade MUST NOT reach into AllowlistDirectory /
        // ClientAllowlistService directly. We pin this by asserting the
        // ONLY collaborator that touches the allowlist domain is the
        // ClientAllowlistFacade mock; if a future refactor injects an
        // AllowlistDirectory / ClientAllowlistService into this facade,
        // ArchitectureRulesTest catches it. This test catches the
        // runtime regression — the facade has exactly the four
        // collaborators below + audit.
        EnrollmentRateLimiterService rate = mock(EnrollmentRateLimiterService.class);
        EnrollmentTokenService tokens = mock(EnrollmentTokenService.class);
        EnrollmentCertMintService certs = mock(EnrollmentCertMintService.class);
        ClientAllowlistFacade allowFacade = mock(ClientAllowlistFacade.class);
        AuditLogger audit = mock(AuditLogger.class);

        when(rate.tryAcquire(SOURCE_IP)).thenReturn(true);
        EnrollmentToken consumed = token("bob-tablet", Instant.now().plusSeconds(600));
        when(tokens.verify(FAKE_TOKEN)).thenReturn(new RedemptionOutcome.Success(consumed));
        when(certs.mint("bob-tablet")).thenReturn(bundle("bob-tablet"));
        when(allowFacade.addClient(eq("bob-tablet"), any(String.class))).thenReturn(allowed("bob-tablet"));
        when(tokens.markRedeemed(FAKE_TOKEN)).thenReturn(true);

        EnrollmentFacade facade = new EnrollmentFacade(rate, tokens, certs, allowFacade, audit);
        facade.redeem(FAKE_TOKEN, SOURCE_IP);

        // Exactly one allowlist-domain call, and it's on the sibling facade.
        verify(allowFacade).addClient(eq("bob-tablet"), any(String.class));
    }
}
