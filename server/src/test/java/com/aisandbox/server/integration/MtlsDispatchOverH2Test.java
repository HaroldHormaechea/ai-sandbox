package com.aisandbox.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.test.CertFixtures;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * UC-07 AC1 — HTTP/2 mTLS-dispatch regression guard (H2 sibling of {@link MtlsDispatchTest}).
 *
 * <p>This is the test-first half of the UC-07 cascade. It is authored
 * and run on the pre-fix branch BEFORE any production code in UC-07
 * ships, to prove that the H2 propagation bug — per-stream
 * {@code Http2StreamChannel} instances do not inherit the parent
 * connection's {@code IDENTITY_ATTR}, so
 * {@code ClientIdentityExtractor.channelIdOf()} resolves to a
 * stream-channel id that {@code ActiveConnectionRegistry} has never
 * seen — actually surfaces as an end-to-end failure when an
 * allowlisted client tries to talk HTTP/2 mTLS to {@code /v1/healthz}.
 * Only after this test fails on the pre-fix branch does the developer
 * touch any propagation code.
 *
 * <p>The test mirrors {@link MtlsDispatchTest} structurally — same
 * static {@code <clinit>} tmp-dir layout, same {@code CertFixtures}
 * allowlisted-client setup, same {@code @DynamicPropertySource}
 * wiring, same trust-everything {@code X509ExtendedTrustManager}, same
 * hostname-verification bypass for the SAN-less server cert. The
 * three differences from the H1.1 sibling are:
 *
 * <ol>
 *   <li>The {@code HttpClient} is built with
 *       {@code .version(HttpClient.Version.HTTP_2)} so it offers
 *       {@code h2} via ALPN.</li>
 *   <li>Only the positive "allowlisted client GET /v1/healthz"
 *       assertion is exercised here — the WS-upgrade and unlisted-cert
 *       negative paths are owned by the H1.1 sibling and are not
 *       re-asserted at the H2 tier.</li>
 *   <li>The response's negotiated protocol version is asserted to be
 *       {@code HttpClient.Version.HTTP_2}. This is what prevents a
 *       silent ALPN downgrade from masking a still-broken H2 path: if
 *       the server's customizer only advertises {@code http/1.1}, the
 *       JDK client will quietly fall back, and a 200/503 response on
 *       H1.1 would otherwise let the test pass while H2 stayed
 *       broken.</li>
 * </ol>
 *
 * <p><b>Expected pre-fix behaviour:</b> at least one of the
 * assertions fails. Two failure modes are plausible depending on the
 * exact pre-fix state:
 *
 * <ul>
 *   <li>If the customizer still pins {@code HttpProtocol.HTTP11} and
 *       the ALPN list still omits {@code "h2"}, ALPN negotiates
 *       {@code http/1.1} and the version assertion fails — the body
 *       and status assertions may pass, but the negotiated-protocol
 *       check proves H2 is not actually in play.</li>
 *   <li>If the customizer has been flipped to advertise H2 but the
 *       {@code ClientIdentityExtractor} parent-channel walk is still
 *       missing, the status check fails with {@code 401} and the body
 *       contains {@code "mtls_required"}.</li>
 * </ul>
 *
 * <p>Either failure proves the bug exists. After UC-07's production
 * changes land (parent-channel walk in
 * {@code ClientIdentityExtractor.channelIdOf}, customizer flipped to
 * {@code HttpProtocol.HTTP11, HttpProtocol.H2}, holder ALPN list
 * re-adds {@code "h2"}), all three assertions must pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MtlsDispatchOverH2Test {

    private static final Path ROOT;
    private static final Path PKI_DIR;
    private static final Path CLIENTS_DIR;
    private static final Path SCRIPTS_DIR;
    private static final Path AUDIT_DIR;
    private static final Path ENROLLMENT_DIR;
    private static final Path SESSIONS_DIR;
    private static final Path SECRETS_DIR;

    /** Client material whose PEM is dropped into the allowlist before context start. */
    private static final CertFixtures.ClientMaterial ALLOWLISTED_CLIENT;

    static {
        try {
            ROOT = Files.createTempDirectory("ai-sandbox-mtls-dispatch-h2-");
            PKI_DIR = Files.createDirectories(ROOT.resolve("pki"));
            CLIENTS_DIR = Files.createDirectories(ROOT.resolve("clients"));
            SCRIPTS_DIR = Files.createDirectories(ROOT.resolve("hostscripts"));
            AUDIT_DIR = Files.createDirectories(ROOT.resolve("audit"));
            ENROLLMENT_DIR = Files.createDirectories(ROOT.resolve("enrollment"));
            SESSIONS_DIR = Files.createDirectories(ROOT.resolve("sessions"));
            SECRETS_DIR = Files.createDirectories(ROOT.resolve("secrets"));

            CertFixtures.writeServerMaterialTo(PKI_DIR, "mtls-dispatch-h2-server");

            // Allowlisted client: generate keypair+cert, persist PEM, KEEP the
            // KeyPair so the test can present the matching private key during
            // handshake. CertFixtures.writeClientPemTo() throws away the KeyPair
            // before returning, so we replicate its body inline here.
            ALLOWLISTED_CLIENT = CertFixtures.newClient("mtls-dispatch-h2-allowlisted");
            Files.writeString(CLIENTS_DIR.resolve("allowlisted.crt"), ALLOWLISTED_CLIENT.pem());

            // UC02 host scripts as executable empty shims — only the
            // Files.isRegularFile && Files.isExecutable predicate is
            // checked at boot.
            writeExecutableShim(SCRIPTS_DIR.resolve("spawn.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("attach.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("clean.sh"));

            // Logback property handshake — same dance as SslContextBootOrderTest;
            // logback-spring.xml reads ai-sandbox.server.audit.file during
            // ApplicationEnvironmentPreparedEvent (before @DynamicPropertySource
            // is merged), so set it via a JVM property fallback.
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
                            "ai-sandbox-mtls-dispatch-h2-cleanup"));
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

    /**
     * UC-07 AC1 — an allowlisted client cert MUST be able to reach
     * {@code /v1/healthz} over mTLS WHEN the transport is HTTP/2, and
     * the negotiated protocol must actually be HTTP/2 (no silent ALPN
     * downgrade).
     *
     * <p>Acceptance contract from the use case:
     * <ul>
     *   <li>{@code resp.statusCode()} ∈ {@code {200, 503}}.</li>
     *   <li>{@code resp.body()} does NOT contain {@code "mtls_required"}.</li>
     *   <li>{@code resp.version() == HttpClient.Version.HTTP_2}.</li>
     * </ul>
     *
     * <p>This test is expected to FAIL on the pre-UC-07 branch — that
     * failure is the evidence the dev-team needs before touching
     * propagation code.
     */
    @Test
    void mtls_get_over_h2_with_allowlisted_cert_is_admitted() throws Exception {
        assertThat(port).isGreaterThan(0);

        HttpClient client = httpClientWith(ALLOWLISTED_CLIENT);
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/v1/healthz"))
                .GET()
                .timeout(java.time.Duration.ofSeconds(10))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode())
                .as("UC-07 AC1: an allowlisted client cert over HTTP/2 must NOT be rejected as 401")
                .isNotEqualTo(401);
        assertThat(resp.body())
                .as("UC-07 AC1: response body must not be a Problem-Details mtls_required envelope")
                .doesNotContain("mtls_required");
        assertThat(resp.statusCode())
                .as("UC-07 AC1: healthz returns 200 when Docker is up, 503 when not")
                .isIn(200, 503);
        assertThat(resp.version())
                .as("UC-07 AC1: negotiated protocol must be HTTP/2 — guards against silent ALPN downgrade to http/1.1")
                .isEqualTo(HttpClient.Version.HTTP_2);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Build a {@link HttpClient} that presents {@code mat}'s cert+key
     * during the TLS handshake, trusts the server unconditionally
     * (chain verification is owned by other tests), skips hostname
     * verification (CertFixtures' server cert has no SAN), and
     * advertises {@code h2} via ALPN by setting the client version to
     * {@link HttpClient.Version#HTTP_2}.
     */
    private static HttpClient httpClientWith(CertFixtures.ClientMaterial mat) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("client", mat.keyPair().getPrivate(), new char[0], new Certificate[] {mat.certificate()});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(kmf.getKeyManagers(), new TrustManager[] {TRUST_ALL}, new SecureRandom());

        SSLParameters sslParams = new SSLParameters();
        sslParams.setEndpointIdentificationAlgorithm("");

        return HttpClient.newBuilder()
                .sslContext(sslCtx)
                .sslParameters(sslParams)
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();
    }

    /**
     * Trust-everything extended TrustManager — see
     * {@code SslContextBootOrderTest.TRUST_ALL} for the rationale
     * (using the extended variant disables the JDK's auto-wrap that
     * would otherwise re-introduce hostname verification).
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
}
