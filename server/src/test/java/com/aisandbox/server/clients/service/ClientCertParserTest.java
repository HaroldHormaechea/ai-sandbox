package com.aisandbox.server.clients.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aisandbox.server.clients.dto.AllowedClient;
import com.aisandbox.server.pki.PemUtils;
import com.aisandbox.server.test.CertFixtures;
import java.security.cert.CertificateException;
import org.junit.jupiter.api.Test;

/**
 * AC12 / AC19 (POST /v1/clients) — parser drives the wire-shape returned
 * to API consumers. CN, fingerprint, and serial MUST all be derivable
 * from the PEM alone.
 */
class ClientCertParserTest {

    private final ClientCertParser parser = new ClientCertParser();

    @Test
    void parses_a_fresh_self_signed_cert() throws Exception {
        CertFixtures.ClientMaterial mat = CertFixtures.newClient("alice");

        AllowedClient parsed = parser.parse("alice", mat.pem());

        assertThat(parsed.name()).isEqualTo("alice");
        assertThat(parsed.cn()).isEqualTo("alice");
        assertThat(parsed.fingerprintHex()).isEqualTo(PemUtils.fingerprintHex(mat.certificate()));
        assertThat(parsed.serial()).isEqualTo(mat.certificate().getSerialNumber());
        assertThat(parsed.addedAt()).isNotNull();
    }

    @Test
    void rejects_an_empty_pem() {
        assertThatThrownBy(() -> parser.parse("oops", ""))
                .isInstanceOfAny(CertificateException.class, java.io.IOException.class);
    }

    @Test
    void rejects_garbage_pem() {
        assertThatThrownBy(() -> parser.parse("oops", "----BEGIN NONSENSE----\nblahblah\n----END NONSENSE----\n"))
                .isInstanceOfAny(CertificateException.class, java.io.IOException.class);
    }

    @Test
    void fingerprint_is_lowercase_hex_64_chars() throws Exception {
        AllowedClient parsed = parser.parse("bob", CertFixtures.newClient("bob").pem());
        assertThat(parsed.fingerprintHex()).hasSize(64).matches("[0-9a-f]+");
    }
}
