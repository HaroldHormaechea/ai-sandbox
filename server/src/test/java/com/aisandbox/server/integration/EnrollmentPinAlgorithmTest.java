package com.aisandbox.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.cli.ClientInviteCommand;
import com.aisandbox.server.enrollment.dto.EnrollmentToken;
import com.aisandbox.server.enrollment.service.EnrollmentTokenStore;
import com.aisandbox.server.pki.PemUtils;
import com.aisandbox.server.test.CertFixtures;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import okhttp3.CertificatePinner;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * UC-09 § AC4 — test-first cascade for the SPKI vs. full-DER cert-pin
 * algorithm bug. Mirrors the UC-07 § AC1 pattern: QA writes this test
 * on the pre-fix branch, runs it, captures the failure trace as
 * evidence that the production-code change is necessary, then the
 * developer lands the fix and the same test goes green WITHOUT any
 * QA-side edit.
 *
 * <h2>What this test proves end-to-end</h2>
 *
 * <p>The server's QR-payload generator
 * ({@code ClientInviteCommand.autoDiscoverPin}) historically computed
 * the {@code pin} field via
 * {@link com.aisandbox.server.pki.PemUtils#fingerprintHex} —
 * {@code sha256(cert.getEncoded())}, the full DER cert hash. The
 * Android client, however, hands that value to OkHttp's
 * {@link okhttp3.CertificatePinner}, which by RFC 7469 / HPKP / OkHttp
 * default verifies against {@code sha256(publicKey.encoded)} — the
 * SubjectPublicKeyInfo (SPKI) hash. The two values are never equal for
 * any certificate, so every enrollment failed with
 * {@code SSLPeerUnverifiedException} on the first
 * {@code POST /v1/enrollment} request. The Android-side
 * {@code observedPinHex = "<bootstrap>"} sentinel masked the symptom
 * through UC-04 / UC-07 / UC-08 manual smoke gates.
 *
 * <h2>Why this test reflects-into {@code autoDiscoverPin}</h2>
 *
 * <p>To exercise the cascade end-to-end without QA touching the test
 * file post-fix, the pin handed to OkHttp MUST come from the
 * production code path being fixed —
 * {@link ClientInviteCommand}{@code .autoDiscoverPin}. The method is
 * {@code private static}; the test reaches it via
 * {@link Method#setAccessible(boolean)} on the reflected handle. This
 * is the same primitive the production Android-side `EnrollmentClient`
 * uses (it receives the pin via the QR `pin` field — a value emitted
 * by exactly this method on the server side — and feeds it verbatim
 * into OkHttp's {@code CertificatePinner}). The reflective hop is a
 * proxy for "what the QR contains."
 *
 * <p>Without this indirection the test would compute the pin via the
 * new {@link PemUtils#spkiFingerprintHex} helper directly, and OkHttp
 * would accept the connection unconditionally — the bug under test is
 * in what the SERVER EMITS, not in OkHttp's verification primitive.
 * Hardcoding the SPKI computation in the test would make the
 * post-Phase-2c green a tautology and would not reproduce the pre-fix
 * failure mode at all (every cert presents the same SPKI on the wire
 * regardless of what {@code autoDiscoverPin} emits to the QR).
 *
 * <h2>Pre-fix expected behaviour (Phase 2b)</h2>
 *
 * <p>{@code autoDiscoverPin} still returns {@code fingerprintHex} —
 * the full-DER hash. This value is fed to OkHttp's
 * {@code CertificatePinner}. OkHttp computes SPKI internally on the
 * presented chain, finds no match, and raises
 * {@code SSLPeerUnverifiedException: Certificate pinning failure! …}
 * on the {@code call.execute()}. The failure trace is the
 * {@code AC4_FAILING_AS_EXPECTED} evidence the team lead captures
 * before clearing the developer to begin Phase 2c.
 *
 * <h2>Post-fix expected behaviour (Phase 2c)</h2>
 *
 * <p>{@code autoDiscoverPin} switches to
 * {@link PemUtils#spkiFingerprintHex}. The reflective call now
 * returns the SPKI hex. OkHttp's pin matches, the connection is
 * admitted, the server mints a PKCS#12, and the response asserts
 * succeed. <b>No test-file edit needed</b> — the cascade is driven
 * purely by the production-code change.
 *
 * <h2>Structural choices</h2>
 *
 * <p>Structure mirrors {@link MtlsDispatchOverH2Test} wholesale (same
 * static {@code <clinit>} tmp-dir layout, same
 * {@code @DynamicPropertySource} wiring, same trust-everything
 * {@link X509ExtendedTrustManager}). Two intentional differences:
 *
 * <ol>
 *   <li>The HTTP client is OkHttp, not the JDK {@code HttpClient} —
 *       because the bug under test is precisely about OkHttp's pin
 *       semantics, the test MUST use the same library the Android
 *       client uses (otherwise the pre-fix failure mode would be
 *       observably absent).</li>
 *   <li>An {@link okhttp3.OkHttpClient.Builder#hostnameVerifier
 *       hostname verifier} that returns {@code true} unconditionally is
 *       supplied. {@code CertFixtures.newServer} mints a self-signed
 *       cert with NO SubjectAltName extension (its CN is
 *       {@code uc09-enroll}, not {@code 127.0.0.1}), so OkHttp's
 *       default hostname verification rejects the connection BEFORE
 *       the pin check ever runs. The bypass is analogous to
 *       {@link MtlsDispatchOverH2Test}'s
 *       {@code jdk.internal.httpclient.disableHostnameVerification}
 *       system-property dance — same root cause, different HTTP
 *       library. With hostname verification bypassed,
 *       {@link okhttp3.CertificatePinner} becomes the sole assertion
 *       under test: a single failure mode keeps the cascade signal
 *       crisp.</li>
 * </ol>
 *
 * <p>The enrollment token is minted inline via a directly-constructed
 * {@link EnrollmentTokenStore} (the class is plain — not Spring-
 * managed — by design so the {@code aisandboxctl client invite} CLI
 * can use it without dragging in a Spring context, per its class
 * Javadoc). This avoids wiring the full UC-04 mint pipeline just to
 * obtain a redeemable token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EnrollmentPinAlgorithmTest {

    private static final Path ROOT;
    private static final Path PKI_DIR;
    private static final Path CLIENTS_DIR;
    private static final Path SCRIPTS_DIR;
    private static final Path AUDIT_DIR;
    private static final Path ENROLLMENT_DIR;
    private static final Path SESSIONS_DIR;
    private static final Path SECRETS_DIR;

    /**
     * Server material whose cert is what the OkHttp client must pin
     * against. Captured at static-init time so the test method can
     * reflect the SAME {@code pki/server.crt} the Spring Boot context
     * loads at {@code /v1/enrollment} bind time.
     */
    @SuppressWarnings("unused") // kept for symmetry with MtlsDispatchOverH2Test + future use
    private static final CertFixtures.ServerMaterial SERVER;

    static {
        try {
            ROOT = Files.createTempDirectory("ai-sandbox-uc09-enroll-pin-");
            PKI_DIR = Files.createDirectories(ROOT.resolve("pki"));
            CLIENTS_DIR = Files.createDirectories(ROOT.resolve("clients"));
            SCRIPTS_DIR = Files.createDirectories(ROOT.resolve("hostscripts"));
            AUDIT_DIR = Files.createDirectories(ROOT.resolve("audit"));
            ENROLLMENT_DIR = Files.createDirectories(ROOT.resolve("enrollment"));
            SESSIONS_DIR = Files.createDirectories(ROOT.resolve("sessions"));
            SECRETS_DIR = Files.createDirectories(ROOT.resolve("secrets"));

            SERVER = CertFixtures.writeServerMaterialTo(PKI_DIR, "uc09-enroll");

            // Seed the allowlist with one disposable client PEM so the
            // refuse-to-start policy in PropertiesValidationStartupCheck
            // ("Allowlist directory is empty") doesn't block context
            // boot. /v1/enrollment is mTLS-EXEMPT (the whole point of
            // this test), so the allowlist content is never consulted on
            // the request path under test — but the boot check fires
            // anyway, so it MUST be non-empty.
            CertFixtures.writeClientPemTo(CLIENTS_DIR, "uc09-bootstrap-client");

            // UC02 host scripts as executable empty shims — only the
            // Files.isRegularFile && Files.isExecutable predicate is
            // checked at boot. Mirrors MtlsDispatchOverH2Test.
            writeExecutableShim(SCRIPTS_DIR.resolve("spawn.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("attach.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("clean.sh"));

            // Logback property handshake — logback-spring.xml reads
            // ai-sandbox.server.audit.file during
            // ApplicationEnvironmentPreparedEvent (before
            // @DynamicPropertySource is merged), so set it via a JVM
            // property fallback. Mirrors MtlsDispatchOverH2Test.
            System.setProperty(
                    "ai-sandbox.server.audit.file",
                    AUDIT_DIR.resolve("audit.log").toString());

            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                try (var paths = Files.walk(ROOT)) {
                                    paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                                        try {
                                            Files.deleteIfExists(p);
                                        } catch (IOException ignored) {
                                            // best-effort cleanup
                                        }
                                    });
                                } catch (IOException ignored) {
                                    // best-effort cleanup
                                }
                            },
                            "ai-sandbox-uc09-enroll-pin-cleanup"));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("ai-sandbox.server.tls.port", () -> 0);
        r.add("ai-sandbox.server.tls.bind-address", () -> "127.0.0.1");
        r.add("ai-sandbox.server.pki.dir", PKI_DIR::toString);
        r.add("ai-sandbox.server.clients.dir", CLIENTS_DIR::toString);
        r.add("ai-sandbox.server.hostscripts.repo-root", SCRIPTS_DIR::toString);
        r.add(
                "ai-sandbox.server.audit.file",
                () -> AUDIT_DIR.resolve("audit.log").toString());
        r.add("ai-sandbox.server.enrollment.dir", ENROLLMENT_DIR::toString);
        r.add("ai-sandbox.server.sessions.host-state-root", SESSIONS_DIR::toString);
        r.add("ai-sandbox.server.secrets.dir", SECRETS_DIR::toString);
        r.add("server.port", () -> 0);
        r.add("server.shutdown", () -> "immediate");
        r.add("ai-sandbox.server.shutdown.rest-grace-seconds", () -> 1);
        r.add("ai-sandbox.server.shutdown.total-grace-seconds", () -> 2);
    }

    @LocalServerPort
    int port;

    @Test
    void mtls_enrollment_post_succeeds_when_okhttp_pinner_is_seeded_from_qr_pin() throws Exception {
        assertThat(port).isGreaterThan(0);

        // 1. Mint an enrollment token directly via EnrollmentTokenStore.
        //    This is the same primitive `aisandboxctl client invite` uses
        //    on the CLI side — no Spring context needed (the store is
        //    plain by design, per its class Javadoc).
        EnrollmentTokenStore store = new EnrollmentTokenStore(ENROLLMENT_DIR);
        store.ensureDir();
        String tokenHex = newTokenHex();
        store.save(new EnrollmentToken(
                tokenHex, "uc09-enroll-client", Instant.now().plus(Duration.ofMinutes(5))));

        // 2. Obtain the pin via the EXACT production code path —
        //    ClientInviteCommand.autoDiscoverPin(pkiDir). On the pre-fix
        //    branch this returns the full-DER hash (BUG); on the
        //    post-Phase-2c branch this returns the SPKI hash (fixed).
        //    The reflective hop avoids changing the method's visibility
        //    or refactoring it into an exposed helper — see class
        //    Javadoc § "Why this test reflects-into autoDiscoverPin".
        String productionPin = invokeAutoDiscoverPin(PKI_DIR);
        assertThat(productionPin)
                .as("UC-09 § AC4 — production autoDiscoverPin must return a non-null hex pin "
                        + "(server.crt is present in PKI_DIR, so the file-existence guard inside "
                        + "autoDiscoverPin should not return null)")
                .isNotNull()
                .matches("^[0-9a-f]{64}$");

        // 3. Convert the production-emitted pin into OkHttp's pin format
        //    (sha256/<base64>). Pre-fix: this base64 is of the full-DER
        //    hash bytes (wrong); post-fix: this base64 is of the SPKI
        //    hash bytes (right).
        String okhttpPin = "sha256/" + Base64.getEncoder().encodeToString(hexToBytes(productionPin));

        // 4. Build an OkHttp client that:
        //    a) trusts everything (chain verification is not what this
        //       test exercises — see TRUST_ALL below);
        //    b) bypasses hostname verification (CertFixtures-minted
        //       certs have no SubjectAltName — see class Javadoc);
        //    c) pins against the production-emitted pin via
        //       CertificatePinner — THE assertion under test.
        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(null, new javax.net.ssl.TrustManager[] {TRUST_ALL}, new SecureRandom());

        CertificatePinner pinner =
                new CertificatePinner.Builder().add("127.0.0.1", okhttpPin).build();

        OkHttpClient client = new OkHttpClient.Builder()
                .sslSocketFactory(sslCtx.getSocketFactory(), TRUST_ALL)
                .hostnameVerifier((hostname, session) -> true)
                .certificatePinner(pinner)
                .connectTimeout(Duration.ofSeconds(5))
                .callTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(10))
                .build();

        // 5. POST /v1/enrollment. On the pre-fix branch the pin OkHttp
        //    verifies against (SPKI of the presented cert) does not match
        //    the pinner's value (full-DER of the same cert), so
        //    Response#execute throws SSLPeerUnverifiedException. On the
        //    post-fix branch the two values match, the server mints a
        //    PKCS#12, and the response assertions succeed.
        String url = "https://127.0.0.1:" + port + "/v1/enrollment";
        RequestBody body = RequestBody.create(
                ("{\"token\":\"" + tokenHex + "\"}").getBytes(), MediaType.parse("application/json"));
        Request request = new Request.Builder().url(url).post(body).build();

        try (Response response = client.newCall(request).execute()) {
            assertThat(response.code())
                    .as("UC-09 § AC4 — POST /v1/enrollment must return 201 when the pin handed "
                            + "to OkHttp by ClientInviteCommand.autoDiscoverPin matches OkHttp's "
                            + "SPKI verification. Pre-fix this assertion is unreachable (execute() "
                            + "throws SSLPeerUnverifiedException above); post-Phase-2c this is the "
                            + "end-to-end algorithm-contract proof.")
                    .isEqualTo(201);

            String contentType = response.header("Content-Type");
            assertThat(contentType)
                    .as("UC-09 § AC4 — enrollment response is a PKCS#12 binary stream")
                    .isNotNull()
                    .startsWith("application/octet-stream");

            byte[] p12Bytes = response.body().bytes();
            assertThat(p12Bytes).as("UC-09 § AC4 — response body is non-empty").isNotEmpty();

            // 6. Parse the response body as a PKCS#12. UC-04 contracts
            //    the empty transport passphrase (confirmed via the
            //    X-AI-Sandbox-P12-Passphrase response header — but we
            //    only assert the body-side invariants here; header
            //    assertions belong in dedicated UC-04 tests).
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(p12Bytes), new char[0]);

            // Exactly one alias.
            int aliasCount = 0;
            String onlyAlias = null;
            for (Enumeration<String> e = ks.aliases(); e.hasMoreElements(); ) {
                onlyAlias = e.nextElement();
                aliasCount++;
            }
            assertThat(aliasCount)
                    .as("UC-09 § AC4 — minted .p12 contains exactly one alias")
                    .isEqualTo(1);
            assertThat(onlyAlias)
                    .as("UC-09 § AC4 — single alias must be non-null")
                    .isNotNull();

            // That alias is a private-key entry.
            assertThat(ks.isKeyEntry(onlyAlias))
                    .as("UC-09 § AC4 — alias '%s' must be a private-key entry", onlyAlias)
                    .isTrue();

            // Leaf cert CN equals the requested client name.
            X509Certificate leaf = (X509Certificate) ks.getCertificate(onlyAlias);
            assertThat(leaf).as("UC-09 § AC4 — leaf cert resolvable from alias").isNotNull();
            assertThat(PemUtils.extractCommonName(leaf))
                    .as("UC-09 § AC4 — minted leaf cert CN matches the enrollment-token client name")
                    .isEqualTo("uc09-enroll-client");
        } finally {
            // Quiet OkHttp's executor so a JVM-shared :server:test run
            // shuts down cleanly. Each call's connection pool sticks
            // around until the dispatcher's executor is shut down or its
            // idle timeout elapses; calling shutdown explicitly here
            // keeps the test JVM promptly idle for the next class.
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
            try {
                client.dispatcher().executorService().awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Reflectively invoke
     * {@link ClientInviteCommand}{@code .autoDiscoverPin(Path)} —
     * deliberately a {@code private static} method so that the public
     * picocli surface stays narrow. Reaching through reflection is the
     * cheapest way to exercise the EXACT production code path that
     * computes the QR pin, without widening visibility just for
     * testability.
     *
     * <p>If a future refactor promotes the method to package-private or
     * extracts the logic into a service, this helper goes away; the
     * test's assertion contract is unchanged.
     */
    private static String invokeAutoDiscoverPin(Path pkiDir) throws Exception {
        Method m = ClientInviteCommand.class.getDeclaredMethod("autoDiscoverPin", Path.class);
        m.setAccessible(true);
        return (String) m.invoke(null, pkiDir);
    }

    /**
     * 64 hex chars (256 bits) — at the top of UC-04's token entropy
     * range, comfortably above the AC34 minimum of 32 chars in
     * {@link com.aisandbox.server.api.dto.ApiDtos.EnrollmentRequest}'s
     * {@code @Pattern}.
     */
    private static String newTokenHex() {
        return (UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""))
                .toLowerCase();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    private static void writeExecutableShim(Path target) throws IOException {
        Files.writeString(target, "#!/bin/sh\nexit 0\n");
        Set<PosixFilePermission> perms = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE);
        try {
            Files.setPosixFilePermissions(target, perms);
        } catch (UnsupportedOperationException uoe) {
            target.toFile().setExecutable(true, false);
        }
    }

    /**
     * Trust-everything extended TrustManager — see
     * {@code MtlsDispatchOverH2Test.TRUST_ALL} for the rationale (the
     * extended variant disables the JDK's auto-wrap that would
     * otherwise re-introduce hostname verification). The trust check
     * is intentionally NOT what this test exercises — the assertion
     * under test is OkHttp's CertificatePinner, supplied separately
     * via {@link OkHttpClient.Builder#certificatePinner}.
     */
    private static final X509ExtendedTrustManager TRUST_ALL = new X509ExtendedTrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // accept anything — chain verification is not what this test exercises
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) {
            // accept anything — chain verification is not what this test exercises
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            // accept anything — chain verification is not what this test exercises
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // accept anything — chain verification is not what this test exercises
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) {
            // accept anything — chain verification is not what this test exercises
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            // accept anything — chain verification is not what this test exercises
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };
}
