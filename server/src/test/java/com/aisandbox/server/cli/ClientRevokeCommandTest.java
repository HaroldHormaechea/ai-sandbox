package com.aisandbox.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.pki.PemUtils;
import com.aisandbox.server.test.CertFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * AC39 — {@code client revoke} removes the matching allowlist file.
 * Resolution matches by name OR by SHA-256 fingerprint.
 */
class ClientRevokeCommandTest {

    @Test
    void revokes_by_name(@TempDir Path tmp) throws Exception {
        Path clients = tmp.resolve("clients");
        Path crt = CertFixtures.writeClientPemTo(clients, "alice");
        assertThat(crt).exists();

        int exit =
                new CommandLine(new ClientRevokeCommand.Revoke()).execute("alice", "--clients-dir", clients.toString());

        assertThat(exit).isZero();
        assertThat(crt).doesNotExist();
    }

    @Test
    void revokes_by_fingerprint(@TempDir Path tmp) throws Exception {
        Path clients = tmp.resolve("clients");
        Path crt = CertFixtures.writeClientPemTo(clients, "bob");
        String fp = PemUtils.fingerprintHex(PemUtils.parseCertificate(Files.readString(crt)));

        int exit = new CommandLine(new ClientRevokeCommand.Revoke()).execute(fp, "--clients-dir", clients.toString());

        assertThat(exit).isZero();
        assertThat(crt).doesNotExist();
    }

    @Test
    void returns_non_zero_when_no_match(@TempDir Path tmp) throws Exception {
        Path clients = tmp.resolve("clients");
        Files.createDirectories(clients);
        // Drop one unrelated cert.
        CertFixtures.writeClientPemTo(clients, "kept");

        int exit =
                new CommandLine(new ClientRevokeCommand.Revoke()).execute("nope", "--clients-dir", clients.toString());

        assertThat(exit).isEqualTo(1);
        assertThat(clients.resolve("kept.crt")).exists();
    }
}
