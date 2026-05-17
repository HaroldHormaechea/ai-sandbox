package com.aisandbox.server.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.test.CertFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * UC04 AC32 — {@code aisandboxctl client invite <name>} writes a single-
 * use enrollment token to disk (256-bit entropy, hex-encoded) at
 * {@code <enrollment-dir>/<token-prefix>.json}, mode 0600, and emits a
 * QR payload (PNG when {@code --out} provided, otherwise the JSON
 * payload echoed to stdout for non-TTY callers).
 *
 * <p>The on-disk shape MUST match {@code EnrollmentTokenStore}'s reader.
 * Drift between writer and reader breaks redemption — this test pins
 * both halves against the same JSON keys.
 *
 * <p>NOTE: System.console() / TTY detection cannot be mocked from the
 * test JVM. The ASCII-to-TTY branch is documented but not exercised
 * here; coverage gap is explicit in the TEST SUMMARY. The PNG branch
 * (via {@code --out}) IS exercised.
 */
class ClientInviteCommandTest {

    private static int runInvite(String... extraArgs) {
        return new CommandLine(new ClientMintCommand()).execute(prepend(new String[] {"invite"}, extraArgs));
    }

    private static String[] prepend(String[] head, String[] tail) {
        String[] out = new String[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    @Test
    void invite_writes_token_file_with_entropy_and_mode_0600(@TempDir Path tmp) throws Exception {
        Path pki = tmp.resolve("pki");
        Path enrollment = tmp.resolve("enrollment");
        Path outPng = tmp.resolve("invite.png");
        Files.createDirectories(pki);

        // Seed server.crt so the command can auto-discover the pin.
        CertFixtures.writeServerMaterialTo(pki, "server-cn");

        int exit = runInvite(
                "alice-phone",
                "--server-url",
                "https://example.com:12410",
                "--pki-dir",
                pki.toString(),
                "--enrollment-dir",
                enrollment.toString(),
                "--out",
                outPng.toString());

        assertThat(exit).isZero();
        assertThat(outPng).exists();

        // Exactly one token file under the enrollment dir.
        Path tokenFile = onlyFile(enrollment);
        assertThat(tokenFile.getFileName().toString()).endsWith(".json");
        // 16-char hex prefix → filename "<prefix>.json" (16 + 5 = 21 chars).
        assertThat(tokenFile.getFileName().toString()).hasSize(21);

        // Mode 0600 — operator-only readable.
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tokenFile);
        assertThat(perms).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

        // JSON shape: {token, name, exp} — matches EnrollmentTokenStore's TokenJson.
        ObjectMapper m = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode body = m.readTree(tokenFile.toFile());
        assertThat(body.has("token")).isTrue();
        assertThat(body.has("name")).isTrue();
        assertThat(body.has("exp")).isTrue();
        assertThat(body.get("name").asText()).isEqualTo("alice-phone");

        // Token entropy: 64 hex chars = 256 bits (AC32).
        String token = body.get("token").asText();
        assertThat(token).matches("[0-9a-f]{64}");
        // Prefix matches the filename stem — the contract the
        // EnrollmentTokenStore.fileFor() reader depends on.
        String stem = tokenFile.getFileName().toString().replace(".json", "");
        assertThat(token).startsWith(stem);
    }

    @Test
    void invite_emits_qr_payload_with_u_t_exp_pin_keys_to_stdout(@TempDir Path tmp) throws Exception {
        Path pki = tmp.resolve("pki");
        Path enrollment = tmp.resolve("enrollment");
        Path outPng = tmp.resolve("invite.png");
        Files.createDirectories(pki);
        CertFixtures.writeServerMaterialTo(pki, "server-cn");

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        try {
            System.setOut(new PrintStream(stdout));
            int exit = runInvite(
                    "bob-tablet",
                    "--server-url",
                    "https://example.com:12410",
                    "--pki-dir",
                    pki.toString(),
                    "--enrollment-dir",
                    enrollment.toString(),
                    "--out",
                    outPng.toString());
            assertThat(exit).isZero();
        } finally {
            System.setOut(origOut);
        }

        // The "Payload     :" line carries the JSON QR payload — Android
        // scanner consumes exactly this string.
        String out = stdout.toString();
        assertThat(out).contains("Payload     : ");

        // Extract the JSON payload — first line that starts with {.
        String payload = out.lines()
                .filter(s -> s.contains("{") && s.contains("\"u\""))
                .findFirst()
                .orElseThrow();
        // Strip any "Payload     : " prefix.
        int brace = payload.indexOf('{');
        String json = payload.substring(brace);

        ObjectMapper m = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode parsed = m.readTree(json);
        // Field set is exactly {u, t, exp, pin}.
        assertThat(parsed.fieldNames()).toIterable().containsExactlyInAnyOrder("u", "t", "exp", "pin");
        assertThat(parsed.get("u").asText()).isEqualTo("https://example.com:12410");
        assertThat(parsed.get("t").asText()).matches("[0-9a-f]{64}");
        // Pin is lowercase hex (auto-discovered from server.crt).
        assertThat(parsed.get("pin").asText()).matches("[0-9a-f]+");
    }

    @Test
    void custom_ttl_is_honoured(@TempDir Path tmp) throws Exception {
        Path pki = tmp.resolve("pki");
        Path enrollment = tmp.resolve("enrollment");
        Path outPng = tmp.resolve("invite.png");
        Files.createDirectories(pki);
        CertFixtures.writeServerMaterialTo(pki, "server-cn");

        java.time.Instant before = java.time.Instant.now();
        int exit = runInvite(
                "alice-phone",
                "--server-url",
                "https://example.com:12410",
                "--ttl",
                "30s",
                "--pki-dir",
                pki.toString(),
                "--enrollment-dir",
                enrollment.toString(),
                "--out",
                outPng.toString());
        java.time.Instant after = java.time.Instant.now();
        assertThat(exit).isZero();

        Path tokenFile = onlyFile(enrollment);
        ObjectMapper m = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode body = m.readTree(tokenFile.toFile());
        java.time.Instant exp = java.time.Instant.parse(body.get("exp").asText());

        // exp ≈ now + 30s; bound around the call window to absorb clock skew.
        assertThat(exp).isAfter(before.plus(Duration.ofSeconds(28)));
        assertThat(exp).isBefore(after.plus(Duration.ofSeconds(32)));
    }

    @Test
    void explicit_server_pin_overrides_auto_discovery(@TempDir Path tmp) throws Exception {
        Path enrollment = tmp.resolve("enrollment");
        // No PKI dir at all — operator-supplied --server-pin should make
        // server.crt auto-discovery unnecessary.
        Path outPng = tmp.resolve("invite.png");

        int exit = runInvite(
                "alice-phone",
                "--server-url",
                "https://example.com:12410",
                "--server-pin",
                "deadbeef".repeat(8),
                "--pki-dir",
                tmp.resolve("nope").toString(),
                "--enrollment-dir",
                enrollment.toString(),
                "--out",
                outPng.toString());

        assertThat(exit).isZero();
        Path tokenFile = onlyFile(enrollment);
        assertThat(tokenFile).exists();
    }

    @Test
    void rejects_filename_unsafe_name(@TempDir Path tmp) throws Exception {
        Path enrollment = tmp.resolve("enrollment");
        // Reuses the same [A-Za-z0-9._-]+ name-shape contract as
        // `client mint`. Reject anything that could break the on-disk
        // schema or shell-injection.
        int exit = runInvite(
                "alice; rm -rf /",
                "--server-url",
                "https://example.com:12410",
                "--server-pin",
                "deadbeef".repeat(8),
                "--enrollment-dir",
                enrollment.toString());

        assertThat(exit).isEqualTo(2);
        // No token file was written.
        assertThat(Files.exists(enrollment)).isFalse();
    }

    @Test
    void rejects_zero_or_negative_ttl(@TempDir Path tmp) throws Exception {
        Path enrollment = tmp.resolve("enrollment");
        int exit = runInvite(
                "alice-phone",
                "--server-url",
                "https://example.com:12410",
                "--server-pin",
                "deadbeef".repeat(8),
                "--ttl",
                "0s",
                "--enrollment-dir",
                enrollment.toString());

        assertThat(exit).isEqualTo(2);
    }

    @Test
    void cli_writer_and_store_reader_agree_on_filename_layout(@TempDir Path tmp) throws Exception {
        // The CLI duplicates the on-disk writer rather than importing
        // EnrollmentTokenStore (LayeringTest forbids cli ↔ enrollment
        // cycles). This test pins the contract by issuing via the CLI
        // and reading back via the store — if either side drifts, the
        // redemption path breaks at runtime; this test catches it at
        // build time.
        Path pki = tmp.resolve("pki");
        Path enrollment = tmp.resolve("enrollment");
        Path outPng = tmp.resolve("invite.png");
        Files.createDirectories(pki);
        CertFixtures.writeServerMaterialTo(pki, "server-cn");

        int exit = runInvite(
                "alice-phone",
                "--server-url",
                "https://example.com:12410",
                "--pki-dir",
                pki.toString(),
                "--enrollment-dir",
                enrollment.toString(),
                "--out",
                outPng.toString());
        assertThat(exit).isZero();

        // Read back through the store — this is what the server's
        // POST /v1/enrollment handler does at redemption time.
        Path tokenFile = onlyFile(enrollment);
        ObjectMapper m = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode body = m.readTree(tokenFile.toFile());
        String token = body.get("token").asText();

        com.aisandbox.server.enrollment.service.EnrollmentTokenStore store =
                new com.aisandbox.server.enrollment.service.EnrollmentTokenStore(enrollment);
        var outcome = store.redeem(token, java.time.Clock.systemUTC());
        assertThat(outcome)
                .isInstanceOf(
                        com.aisandbox.server.enrollment.service.EnrollmentTokenStore.RedemptionOutcome.Success.class);
        assertThat(outcome.token().name()).isEqualTo("alice-phone");
    }

    private static Path onlyFile(Path dir) throws Exception {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            java.util.Iterator<Path> it = stream.iterator();
            assertThat(it.hasNext())
                    .as("expected exactly one .json file in %s", dir)
                    .isTrue();
            Path first = it.next();
            assertThat(it.hasNext())
                    .as("expected exactly one .json file in %s", dir)
                    .isFalse();
            return first;
        }
    }
}
