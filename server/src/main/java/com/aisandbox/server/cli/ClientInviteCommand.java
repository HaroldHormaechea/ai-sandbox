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
 *   <li><b>No {@code --json}, no {@code --out}, stdout is a TTY</b> →
 *       ASCII QR + operator-facing trailer to stdout.</li>
 *   <li><b>No {@code --json}, no {@code --out}, stdout is NOT a TTY</b>
 *       → compact {@code {u,t,exp,pin}} JSON + operator-facing trailer
 *       to stdout. The trailer is separated from the JSON by a blank
 *       line so {@code head -n 1} peels off the machine-clean JSON;
 *       prefer {@code --json} for new scripts (see below).</li>
 *   <li><b>No {@code --json}, {@code --out <path>} provided</b> →
 *       512 × 512 PNG written to {@code <path>}; the JSON payload is
 *       also echoed to stdout so the operator can copy-paste it if the
 *       scanner can't read the PNG.</li>
 *   <li><b>{@code --json}, no {@code --out}</b> → compact
 *       {@code {u,t,exp,pin}} JSON on stdout (single line, no QR, no
 *       trailer); operator-facing trailer goes to <b>stderr</b>.</li>
 *   <li><b>{@code --json --out <path>}</b> → compact JSON on stdout
 *       AND the same JSON written to {@code <path>} (NOT a PNG —
 *       {@code --json} suppresses QR generation entirely); operator-
 *       facing trailer to <b>stderr</b>.</li>
 * </ul>
 *
 * <p>The {@code --json} flag is the recommended form for CI / scripted
 * use, replacing the v0.0.7-era {@code | head -n 1} workaround that
 * peeled JSON off the mixed stdout stream.
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

    /**
     * Final-token mode (UC07 Bug D). The tmp file is written 0600 (transient,
     * before atomic rename); 0640 is applied to the canonical path AFTER the
     * rename, so there is no momentary 0600 window on the public name. The
     * group bit lets a non-root operator who happens to be in the
     * {@code ai-sandbox-server} group inspect the token (e.g. when copy-pasting
     * to debug a stalled enrollment) without sudo, while still keeping the
     * world bit off. The server process itself reads the file via the owner
     * bit (it runs as {@code ai-sandbox-server}).
     */
    private static final Set<PosixFilePermission> MODE_640 =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ);

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

    @Option(
            names = "--enrollment-dir",
            description = "Enrollment token store (default /var/lib/ai-sandbox-server/enrollment).")
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

    @Option(
            names = "--out",
            description =
                    "Without --json: write a 512x512 PNG QR to this path (default: ASCII QR to stdout if TTY,"
                            + " JSON-only to stdout otherwise). With --json: write the same compact JSON that"
                            + " goes to stdout to this path (NOT a PNG — --json suppresses QR generation).")
    Path outFile;

    @Option(
            names = "--json",
            description =
                    "Emit machine-clean compact JSON on stdout (single line, no QR, no trailer); operator-facing"
                            + " trailer goes to stderr. With --out, the same JSON is also written to the file"
                            + " (no PNG). Replaces the v0.0.7-era `| head -n 1` workaround for CI / scripted use.")
    boolean json;

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

        Path effectiveEnrollmentDir = enrollmentDir != null ? enrollmentDir : defaultEnrollmentDir();
        // Track whether we will create the dir so we can chown it post-mkdir
        // (chown-after-creation only — if the dir already exists, it was
        // either created by `pki init` and already owned correctly, or by
        // a sibling tool the operator is responsible for. We don't second-
        // guess existing ownership here.)
        boolean willCreateEnrollmentDir = !Files.isDirectory(effectiveEnrollmentDir);
        ensureDir(effectiveEnrollmentDir);

        // UC07 Bug D — resolve ownership once at the top so we can chown
        // both the (possibly freshly-created) dir and the final token
        // file to ai-sandbox-server:ai-sandbox-server. Null on non-POSIX
        // hosts (Windows) or when the system user is not present (unit
        // tests, dev hosts); both branches skip chown silently.
        Ownership ownership = isPosix() ? Ownership.resolve("ai-sandbox-server", "client invite") : null;
        if (ownership != null && willCreateEnrollmentDir) {
            try {
                ownership.chown(effectiveEnrollmentDir);
            } catch (IOException ioe) {
                System.err.println("aisandboxctl client invite: warning — could not chown " + effectiveEnrollmentDir
                        + ": " + ioe.getMessage());
            }
        }

        String pin = serverPin != null ? serverPin : autoDiscoverPin(pkiDir);
        if (pin == null) {
            System.err.println("Cannot resolve server pin: provide --server-pin or place server.crt under " + pkiDir);
            return 2;
        }

        String token = generateHexToken();
        Instant expiresAt = Instant.now().plus(ttlDuration);
        Path file = fileFor(effectiveEnrollmentDir, token);
        writeTokenFile(file, token, name, expiresAt);

        // UC07 Bug D — chown + chmod the final token file. Mode 0640 with
        // owner=ai-sandbox-server makes the file readable by the server
        // process (which runs as that user) and by operators in the
        // ai-sandbox-server group, without exposing it world-wide. Apply
        // AFTER the atomic rename so there is no 0600 → 0640 window on
        // the canonical name (writeTokenFile leaves the path at 0600 from
        // the tmp file). On non-POSIX hosts the setPosixFilePermissions
        // call would throw; we skip both halves in that case.
        if (isPosix()) {
            try {
                Files.setPosixFilePermissions(file, MODE_640);
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX FS — leave as-is.
            }
            if (ownership != null) {
                try {
                    ownership.chown(file);
                } catch (IOException ioe) {
                    System.err.println(
                            "aisandboxctl client invite: warning — could not chown " + file + ": " + ioe.getMessage());
                }
            }
        }

        String payload = buildQrPayload(serverUrl, token, expiresAt, pin);
        // Output policy — see class Javadoc for the full matrix. --json is the
        // explicit machine-clean form (JSON to stdout, trailer to stderr); the
        // pre-v0.0.8 default behaviour is preserved when --json is absent.
        if (json) {
            // --json: stdout gets ONLY the compact JSON. No QR (ASCII or PNG)
            // is emitted on stdout. If --out is provided, the same JSON is
            // also written to the file — explicitly NOT a PNG, since --json
            // suppresses QR generation entirely.
            System.out.println(payload);
            if (outFile != null) {
                java.nio.file.Files.writeString(outFile, payload);
            }
        } else {
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
        }

        // Operator-facing trailer. Goes to stderr under --json so stdout
        // remains a clean JSON channel; otherwise to stdout where it has
        // always lived (compatible with v0.0.7 scripts that read stdout).
        java.io.PrintStream trailer = json ? System.err : System.out;
        trailer.println();
        trailer.println("Invite issued: " + name);
        trailer.println("  token-prefix : " + token.substring(0, FILENAME_PREFIX_LEN));
        trailer.println("  expires-at   : " + expiresAt);
        trailer.println("  file         : " + file);
        return 0;
    }

    /**
     * Default enrollment dir is the FHS-canonical runtime-state location
     * at {@code /var/lib/ai-sandbox-server/enrollment} — matches the
     * {@code application.yaml} default and what {@code aisandboxctl pki init}
     * provisions. Previous releases derived this from the {@code --pki-dir}
     * (sibling {@code ../enrollment}) but tokens are mutable runtime state,
     * not PKI material, so they belong under {@code /var/lib} per FHS.
     */
    static Path defaultEnrollmentDir() {
        return Path.of("/var/lib/ai-sandbox-server/enrollment");
    }

    /** Same probe as {@link PkiInitCommand} — does the default FS support POSIX attribute views? */
    private static boolean isPosix() {
        return java.nio.file.FileSystems.getDefault()
                .supportedFileAttributeViews()
                .contains("posix");
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
