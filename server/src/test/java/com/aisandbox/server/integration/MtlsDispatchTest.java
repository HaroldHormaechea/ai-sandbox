package com.aisandbox.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aisandbox.server.test.CertFixtures;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Round-4 mTLS-dispatch regression guard.
 *
 * <p>Boots the full Spring context against a temp PKI tree, then exercises
 * three end-to-end paths the v0.0.4/0.5/0.6 release cascade kept missing:
 *
 * <ol>
 *   <li>An HTTPS {@code GET /v1/healthz} from an allowlisted client cert
 *       must reach the controller — {@code MtlsEnforcementFilter} must
 *       NOT reject it as 401 {@code mtls_required}. Either 200 (Docker
 *       reachable) or 503 (Docker absent) is acceptable; both prove the
 *       request crossed the filter chain.</li>
 *   <li>A WSS upgrade to {@code /v1/sessions/0/stream} from an
 *       allowlisted client cert must NOT be rejected at the filter chain
 *       with 401. Session 0 may not exist; the upgrade may be refused by
 *       the WS handler (404, 4xx, 5xx) — any non-401 outcome proves the
 *       mTLS gate let the upgrade through, which is the only thing being
 *       asserted here.</li>
 *   <li>An HTTPS {@code GET /v1/healthz} from a client whose cert is
 *       NOT on the allowlist must be rejected at the TLS layer (handshake
 *       exception or I/O failure post-handshake under TLS 1.3's
 *       post-handshake client-cert validation), NOT pass through to the
 *       application and reach the controller.</li>
 * </ol>
 *
 * <p>This test is written as a TDD-style failing regression guard for
 * the Round-4 cascade: the developer is unblocked to fix the underlying
 * identity-lookup bug ONLY after QA has confirmed the test fails on the
 * current branch HEAD.
 *
 * <p>Naming + temp-dir conventions mirror {@link SslContextBootOrderTest}
 * (drop the {@code IT} suffix so {@code :server:test} actually runs it;
 * populate the scratch tree in a static initialiser so it exists before
 * {@code SpringExtension}'s {@code beforeAll} resolves the
 * {@code @DynamicPropertySource} suppliers).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MtlsDispatchTest {

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

    /** Client material whose PEM is NOT in the allowlist — used for the negative path. */
    private static final CertFixtures.ClientMaterial UNLISTED_CLIENT;

    static {
        try {
            ROOT = Files.createTempDirectory("ai-sandbox-mtls-dispatch-");
            PKI_DIR = Files.createDirectories(ROOT.resolve("pki"));
            CLIENTS_DIR = Files.createDirectories(ROOT.resolve("clients"));
            SCRIPTS_DIR = Files.createDirectories(ROOT.resolve("hostscripts"));
            AUDIT_DIR = Files.createDirectories(ROOT.resolve("audit"));
            ENROLLMENT_DIR = Files.createDirectories(ROOT.resolve("enrollment"));
            SESSIONS_DIR = Files.createDirectories(ROOT.resolve("sessions"));
            SECRETS_DIR = Files.createDirectories(ROOT.resolve("secrets"));

            CertFixtures.writeServerMaterialTo(PKI_DIR, "mtls-dispatch-server");

            // Allowlisted client: generate keypair+cert, persist PEM, KEEP the
            // KeyPair so the test can present the matching private key during
            // handshake. CertFixtures.writeClientPemTo() throws away the KeyPair
            // before returning, so we replicate its body inline here.
            ALLOWLISTED_CLIENT = CertFixtures.newClient("mtls-dispatch-allowlisted");
            Files.writeString(CLIENTS_DIR.resolve("allowlisted.crt"), ALLOWLISTED_CLIENT.pem());

            // Unlisted client: minted but its PEM is NOT dropped into CLIENTS_DIR.
            UNLISTED_CLIENT = CertFixtures.newClient("mtls-dispatch-rogue");

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
                            "ai-sandbox-mtls-dispatch-cleanup"));
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
     * Round-4a primary assertion: an allowlisted client cert MUST be
     * able to reach {@code /v1/healthz} over mTLS. The current branch
     * HEAD ({@code a91d43e}) rejects every request with 401
     * {@code mtls_required} because
     * {@code NettyServerCustomizer.IdentityCapturingHandler} looks up
     * the {@code SslHandler} by name {@code "ssl"} — a name
     * Reactor-Netty 1.2.x does NOT use for its pipeline-installed
     * SslHandler. The handler exists in the pipeline; the lookup just
     * misses it, identity registration silently fails, and the L7
     * filter rejects.
     *
     * <p>Accept 200 (Docker reachable) OR 503 (Docker absent) — both
     * prove the request reached the controller. The response body must
     * NOT contain {@code "mtls_required"} either way.
     */
    @Test
    void mtls_get_with_allowlisted_cert_is_admitted() throws Exception {
        assertThat(port).isGreaterThan(0);

        HttpClient client = httpClientWith(ALLOWLISTED_CLIENT);
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/v1/healthz"))
                .GET()
                .timeout(java.time.Duration.ofSeconds(10))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode())
                .as("v0.0.6 mTLS dispatch: an allowlisted client cert must NOT be rejected as 401")
                .isNotEqualTo(401);
        assertThat(resp.body())
                .as("v0.0.6 mTLS dispatch: response body must not be a Problem-Details mtls_required envelope")
                .doesNotContain("mtls_required");
        assertThat(resp.statusCode())
                .as("v0.0.6 mTLS dispatch: healthz returns 200 when Docker is up, 503 when not")
                .isIn(200, 503);
    }

    /**
     * Round-4a secondary assertion: an allowlisted client cert must be
     * able to reach the WebSocket upgrade path. Same underlying bug,
     * different surface — the WS handshake is an HTTP/1.1 GET with
     * {@code Upgrade: websocket}; if {@code MtlsEnforcementFilter} runs
     * before the WS handler (it does, per {@code @Order} at
     * {@code HIGHEST_PRECEDENCE + 10}), a missing identity rejects the
     * upgrade with 401 before the session router ever sees it.
     *
     * <p>Session {@code 0} likely doesn't exist; the WS handler may
     * close, 4xx, or 5xx. The only thing this test asserts is the
     * upgrade response is NOT 401 — anything else proves the mTLS gate
     * let the request through.
     */
    @Test
    void mtls_websocket_upgrade_with_allowlisted_cert_is_admitted() throws Exception {
        assertThat(port).isGreaterThan(0);

        HttpClient client = httpClientWith(ALLOWLISTED_CLIENT);
        URI ws = URI.create("wss://127.0.0.1:" + port + "/v1/sessions/0/stream");
        WebSocket.Listener listener = new WebSocket.Listener() {};

        try {
            WebSocket sock =
                    client.newWebSocketBuilder().buildAsync(ws, listener).get(10, TimeUnit.SECONDS);
            // The upgrade succeeded — close cleanly. Anything that
            // reaches this branch is already a non-401 outcome.
            sock.abort();
        } catch (ExecutionException | CompletionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof WebSocketHandshakeException wshe) {
                int status = wshe.getResponse().statusCode();
                assertThat(status)
                        .as("v0.0.6 mTLS dispatch: WS upgrade response status must not be 401 for allowlisted client")
                        .isNotEqualTo(401);
            } else if (cause instanceof IOException) {
                // Server hung up post-101 (e.g. WS handler rejected
                // unknown session 0). The handshake response was
                // non-401; that's already proven by us reaching the
                // post-handshake stream lifecycle.
                // No assertion needed — getting here means MtlsEnforcementFilter
                // did NOT reject with 401 at the upgrade stage.
            } else {
                throw ee;
            }
        }
    }

    /**
     * Round-4a negative case: a client whose cert is NOT in the
     * allowlist must be rejected at the TLS layer.
     * {@code AllowlistTrustManager.checkClientTrusted} throws
     * {@code CertificateException} when the leaf's SHA-256 fingerprint
     * is absent from the allowlist snapshot. Under TLS 1.3 the
     * client-cert validation runs post-handshake, so the rejection may
     * surface as a delayed I/O failure rather than a clean
     * {@code SSLHandshakeException} — accept any
     * {@link SSLException} / {@link IOException} subtype.
     *
     * <p>What this test specifically rules out: a regression that
     * pushes the cert-allowlist check from L5 (TrustManager) up to L7
     * (filter), which would change the failure mode from
     * connection-reset to 401 — semantically very different and
     * silently looser, because L7 handlers can be misordered.
     */
    @Test
    void mtls_get_with_unlisted_cert_is_rejected_at_tls() throws Exception {
        assertThat(port).isGreaterThan(0);

        HttpClient client = httpClientWith(UNLISTED_CLIENT);
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/v1/healthz"))
                .GET()
                .timeout(java.time.Duration.ofSeconds(10))
                .build();

        assertThatThrownBy(() -> client.send(req, HttpResponse.BodyHandlers.discarding()))
                .as("v0.0.6 mTLS dispatch: unlisted client cert must be rejected by the TrustManager at L5")
                .isInstanceOfAny(SSLException.class, IOException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Build a {@link HttpClient} that presents {@code mat}'s cert+key
     * during the TLS handshake, trusts the server unconditionally
     * (chain verification is owned by other tests), and skips hostname
     * verification (CertFixtures' server cert has no SAN).
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
                .version(HttpClient.Version.HTTP_1_1)
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
