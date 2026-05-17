package com.aisandbox.server.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

/**
 * UC04 § B2 — index of live WS sessions by client fingerprint, so the
 * revocation path can issue a graceful WS close (code 4401) before
 * tearing down the underlying TCP channel.
 */
class ActiveStreamRegistryTest {

    private static final String FP = "fa".repeat(32);

    @Test
    void attach_and_detach_track_session_count_per_fingerprint() {
        ActiveStreamRegistry reg = new ActiveStreamRegistry();
        WebSocketSession s1 = mock(WebSocketSession.class);
        WebSocketSession s2 = mock(WebSocketSession.class);

        assertThat(reg.sessionCountFor(FP)).isEqualTo(0);

        reg.attach(FP, s1);
        reg.attach(FP, s2);
        assertThat(reg.sessionCountFor(FP)).isEqualTo(2);

        reg.detach(FP, s1);
        assertThat(reg.sessionCountFor(FP)).isEqualTo(1);

        reg.detach(FP, s2);
        assertThat(reg.sessionCountFor(FP)).isEqualTo(0);
    }

    @Test
    void detach_of_unknown_session_is_a_noop() {
        ActiveStreamRegistry reg = new ActiveStreamRegistry();
        // Should not throw, should not register a phantom entry.
        reg.detach("never-attached-fp", mock(WebSocketSession.class));
        assertThat(reg.sessionCountFor("never-attached-fp")).isEqualTo(0);
    }

    @Test
    void null_or_empty_fingerprint_or_session_is_ignored_on_attach() {
        ActiveStreamRegistry reg = new ActiveStreamRegistry();
        reg.attach(null, mock(WebSocketSession.class));
        reg.attach("", mock(WebSocketSession.class));
        reg.attach(FP, null);
        // None of the above should register an entry.
        assertThat(reg.sessionCountFor(FP)).isEqualTo(0);
    }

    @Test
    void gracefulClose_emits_close_frame_on_every_session_with_supplied_status() {
        ActiveStreamRegistry reg = new ActiveStreamRegistry();
        WebSocketSession s1 = mock(WebSocketSession.class);
        WebSocketSession s2 = mock(WebSocketSession.class);
        when(s1.close(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
        when(s2.close(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());

        reg.attach(FP, s1);
        reg.attach(FP, s2);

        CloseStatus status = new CloseStatus(4401, "revoked");
        reg.gracefulClose(FP, status).block(Duration.ofSeconds(1));

        ArgumentCaptor<CloseStatus> cap = ArgumentCaptor.forClass(CloseStatus.class);
        verify(s1).close(cap.capture());
        verify(s2).close(cap.capture());
        assertThat(cap.getValue().getCode()).isEqualTo(4401);
        assertThat(cap.getValue().getReason()).isEqualTo("revoked");

        // Set is cleared after a graceful close so the next attach starts fresh.
        assertThat(reg.sessionCountFor(FP)).isEqualTo(0);
    }

    @Test
    void gracefulClose_with_unknown_fingerprint_completes_without_calling_anything() {
        ActiveStreamRegistry reg = new ActiveStreamRegistry();
        WebSocketSession ghost = mock(WebSocketSession.class);

        // Nothing attached at this fingerprint.
        Mono<Void> mono = reg.gracefulClose("never-seen-fp", new CloseStatus(4401, "revoked"));
        mono.block(Duration.ofSeconds(1));

        verify(ghost, never()).close(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void gracefulClose_swallows_per_session_errors_and_still_closes_others() {
        // One session throws; the other closes cleanly. The aggregate Mono
        // still completes — a single misbehaving client cannot deadlock
        // the revocation path.
        ActiveStreamRegistry reg = new ActiveStreamRegistry();
        WebSocketSession bad = mock(WebSocketSession.class);
        WebSocketSession good = mock(WebSocketSession.class);
        when(bad.close(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.error(new RuntimeException("boom")));
        when(good.close(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());

        reg.attach(FP, bad);
        reg.attach(FP, good);

        // No throw, no timeout — Mono completes within 1s budget.
        reg.gracefulClose(FP, new CloseStatus(4401, "revoked")).block(Duration.ofSeconds(1));
        verify(bad, times(1)).close(org.mockito.ArgumentMatchers.any());
        verify(good, times(1)).close(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void gracefulClose_uses_code_4401_when_called_via_ActiveConnectionRegistry_revoke() {
        // Cross-check the wire close code that the Android client AC26
        // path watches for. The constant lives on ActiveConnectionRegistry;
        // we sanity-check it here so a future drift surfaces in the
        // identity-package tests, not in a flaky end-to-end run.
        assertThat(ActiveConnectionRegistry.REVOKED_CLOSE_CODE).isEqualTo(4401);
        assertThat(ActiveConnectionRegistry.REVOKED_CLOSE_REASON).isEqualTo("revoked");
    }
}
