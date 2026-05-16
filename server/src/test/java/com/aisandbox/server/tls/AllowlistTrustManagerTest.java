package com.aisandbox.server.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aisandbox.server.clients.service.ClientAllowlistService;
import com.aisandbox.server.pki.PemUtils;
import com.aisandbox.server.test.CertFixtures;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the AC12 fingerprint-only trust gate. The trust manager
 * MUST:
 * <ul>
 *   <li>Reject an empty / null chain.</li>
 *   <li>Reject a cert whose fingerprint is not in the allowlist.</li>
 *   <li>Accept a cert whose fingerprint matches an entry.</li>
 *   <li>Reject any call to {@code checkServerTrusted} (we never act as a TLS client).</li>
 * </ul>
 */
class AllowlistTrustManagerTest {

    @Test
    void rejects_null_chain() {
        ClientAllowlistService svc = mock(ClientAllowlistService.class);
        AllowlistTrustManager tm = new AllowlistTrustManager(svc);

        assertThatThrownBy(() -> tm.checkClientTrusted(null, "RSA"))
                .isInstanceOf(CertificateException.class)
                .hasMessageContaining("Empty");
    }

    @Test
    void rejects_empty_chain() {
        ClientAllowlistService svc = mock(ClientAllowlistService.class);
        AllowlistTrustManager tm = new AllowlistTrustManager(svc);

        assertThatThrownBy(() -> tm.checkClientTrusted(new X509Certificate[0], "RSA"))
                .isInstanceOf(CertificateException.class);
    }

    @Test
    void rejects_unknown_fingerprint() throws Exception {
        ClientAllowlistService svc = mock(ClientAllowlistService.class);
        when(svc.isAllowed(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        AllowlistTrustManager tm = new AllowlistTrustManager(svc);

        X509Certificate cert = CertFixtures.newClient("rejected").certificate();

        assertThatThrownBy(() -> tm.checkClientTrusted(new X509Certificate[] {cert}, "RSA"))
                .isInstanceOf(CertificateException.class)
                .hasMessageContaining("not in allowlist");
    }

    @Test
    void accepts_when_fingerprint_matches() throws Exception {
        ClientAllowlistService svc = mock(ClientAllowlistService.class);
        X509Certificate cert = CertFixtures.newClient("ok-client").certificate();
        String fp = PemUtils.fingerprintHex(cert);
        when(svc.isAllowed(fp)).thenReturn(true);

        AllowlistTrustManager tm = new AllowlistTrustManager(svc);
        // Should not throw.
        tm.checkClientTrusted(new X509Certificate[] {cert}, "RSA");
    }

    @Test
    void server_trusted_methods_are_never_acceptable() throws Exception {
        ClientAllowlistService svc = mock(ClientAllowlistService.class);
        AllowlistTrustManager tm = new AllowlistTrustManager(svc);
        X509Certificate cert = CertFixtures.newClient("any").certificate();

        assertThatThrownBy(() -> tm.checkServerTrusted(new X509Certificate[] {cert}, "RSA"))
                .isInstanceOf(CertificateException.class)
                .hasMessageContaining("Not a TLS client");
    }

    @Test
    void getAcceptedIssuers_is_empty() {
        ClientAllowlistService svc = mock(ClientAllowlistService.class);
        AllowlistTrustManager tm = new AllowlistTrustManager(svc);
        assertThat(tm.getAcceptedIssuers()).isEmpty();
    }
}
