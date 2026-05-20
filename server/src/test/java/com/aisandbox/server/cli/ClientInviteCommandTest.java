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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * UC04 AC32 — {@code aisandboxctl client invite <name>} writes a single-
 * use enrollment token to disk (256-bit entropy, hex-encoded) at
 * {@code <enrollment-dir>/<token-prefix>.json}, mode 0640 (UC07 Bug D),
 * and emits a QR payload (PNG when {@code --out} provided, otherwise
 * the JSON payload echoed to stdout for non-TTY callers).
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
    void invite_writes_token_file_with_entropy_and_mode_0640(@TempDir Path tmp) throws Exception {
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

        // Mode 0640 — UC07 Bug D. Owner=ai-sandbox-server reads as the
        // server process; ai-sandbox-server group members (the operator
        // when explicitly added) can inspect via the read bit. World bit
        // stays off. The tmp file is 0600 for the transient atomic-move
        // window; the canonical name lands at 0640 after the rename so
        // we never expose a 0600 → 0640 widening on the final path.
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(tokenFile);
        assertThat(perms)
                .containsExactlyInAnyOrder(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ);

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

    // ── UC07 Bug C + Bug D (defaultEnrollmentDir + chown gating) ─────

    @Test
    void default_enrollment_dir_is_var_lib_fhs_canonical_path() {
        // UC07 Bug C — `aisandboxctl client invite` (and `pki init`) now
        // default to /var/lib/ai-sandbox-server/enrollment, not
        // /etc/ai-sandbox-server/enrollment. Mutable runtime state
        // belongs under /var/lib per FHS; /etc is for configuration.
        // The zero-arg accessor is the contract the rest of the CLI
        // depends on when `--enrollment-dir` is omitted.
        assertThat(ClientInviteCommand.defaultEnrollmentDir().toString())
                .isEqualTo("/var/lib/ai-sandbox-server/enrollment");
    }

    @Test
    void chown_assertion_is_gated_on_ai_sandbox_server_user_resolution(@TempDir Path tmp) throws Exception {
        // UC07 Bug D — Ownership.resolve("ai-sandbox-server", ...) returns
        // null on hosts where the system user is absent (every dev/CI
        // sandbox) and on non-POSIX filesystems. When it returns null,
        // the production code skips chown and emits a single warning to
        // stderr. This test pins that contract:
        //  - When the user resolves → chown ran (we can't verify Files.getOwner
        //    in a portable way without the actual user, so we just confirm
        //    the command completed and emitted no skip-warning).
        //  - When the user does NOT resolve → command still succeeds, file
        //    exists, and the documented stderr warning is present.
        //
        // Mirrors the skip pattern in PkiInitCommandTest — the tests run
        // on a host without the system user, so we expect the negative
        // branch.
        Path pki = tmp.resolve("pki");
        Path enrollment = tmp.resolve("enrollment");
        Path outPng = tmp.resolve("invite.png");
        Files.createDirectories(pki);
        CertFixtures.writeServerMaterialTo(pki, "server-cn");

        Ownership resolved = Ownership.resolve("ai-sandbox-server", "test-probe");
        Assumptions.assumeTrue(
                resolved == null,
                "test host has the ai-sandbox-server user — chown branch is exercised in CI release-install-smoke");

        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        System.setErr(new PrintStream(errBuf, true));
        int exit;
        try {
            exit = runInvite(
                    "alice-phone",
                    "--server-url",
                    "https://example.com:12410",
                    "--pki-dir",
                    pki.toString(),
                    "--enrollment-dir",
                    enrollment.toString(),
                    "--out",
                    outPng.toString());
        } finally {
            System.setErr(origErr);
        }
        assertThat(exit).isZero();
        assertThat(onlyFile(enrollment)).exists();
        // Documented warning from Ownership.resolve when the user is missing.
        // Same wording as PkiInitCommand emits.
        assertThat(errBuf.toString()).contains("skipping chown").contains("ai-sandbox-server");
    }

    // ── UC07 § AC5 — `--json` matrix (v0.0.8 machine-clean output) ───

    /**
     * AC5 row 1 — {@code --json} alone. Stdout is a single line of
     * compact JSON (no QR, no operator-facing trailer). The trailer
     * goes to stderr so stdout remains a clean machine-readable
     * channel. No PNG is written anywhere.
     *
     * <p>The contract this guards: a CI / scripted caller that consumes
     * stdout as JSON must never have to {@code head -n 1} or similar
     * workarounds; that was the v0.0.7-era pattern this flag replaces.
     */
    @Test
    void json_alone_emits_clean_payload_to_stdout_and_trailer_to_stderr_no_png(@TempDir Path tmp) throws Exception {
        Path pki = tmp.resolve("pki");
        Path enrollment = tmp.resolve("enrollment");
        Files.createDirectories(pki);
        CertFixtures.writeServerMaterialTo(pki, "server-cn");

        ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        int exit;
        try {
            System.setOut(new PrintStream(stdoutBuf, true));
            System.setErr(new PrintStream(stderrBuf, true));
            exit = runInvite(
                    "alice-phone",
                    "--server-url",
                    "https://example.com:12410",
                    "--pki-dir",
                    pki.toString(),
                    "--enrollment-dir",
                    enrollment.toString(),
                    "--json");
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }

        assertThat(exit).isZero();

        String stdout = stdoutBuf.toString();
        String stderr = stderrBuf.toString();

        // Stdout: exactly one line, that line is the compact JSON payload.
        // (println adds a trailing newline; trim and assert the
        //  content is a single non-empty line of JSON.)
        String stdoutTrim = stdout.strip();
        assertThat(stdoutTrim.lines().count())
                .as("--json: stdout must be exactly one line — the JSON payload")
                .isEqualTo(1);
        assertThat(stdoutTrim).startsWith("{").endsWith("}");

        // Validate the line parses as JSON with the {u, t, exp, pin} shape.
        ObjectMapper m = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode parsed = m.readTree(stdoutTrim);
        assertThat(parsed.fieldNames()).toIterable().containsExactlyInAnyOrder("u", "t", "exp", "pin");
        assertThat(parsed.get("u").asText()).isEqualTo("https://example.com:12410");
        assertThat(parsed.get("t").asText()).matches("[0-9a-f]{64}");
        assertThat(parsed.get("pin").asText()).matches("[0-9a-f]+");

        // Stdout has NO operator-facing trailer lines.
        assertThat(stdout)
                .as("--json: stdout must NOT contain operator-facing trailer fields")
                .doesNotContain("Invite issued:")
                .doesNotContain("token-prefix")
                .doesNotContain("expires-at")
                .doesNotContain("Wrote PNG QR:");

        // Stderr carries the operator-facing trailer.
        assertThat(stderr)
                .as("--json: trailer (Invite issued / token-prefix / expires-at / file) must be on stderr")
                .contains("Invite issued: alice-phone")
                .contains("token-prefix")
                .contains("expires-at")
                .contains("file         :");

        // PNG suppression: neither stdout nor any --out path got a PNG.
        // (PNG signature is the eight bytes 89 50 4E 47 0D 0A 1A 0A; we
        // check the textual signature here — sufficient since the
        // command builds PNG only via QrEncoder.writePng on --out.)
        assertThat(stdoutBuf.toByteArray())
                .as("--json without --out: stdout must NOT contain a PNG signature")
                .isNotEmpty();
        byte[] stdoutBytes = stdoutBuf.toByteArray();
        if (stdoutBytes.length >= 8) {
            byte[] firstEight = new byte[8];
            System.arraycopy(stdoutBytes, 0, firstEight, 0, 8);
            assertThat(firstEight)
                    .as("--json: stdout's first 8 bytes must NOT be the PNG signature 89 50 4E 47 0D 0A 1A 0A")
                    .isNotEqualTo(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        }

        // Token file IS still written under the enrollment dir — that
        // contract is shared with the no-`--json` flow.
        assertThat(onlyFile(enrollment)).exists();
    }

    /**
     * AC5 row 2 — {@code --json --out <path>}. The flags compose:
     * stdout still receives the compact JSON (single line) AND the
     * same JSON is written to {@code <path>}. The {@code <path>} is
     * deliberately NOT a PNG; {@code --json} suppresses QR generation
     * entirely, so the file content is bytes-identical to stdout. The
     * trailer goes to stderr.
     *
     * <p>The contract this guards: a CI step that wants the JSON on
     * disk for downstream tooling AND on stdout for a log capture
     * gets both, without having to re-derive the JSON from a PNG.
     */
    @Test
    void json_with_out_writes_json_payload_to_file_not_a_png(@TempDir Path tmp) throws Exception {
        Path pki = tmp.resolve("pki");
        Path enrollment = tmp.resolve("enrollment");
        Path outFile = tmp.resolve("invite.json");
        Files.createDirectories(pki);
        CertFixtures.writeServerMaterialTo(pki, "server-cn");

        ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        int exit;
        try {
            System.setOut(new PrintStream(stdoutBuf, true));
            System.setErr(new PrintStream(stderrBuf, true));
            exit = runInvite(
                    "alice-phone",
                    "--server-url",
                    "https://example.com:12410",
                    "--pki-dir",
                    pki.toString(),
                    "--enrollment-dir",
                    enrollment.toString(),
                    "--out",
                    outFile.toString(),
                    "--json");
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }

        assertThat(exit).isZero();
        assertThat(outFile)
                .as("--json --out: the file must exist (with JSON content, NOT a PNG)")
                .exists();

        // File content: same compact JSON line as stdout, NOT a PNG.
        byte[] fileBytes = Files.readAllBytes(outFile);
        assertThat(fileBytes.length).isGreaterThanOrEqualTo(8);
        byte[] firstEight = new byte[8];
        System.arraycopy(fileBytes, 0, firstEight, 0, 8);
        assertThat(firstEight)
                .as("--json --out: file's first 8 bytes must NOT be the PNG signature")
                .isNotEqualTo(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

        // File content parses as the same {u, t, exp, pin} shape.
        String fileStr = Files.readString(outFile);
        ObjectMapper m = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode parsedFromFile = m.readTree(fileStr);
        assertThat(parsedFromFile.fieldNames()).toIterable().containsExactlyInAnyOrder("u", "t", "exp", "pin");

        // Stdout AND file carry the same JSON payload (bytes-equal modulo
        // trailing newline that println adds on stdout).
        String stdoutLine = stdoutBuf.toString().strip();
        assertThat(stdoutLine).isEqualTo(fileStr);

        // Trailer on stderr.
        assertThat(stderrBuf.toString())
                .as("--json --out: trailer on stderr")
                .contains("Invite issued: alice-phone")
                .contains("token-prefix")
                .contains("file         :");

        // Stdout has no trailer pieces.
        assertThat(stdoutBuf.toString())
                .as("--json --out: stdout must NOT contain trailer text or PNG announcement")
                .doesNotContain("Invite issued:")
                .doesNotContain("Wrote PNG QR:");
    }

    /**
     * AC5 row 3 — no {@code --json}: byte-for-byte v0.0.7 layout.
     * Operator-facing trailer (Invite issued / token-prefix /
     * expires-at / file) lives on STDOUT — same place v0.0.7 scripts
     * have always read it from. Stderr is silent (no chown warning is
     * a separate skip-guarded test above).
     *
     * <p>This is the explicit back-compat guard: a future refactor
     * that accidentally promotes the trailer to stderr (e.g. mistakenly
     * always-on instead of {@code --json}-gated) would break
     * pre-v0.0.8 callers that scrape the trailer from stdout. This
     * test pins the unchanged-stream invariant.
     */
    @Test
    void non_json_keeps_trailer_on_stdout_for_v007_backcompat(@TempDir Path tmp) throws Exception {
        Path pki = tmp.resolve("pki");
        Path enrollment = tmp.resolve("enrollment");
        Path outPng = tmp.resolve("invite.png");
        Files.createDirectories(pki);
        CertFixtures.writeServerMaterialTo(pki, "server-cn");

        ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        int exit;
        try {
            System.setOut(new PrintStream(stdoutBuf, true));
            System.setErr(new PrintStream(stderrBuf, true));
            exit = runInvite(
                    "alice-phone",
                    "--server-url",
                    "https://example.com:12410",
                    "--pki-dir",
                    pki.toString(),
                    "--enrollment-dir",
                    enrollment.toString(),
                    "--out",
                    outPng.toString());
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }

        assertThat(exit).isZero();

        String stdout = stdoutBuf.toString();
        // v0.0.7 stdout layout intact: PNG announcement + payload line + trailer.
        assertThat(stdout)
                .as("no --json: stdout still carries Wrote PNG QR + Payload + trailer (v0.0.7 layout)")
                .contains("Wrote PNG QR: " + outPng)
                .contains("Payload     : ")
                .contains("Invite issued: alice-phone")
                .contains("token-prefix")
                .contains("expires-at")
                .contains("file         :");

        // Stderr is silent on the success path (the chown-skip warning is
        // covered by `chown_assertion_is_gated_on_ai_sandbox_server_user_resolution`
        // and only fires under that scenario's Assumptions.assumeTrue gate).
        // Filter to non-empty lines so a trailing newline doesn't false-fail.
        String stderr = stderrBuf.toString();
        assertThat(stderr.lines().filter(l -> !l.isBlank()).toList())
                .as("no --json: stderr should be empty on the success path (no trailer leakage)")
                .allSatisfy(line ->
                        // Tolerate the Ownership skip warning when the test host
                        // happens to lack the ai-sandbox-server user (same skip
                        // condition as the existing chown test). Anything ELSE
                        // is a regression.
                        assertThat(line).containsAnyOf("skipping chown", "ai-sandbox-server"));
    }

    // ── UC10 § AC6 / AC7 — SAN-vs-URL validation at QR mint ──────────────────

    /**
     * UC10 § AC7 Case B — happy path. The {@code --server-url} host is
     * present in the cert's SubjectAlternativeName list, so the invite
     * mints successfully. Pins {@code localhost} (default SAN) as the
     * URL host.
     *
     * <p>Pre-fix expectation: PASSES on the current branch — the
     * validation isn't wired yet, so the command never refuses.
     * Post-fix expectation: PASSES — explicit SAN match, allowed.
     * Cascade signal is on the REFUSAL tests below.
     */
    @Test
    void uc10_url_host_in_san_passes(@TempDir Path tmp) throws Exception {
        Path pki = tmp.resolve("pki");
        Path enrollment = tmp.resolve("enrollment");
        Path outPng = tmp.resolve("invite.png");
        Files.createDirectories(pki);
        // Cert SAN = DNS:localhost + IP:127.0.0.1.
        CertFixtures.writeServerMaterialTo(pki, "uc10-server", List.of("DNS:localhost", "IP:127.0.0.1"));

        int exit = runInvite(
                "alice-phone",
                "--server-url",
                "https://localhost:12410",
                "--pki-dir",
                pki.toString(),
                "--enrollment-dir",
                enrollment.toString(),
                "--out",
                outPng.toString());

        assertThat(exit).as("UC10 § AC7 — URL host in SAN must succeed").isZero();
        assertThat(onlyFile(enrollment)).exists();
    }

    /**
     * UC10 § AC7 Case B — URL host NOT in cert SAN. Refuses with exit 2
     * and a stderr message that names the URL host, the SAN entries the
     * cert actually contains, and the remediation command
     * ({@code aisandboxctl pki init --force --san <tag>:<host>}).
     *
     * <p>Pre-fix expectation: FAILS — no SAN validation exists; the
     * command exits 0 and writes the token file.
     * Post-fix expectation: PASSES.
     */
    @Test
    void uc10_url_host_not_in_san_refuses_with_exit_2_and_documented_message(@TempDir Path tmp) throws Exception {
        Path pki = tmp.resolve("pki");
        Path enrollment = tmp.resolve("enrollment");
        Path outPng = tmp.resolve("invite.png");
        Files.createDirectories(pki);
        // Cert SAN = DNS:potato-server + DNS:localhost + IP:127.0.0.1
        // (typical post-`pki init` default). 192.168.0.28 is NOT in the
        // SAN list — that's the empirical case the UC10 § Original
        // Description documents.
        CertFixtures.writeServerMaterialTo(
                pki, "uc10-server", List.of("DNS:potato-server", "DNS:localhost", "IP:127.0.0.1"));

        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        int exit;
        try {
            System.setErr(new PrintStream(errBuf, true));
            exit = runInvite(
                    "alice-phone",
                    "--server-url",
                    "https://192.168.0.28:12410",
                    "--pki-dir",
                    pki.toString(),
                    "--enrollment-dir",
                    enrollment.toString(),
                    "--out",
                    outPng.toString());
        } finally {
            System.setErr(origErr);
        }

        assertThat(exit)
                .as("UC10 § AC7 Case B — URL host '192.168.0.28' is not in the cert's SAN "
                        + "list (DNS:potato-server, DNS:localhost, IP:127.0.0.1); the command MUST "
                        + "exit 2. Pre-fix: FAILS — no SAN validation wired, command exits 0.")
                .isEqualTo(2);

        // No token file written on refusal.
        assertThat(Files.exists(enrollment) ? Files.list(enrollment).count() : 0L)
                .as("UC10 § AC7 Case B — refusal must not write the enrollment token.")
                .isZero();

        String stderr = errBuf.toString();
        // Stderr names the URL host, the cert's SAN entries, and the
        // remediation command. Three distinctive substrings — robust to
        // minor wording variation, but each is a contract break if
        // missing.
        assertThat(stderr)
                .as("UC10 § AC7 Case B — stderr must name the URL host that failed validation")
                .contains("192.168.0.28");
        assertThat(stderr)
                .as("UC10 § AC7 Case B — stderr must enumerate the cert's actual SAN entries")
                .contains("potato-server")
                .contains("127.0.0.1");
        assertThat(stderr)
                .as("UC10 § AC7 Case B — stderr must surface the remediation command "
                        + "(aisandboxctl pki init --force --san <tag>:<host>)")
                .contains("aisandboxctl pki init")
                .contains("--san");
    }

    /**
     * UC10 § AC7 Case C — operator-supplied {@code --server-pin} does
     * NOT bypass SAN validation. The pin overrides the auto-discovered
     * pin source (the SPKI of {@code server.crt}), but the host
     * validation still reads the cert's SAN list. The empirical case:
     * an operator with a fresh {@code potato-server} SAN list, but
     * passing a remembered pin from a prior cert, must still be
     * refused if their URL host is not in the SAN.
     *
     * <p>Pre-fix expectation: FAILS — no SAN validation wired.
     * Post-fix expectation: PASSES — refusal applies regardless of pin
     * source.
     */
    @Test
    void uc10_server_pin_override_does_not_bypass_san_validation(@TempDir Path tmp) throws Exception {
        Path pki = tmp.resolve("pki");
        Path enrollment = tmp.resolve("enrollment");
        Path outPng = tmp.resolve("invite.png");
        Files.createDirectories(pki);
        CertFixtures.writeServerMaterialTo(pki, "uc10-server", List.of("DNS:potato-server", "IP:127.0.0.1"));

        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        int exit;
        try {
            System.setErr(new PrintStream(errBuf, true));
            exit = runInvite(
                    "alice-phone",
                    "--server-url",
                    "https://192.168.0.28:12410",
                    "--server-pin",
                    "deadbeef".repeat(8),
                    "--pki-dir",
                    pki.toString(),
                    "--enrollment-dir",
                    enrollment.toString(),
                    "--out",
                    outPng.toString());
        } finally {
            System.setErr(origErr);
        }

        assertThat(exit)
                .as("UC10 § AC7 Case C — --server-pin override does NOT bypass SAN validation; "
                        + "URL host not in SAN must still refuse with exit 2.")
                .isEqualTo(2);
        // No token file written on refusal.
        assertThat(Files.exists(enrollment) ? Files.list(enrollment).count() : 0L)
                .as("UC10 § AC7 Case C — refusal must not write the enrollment token.")
                .isZero();
        assertThat(errBuf.toString())
                .as("UC10 § AC7 Case C — same refusal message structure as Case B")
                .contains("192.168.0.28")
                .contains("aisandboxctl pki init");
    }

    /**
     * UC10 § AC6 Case A — IPv6-literal {@code --server-url} is refused
     * up-front with the exact, AC6-verbatim message:
     *
     * <pre>--server-url with an IPv6 literal is not supported yet; pass a DNS name or IPv4 address</pre>
     *
     * <p>Pre-fix expectation: FAILS — no IPv6 detection wired; command
     * either exits 0 or fails for an unrelated reason (e.g. bracket
     * parse).
     * Post-fix expectation: PASSES with the documented stderr text.
     */
    @Test
    void uc10_ipv6_literal_in_url_is_refused_with_verbatim_message(@TempDir Path tmp) throws Exception {
        Path pki = tmp.resolve("pki");
        Path enrollment = tmp.resolve("enrollment");
        Path outPng = tmp.resolve("invite.png");
        Files.createDirectories(pki);
        // Cert SAN doesn't matter for this test — IPv6 refusal must
        // fire before SAN validation runs.
        CertFixtures.writeServerMaterialTo(pki, "uc10-server", List.of("DNS:localhost", "IP:127.0.0.1"));

        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        int exit;
        try {
            System.setErr(new PrintStream(errBuf, true));
            exit = runInvite(
                    "alice-phone",
                    "--server-url",
                    "https://[::1]:12410",
                    "--pki-dir",
                    pki.toString(),
                    "--enrollment-dir",
                    enrollment.toString(),
                    "--out",
                    outPng.toString());
        } finally {
            System.setErr(origErr);
        }

        assertThat(exit)
                .as("UC10 § AC6 Case A — IPv6-literal --server-url must exit 2. "
                        + "Pre-fix: FAILS — no IPv6 detection wired.")
                .isEqualTo(2);
        // No token file written on refusal.
        assertThat(Files.exists(enrollment) ? Files.list(enrollment).count() : 0L)
                .as("UC10 § AC6 Case A — refusal must not write the enrollment token.")
                .isZero();
        // The AC6 message text is quoted verbatim in the use case.
        assertThat(errBuf.toString())
                .as("UC10 § AC6 Case A — stderr must contain the exact AC6 IPv6 refusal text "
                        + "verbatim. A change to this string is a contract break.")
                .contains(
                        "--server-url with an IPv6 literal is not supported yet; " + "pass a DNS name or IPv4 address");
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
