package com.aisandbox.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.pki.PemUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * AC7 — {@code aisandboxctl pki init} produces server.crt + server.key
 * with the right CN, an empty allowlist dir, and a sample config.yaml.
 * AC8 — private key is mode 0600 (we don't assert the exact mode in unit
 * tests since POSIX permissions are filesystem-dependent; the integration
 * tier covers that).
 */
class PkiInitCommandTest {

    @Test
    void init_creates_server_cert_key_and_clients_dir(@TempDir Path tmp) throws Exception {
        Path pki = tmp.resolve("pki");
        Path clients = tmp.resolve("clients");
        Path config = tmp.resolve("config.yaml");

        int exit = new CommandLine(new PkiInitCommand())
                .execute(
                        "init",
                        "--pki-dir",
                        pki.toString(),
                        "--clients-dir",
                        clients.toString(),
                        "--config",
                        config.toString(),
                        "--cn",
                        "unit-test-server");

        assertThat(exit).isZero();
        assertThat(pki.resolve("server.crt")).exists();
        assertThat(pki.resolve("server.key")).exists();
        assertThat(clients).isDirectory();
        assertThat(config).exists();

        // The cert's CN must match the override.
        var cert = PemUtils.parseCertificate(Files.readString(pki.resolve("server.crt")));
        assertThat(PemUtils.extractCommonName(cert)).isEqualTo("unit-test-server");
    }

    @Test
    void init_refuses_to_overwrite_existing_material_without_force(@TempDir Path tmp) throws Exception {
        Path pki = tmp.resolve("pki");
        Path clients = tmp.resolve("clients");
        Path config = tmp.resolve("config.yaml");

        int firstRun = new CommandLine(new PkiInitCommand())
                .execute(
                        "init",
                        "--pki-dir",
                        pki.toString(),
                        "--clients-dir",
                        clients.toString(),
                        "--config",
                        config.toString());
        assertThat(firstRun).isZero();

        int secondRun = new CommandLine(new PkiInitCommand())
                .execute(
                        "init",
                        "--pki-dir",
                        pki.toString(),
                        "--clients-dir",
                        clients.toString(),
                        "--config",
                        config.toString());
        assertThat(secondRun).isEqualTo(2);
    }

    @Test
    void init_overwrites_with_force(@TempDir Path tmp) throws Exception {
        Path pki = tmp.resolve("pki");
        Path clients = tmp.resolve("clients");
        Path config = tmp.resolve("config.yaml");

        new CommandLine(new PkiInitCommand())
                .execute(
                        "init",
                        "--pki-dir",
                        pki.toString(),
                        "--clients-dir",
                        clients.toString(),
                        "--config",
                        config.toString());

        int exit = new CommandLine(new PkiInitCommand())
                .execute(
                        "init",
                        "--pki-dir",
                        pki.toString(),
                        "--clients-dir",
                        clients.toString(),
                        "--config",
                        config.toString(),
                        "--force",
                        "--cn",
                        "second");
        assertThat(exit).isZero();
        var cert = PemUtils.parseCertificate(Files.readString(pki.resolve("server.crt")));
        assertThat(PemUtils.extractCommonName(cert)).isEqualTo("second");
    }

    @Test
    void init_defaults_cn_to_ai_sandbox_server(@TempDir Path tmp) throws Exception {
        Path pki = tmp.resolve("pki");
        Path clients = tmp.resolve("clients");
        Path config = tmp.resolve("config.yaml");
        new CommandLine(new PkiInitCommand())
                .execute(
                        "init",
                        "--pki-dir",
                        pki.toString(),
                        "--clients-dir",
                        clients.toString(),
                        "--config",
                        config.toString());
        var cert = PemUtils.parseCertificate(Files.readString(pki.resolve("server.crt")));
        assertThat(PemUtils.extractCommonName(cert)).isEqualTo("ai-sandbox-server");
    }
}
