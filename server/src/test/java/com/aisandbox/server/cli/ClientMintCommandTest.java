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
 * AC38 — {@code client mint <name> --pem} writes the trio (cert + key +
 * server.crt copy + README) into the chosen output dir AND copies the
 * client public cert into the allowlist folder for the watcher to pick up.
 * The {@code --pem} branch is the one exercised in unit tests: the default
 * P12 mode prompts for a passphrase via {@link java.io.Console}, which is
 * unavailable in a Gradle test JVM, so the P12 path is covered separately
 * through {@code Pkcs12Writer} directly in the integration tier.
 */
class ClientMintCommandTest {

    @Test
    void pem_mode_emits_trio_and_writes_into_allowlist(@TempDir Path tmp) throws Exception {
        Path pki = tmp.resolve("pki");
        Path clients = tmp.resolve("clients");
        Path out = tmp.resolve("out");
        Files.createDirectories(pki);
        Files.createDirectories(clients);
        Files.createDirectories(out);

        // Seed the server's public cert so the command copies it next to the bundle.
        CertFixtures.writeServerMaterialTo(pki, "server-cn");

        int exit = new CommandLine(new ClientMintCommand())
                .execute(
                        "mint",
                        "alice-laptop",
                        "--out",
                        out.toString(),
                        "--clients-dir",
                        clients.toString(),
                        "--pki-dir",
                        pki.toString(),
                        "--pem");

        assertThat(exit).isZero();
        assertThat(out.resolve("alice-laptop.crt")).exists();
        assertThat(out.resolve("alice-laptop.key")).exists();
        assertThat(out.resolve("server.crt")).exists();
        assertThat(out.resolve("README.txt")).exists();
        assertThat(clients.resolve("alice-laptop.crt")).exists();

        // The cert in the allowlist must have CN=alice-laptop.
        var cert = PemUtils.parseCertificate(Files.readString(clients.resolve("alice-laptop.crt")));
        assertThat(PemUtils.extractCommonName(cert)).isEqualTo("alice-laptop");
    }

    @Test
    void rejects_filename_unsafe_name(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("out");
        Files.createDirectories(out);
        int exit = new CommandLine(new ClientMintCommand())
                .execute(
                        "mint",
                        "alice; rm -rf /",
                        "--out",
                        out.toString(),
                        "--clients-dir",
                        tmp.resolve("clients").toString(),
                        "--pki-dir",
                        tmp.resolve("pki").toString(),
                        "--pem");
        assertThat(exit).isEqualTo(2);
        // No allowlist entry was written.
        assertThat(tmp.resolve("clients")).doesNotExist();
    }
}
