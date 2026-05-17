package com.aisandbox.server.cli;

import com.aisandbox.server.cli.pki.QrEncoder;
import com.aisandbox.server.pki.PemUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code aisandboxctl client invite <name>} (UC04 § B3) — issue a
 * single-use enrollment token, persist it under
 * {@code <pki.dir>/../enrollment/<token-prefix>.json}, and emit a QR
 * encoding {@code {u, t, exp, pin}} (server URL, token, expiry, pin)
 * for the Android client to scan (UC04-1 onboarding).
 *
 * <p>The cert itself is NOT minted here — that happens on redemption
 * via {@code POST /v1/enrollment}, so an unredeemed / expired invite
 * leaves no cert behind.
 *
 * <p>The on-disk format is identical to
 * {@code com.aisandbox.server.enrollment.service.EnrollmentTokenStore}'s
 * — {@code {"token":"…","name":"…","exp":"ISO-8601"}} at
 * {@code <enrollment-dir>/<first 16 hex of token>.json} mode 0600.
 * The CLI duplicates the writer rather than importing the store class
 * so that the {@code cli ↔ enrollment} package edge stays one-way:
 * {@code enrollment → cli} for the cert-mint reuse of
 * {@code cli.pki.ClientCertGenerator}, no return edge. The
 * {@code LayeringTest} no-cycles ArchUnit rule enforces this.
 *
 * <p>Output policy:
 *
 * <ul>
 *   <li>If {@code --out} is omitted AND stdout is a TTY → ASCII QR to
 *       stdout, scannable directly from the terminal.</li>
 *   <li>If {@code --out} is provided → 512 × 512 PNG written to that
 *       path; the JSON payload is also echoed to stdout so the operator
 *       can copy-paste it if the scanner can't read the PNG.</li>
 * </ul>
 *
 * <p>The {@code --server-pin} flag accepts an explicit pin (when the
 * operator wants to override what's on disk); otherwise the cert at
 * {@code <pki.dir>/server.crt} is read and its SHA-256 fingerprint is
 * used. The pin is the lowercase hex digest — matching the format that
 * {@code PemUtils.fingerprintHex} produces and the Android client's
 * {@code OkHttp CertificatePinner} expects.
 */
@Command(name = "invite", description = "Issue a single-use Android enrollment token + QR.")
public class ClientInviteCommand implements Callable<Integer> {

    /** SecureRandom entropy in bytes — 32 bytes = 256 bits (AC32). */
    private static final int TOKEN_ENTROPY_BYTES = 32;

    /**
     * Filename prefix length used by the enrollment-token store. MUST
     * match {@code EnrollmentTokenStore.FILENAME_PREFIX_LEN} — both the
     * CLI writer and the server reader compute the path from this
     * constant. Drift here breaks redemption.
     */
    private static final int FILENAME_PREFIX_LEN = 16;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final Set<PosixFilePermission> MODE_600 =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private static final Set<PosixFilePermission> MODE_700 = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    @Parameters(arity = "1", description = "Client name (used as CN + allowlist filename stem on mint).")
    String name;

    @Option(names = "--ttl", description = "Token lifetime (ISO-8601 duration; default 10m).", defaultValue = "10m")
    String ttl;

    @Option(
            names = "--pki-dir",
            description = "Server PKI dir (where server.crt lives) (default /etc/ai-sandbox-server/pki)")
    Path pkiDir = Path.of("/etc/ai-sandbox-server/pki");

    @Option(names = "--enrollment-dir", description = "Enrollment token store (default <pki-dir>/../enrollment).")
    Path enrollmentDir;

    @Option(
            names = "--server-url",
            required = true,
            description = "Server base URL embedded in the QR (e.g. https://example.com:12410).")
    String serverUrl;

    @Option(
            names = "--server-pin",
            description = "Server cert SHA-256 fingerprint (hex). Default: SHA-256 of <pki-dir>/server.crt.")
    String serverPin;

    @Option(names = "--out", description = "Write a PNG QR to this path (default: ASCII QR to stdout).")
    Path outFile;

    @Override
    public Integer call() throws Exception {
        if (!name.matches("[A-Za-z0-9._-]+")) {
            System.err.println("Client name must match [A-Za-z0-9._-]+");
            return 2;
        }

        Duration ttlDuration = parseTtl(ttl);
        if (ttlDuration.isNegative() || ttlDuration.isZero()) {
            System.err.println("Invalid --ttl: must be a positive duration like '10m' or 'PT10M'");
            return 2;
        }

        Path effectiveEnrollmentDir = enrollmentDir != null ? enrollmentDir : defaultEnrollmentDir(pkiDir);
        ensureDir(effectiveEnrollmentDir);

        String pin = serverPin != null ? serverPin : autoDiscoverPin(pkiDir);
        if (pin == null) {
            System.err.println("Cannot resolve server pin: provide --server-pin or place server.crt under " + pkiDir);
            return 2;
        }

        String token = generateHexToken();
        Instant expiresAt = Instant.now().plus(ttlDuration);
        Path file = fileFor(effectiveEnrollmentDir, token);
        writeTokenFile(file, token, name, expiresAt);

        String payload = buildQrPayload(serverUrl, token, expiresAt, pin);
        boolean ttyOut = outFile == null && System.console() != null;
        if (outFile != null) {
            QrEncoder.writePng(payload, outFile);
            System.out.println("Wrote PNG QR: " + outFile);
            System.out.println("Payload     : " + payload);
        } else if (ttyOut) {
            QrEncoder.writeAscii(payload, System.out);
        } else {
            // Non-TTY without --out: print just the payload so the
            // operator can pipe it.
            System.out.println(payload);
        }

        System.out.println();
        System.out.println("Invite issued: " + name);
        System.out.println("  token-prefix : " + token.substring(0, FILENAME_PREFIX_LEN));
        System.out.println("  expires-at   : " + expiresAt);
        System.out.println("  file         : " + file);
        return 0;
    }

    /**
     * Default enrollment dir is a sibling of the PKI dir named
     * {@code enrollment} — matches {@code application.yaml}'s default
     * of {@code /etc/ai-sandbox-server/enrollment} when {@code pkiDir}
     * is the standard {@code /etc/ai-sandbox-server/pki}.
     */
    static Path defaultEnrollmentDir(Path pkiDir) {
        Path parent = pkiDir.getParent();
        return parent != null ? parent.resolve("enrollment") : Path.of("enrollment");
    }

    /**
     * Compute the on-disk path for a token; MUST match
     * {@code EnrollmentTokenStore.fileFor}.
     */
    static Path fileFor(Path dir, String token) {
        String prefix = token.length() <= FILENAME_PREFIX_LEN ? token : token.substring(0, FILENAME_PREFIX_LEN);
        return dir.resolve(prefix + ".json");
    }

    /**
     * Write the {@code {token, name, exp}} JSON to {@code file} mode
     * 0600 via tmp + atomic rename. MUST match the read shape in
     * {@code EnrollmentTokenStore.TokenJson}.
     */
    static void writeTokenFile(Path file, String token, String name, Instant exp)
            throws IOException, JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("name", name);
        body.put("exp", exp);
        ObjectMapper m = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        byte[] bytes = m.writeValueAsBytes(body);

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.setPosixFilePermissions(tmp, MODE_600);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX FS — leave default perms; operator typically
            // runs this on Linux as ai-sandbox-server.
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException amns) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void ensureDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            try {
                Files.setPosixFilePermissions(dir, MODE_700);
            } catch (UnsupportedOperationException ignored) {
                // best-effort
            }
        }
        // Ensure parent ownership/mode is sensible — defer to operator.
        @SuppressWarnings("unused")
        var _unused = PosixFilePermissions.fromString("rwx------");
    }

    /**
     * Build the {@code {u, t, exp, pin}} JSON payload as a compact UTF-8
     * string. Field order is preserved (LinkedHashMap) so the QR looks
     * identical run-to-run, which is helpful for operator-level
     * troubleshooting.
     */
    static String buildQrPayload(String serverUrl, String token, Instant exp, String pin)
            throws JsonProcessingException {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("u", serverUrl);
        body.put("t", token);
        body.put("exp", exp.toString());
        body.put("pin", pin);
        ObjectMapper m = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m.writeValueAsString(body);
    }

    private static String autoDiscoverPin(Path pkiDir) {
        Path serverCrt = pkiDir.resolve("server.crt");
        if (!Files.isRegularFile(serverCrt)) {
            return null;
        }
        try {
            String pem = Files.readString(serverCrt);
            X509Certificate cert = PemUtils.parseCertificate(pem);
            return PemUtils.fingerprintHex(cert);
        } catch (IOException | CertificateException io) {
            System.err.println("Cannot read or parse " + serverCrt + ": " + io.getMessage());
            return null;
        }
    }

    private static String generateHexToken() {
        SecureRandom random = new SecureRandom();
        byte[] buf = new byte[TOKEN_ENTROPY_BYTES];
        random.nextBytes(buf);
        char[] out = new char[buf.length * 2];
        for (int i = 0; i < buf.length; i++) {
            int v = buf[i] & 0xff;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0f];
        }
        return new String(out);
    }

    /**
     * Lenient TTL parser. Accepts ISO-8601 ({@code PT10M}) and a short
     * form ({@code 10m}, {@code 1h}, {@code 30s}). Returns
     * {@link Duration#ZERO} on parse failure so the caller can refuse.
     */
    static Duration parseTtl(String raw) {
        if (raw == null || raw.isBlank()) {
            return Duration.ZERO;
        }
        String t = raw.trim();
        try {
            return Duration.parse(t.startsWith("PT") || t.startsWith("P") ? t : "PT" + t.toUpperCase());
        } catch (Exception e) {
            return Duration.ZERO;
        }
    }
}
