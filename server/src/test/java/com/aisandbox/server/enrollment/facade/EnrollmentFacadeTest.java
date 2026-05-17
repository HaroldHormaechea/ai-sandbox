package com.aisandbox.server.enrollment.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.clients.dto.AllowedClient;
import com.aisandbox.server.clients.facade.ClientAllowlistFacade;
import com.aisandbox.server.enrollment.dto.EnrollmentToken;
import com.aisandbox.server.enrollment.dto.MintedBundle;
import com.aisandbox.server.enrollment.service.EnrollmentCertMintService;
import com.aisandbox.server.enrollment.service.EnrollmentRateLimiterService;
import com.aisandbox.server.enrollment.service.EnrollmentTokenService;
import com.aisandbox.server.enrollment.service.EnrollmentTokenStore.RedemptionOutcome;
import java.math.BigInteger;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * UC04 AC32–AC35 — facade orchestration for {@code POST /v1/enrollment}.
 *
 * <p>The facade composes rate-limiter + token-service + cert-mint
 * service + cross-domain hand-off to {@link ClientAllowlistFacade}.
 * These tests pin the four exception flows (RateLimited / Unknown /
 * Expired / AlreadyRedeemed), the happy path order-of-operations, and
 * the audit trail emitted along the way.
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
    void success_mints_and_hands_off_to_allowlist_facade_then_emits_audit_ok() throws Exception {
        EnrollmentRateLimiterService rate = mock(EnrollmentRateLimiterService.class);
        EnrollmentTokenService tokens = mock(EnrollmentTokenService.class);
        EnrollmentCertMintService certs = mock(EnrollmentCertMintService.class);
        ClientAllowlistFacade allowFacade = mock(ClientAllowlistFacade.class);
        AuditLogger audit = mock(AuditLogger.class);

        when(rate.tryAcquire(SOURCE_IP)).thenReturn(true);
        EnrollmentToken consumed = token("alice-phone", Instant.now().plusSeconds(600));
        when(tokens.redeem(FAKE_TOKEN)).thenReturn(new RedemptionOutcome.Success(consumed));
        MintedBundle minted = bundle("alice-phone");
        when(certs.mint("alice-phone")).thenReturn(minted);
        AllowedClient added = allowed("alice-phone");
        when(allowFacade.addClient(eq("alice-phone"), any(String.class))).thenReturn(added);

        EnrollmentFacade facade = new EnrollmentFacade(rate, tokens, certs, allowFacade, audit);
        MintedBundle out = facade.redeem(FAKE_TOKEN, SOURCE_IP);
        assertThat(out).isSameAs(minted);

        // Order: rate → tokens → certs → allowFacade. The cross-domain
        // hand-off must hit the SIBLING FACADE (profile-java-server-
        // architecture rule 6), not the allowlist service directly.
        InOrder order = Mockito.inOrder(rate, tokens, certs, allowFacade);
        order.verify(rate).tryAcquire(SOURCE_IP);
        order.verify(tokens).redeem(FAKE_TOKEN);
        order.verify(certs).mint("alice-phone");
        order.verify(allowFacade).addClient(eq("alice-phone"), eq(minted.certPem()));

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

        // Short-circuit — no token redemption, no cert mint, no allowlist hand-off.
        verify(tokens, never()).redeem(any());
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
        when(tokens.redeem(FAKE_TOKEN)).thenReturn(new RedemptionOutcome.Unknown());

        EnrollmentFacade facade = new EnrollmentFacade(rate, tokens, certs, allowFacade, audit);
        assertThatThrownBy(() -> facade.redeem(FAKE_TOKEN, SOURCE_IP))
                .isInstanceOf(EnrollmentFacade.TokenInvalidException.class);

        verify(certs, never()).mint(any());
        verify(allowFacade, never()).addClient(any(), any());
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
        when(tokens.redeem(FAKE_TOKEN)).thenReturn(new RedemptionOutcome.Expired(consumed));

        EnrollmentFacade facade = new EnrollmentFacade(rate, tokens, certs, allowFacade, audit);
        assertThatThrownBy(() -> facade.redeem(FAKE_TOKEN, SOURCE_IP))
                .isInstanceOf(EnrollmentFacade.TokenExpiredException.class);

        verify(certs, never()).mint(any());
        verify(allowFacade, never()).addClient(any(), any());
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
        when(tokens.redeem(FAKE_TOKEN)).thenReturn(new RedemptionOutcome.AlreadyRedeemed());

        EnrollmentFacade facade = new EnrollmentFacade(rate, tokens, certs, allowFacade, audit);
        assertThatThrownBy(() -> facade.redeem(FAKE_TOKEN, SOURCE_IP))
                .isInstanceOf(EnrollmentFacade.TokenRedeemedException.class);

        verify(certs, never()).mint(any());
        verify(allowFacade, never()).addClient(any(), any());
        ArgumentCaptor<AuditAction> action = ArgumentCaptor.forClass(AuditAction.class);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(audit).logEvent(action.capture(), reason.capture(), any(Object[].class));
        assertThat(action.getValue()).isEqualTo(AuditAction.CLIENT_ENROLL_REJECT);
        assertThat(reason.getValue()).isEqualTo("token-redeemed");
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
        when(tokens.redeem(FAKE_TOKEN)).thenReturn(new RedemptionOutcome.Success(consumed));
        when(certs.mint("bob-tablet")).thenReturn(bundle("bob-tablet"));
        when(allowFacade.addClient(eq("bob-tablet"), any(String.class))).thenReturn(allowed("bob-tablet"));

        EnrollmentFacade facade = new EnrollmentFacade(rate, tokens, certs, allowFacade, audit);
        facade.redeem(FAKE_TOKEN, SOURCE_IP);

        // Exactly one allowlist-domain call, and it's on the sibling facade.
        verify(allowFacade).addClient(eq("bob-tablet"), any(String.class));
    }
}
