package com.aisandbox.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.aisandbox.server.test.CertFixtures;
import com.aisandbox.server.tls.ReloadableSslContextHolder;
import io.netty.handler.ssl.SslContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * UC-07 § AC6 — full {@code ServerCertWatcher} hot-reload coverage in
 * the unit tier.
 *
 * <p>v0.0.7 shipped this class as an empty
 * {@code @EnabledIfEnvironmentVariable AI_SANDBOX_DIND=1} stub —
 * neither the local {@code :server:test} run nor any non-DinD CI lane
 * exercised the cert-rotation contract end-to-end. UC03 § AC14
 * specifies that (a) a write to the watched PKI dir rebuilds the
 * holder's {@link SslContext} within the debounce window, and (b)
 * in-flight TLS sessions retain the cert chain they handshook with;
 * the rebuild affects only NEW handshakes. v0.0.8 fulfils AC6 by
 * rewriting this class as a real {@link SpringBootTest} on
 * {@code RANDOM_PORT} so the production
 * {@link com.aisandbox.server.tls.ServerCertWatcher} actually runs
 * against a temp PKI tree, and renames the file to {@code *Test} so
 * the default {@code :server:test} task picks it up on every PR.
 *
 * <p>Synchronisation uses {@link org.awaitility.Awaitility} polling —
 * no {@code Thread.sleep} in the test code. The watcher's internal
 * debounce ({@code 500 ms} per
 * {@code ServerCertWatcher.DEBOUNCE_MS}) is the natural barrier; the
 * await timeout ({@code 2 s}) sits above it with margin for CI noise.
 *
 * <p>Static {@code <clinit>} tmp-dir layout mirrors
 * {@link SslContextBootOrderTest} and {@link MtlsDispatchTest} — the
 * {@code @DynamicPropertySource} suppliers run before
 * {@code SpringExtension}'s {@code beforeAll}, so the PKI / clients /
 * audit / etc. scratch dirs must exist by class-load time, which
 * rules out JUnit's {@code @TempDir}. A JVM shutdown hook cleans the
 * tree.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerCertHotReloadTest {

    private static final Path ROOT;
    private static final Path PKI_DIR;
    private static final Path CLIENTS_DIR;
    private static final Path SCRIPTS_DIR;
    private static final Path AUDIT_DIR;
    private static final Path ENROLLMENT_DIR;
    private static final Path SESSIONS_DIR;
    private static final Path SECRETS_DIR;

    /** Client material whose PEM is dropped into the allowlist for the in-flight test. */
    private static final CertFixtures.ClientMaterial ALLOWLISTED_CLIENT;

    static {
        try {
            ROOT = Files.createTempDirectory("ai-sandbox-cert-rotate-");
            PKI_DIR = Files.createDirectories(ROOT.resolve("pki"));
            CLIENTS_DIR = Files.createDirectories(ROOT.resolve("clients"));
            SCRIPTS_DIR = Files.createDirectories(ROOT.resolve("hostscripts"));
            AUDIT_DIR = Files.createDirectories(ROOT.resolve("audit"));
            ENROLLMENT_DIR = Files.createDirectories(ROOT.resolve("enrollment"));
            SESSIONS_DIR = Files.createDirectories(ROOT.resolve("sessions"));
            SECRETS_DIR = Files.createDirectories(ROOT.resolve("secrets"));

            // Real initial server cert + key.
            CertFixtures.writeServerMaterialTo(PKI_DIR, "cert-rotate-initial-server");

            // mTLS client material — written into the allowlist so the
            // in-flight test can complete a real handshake against the
            // production server pipeline (REQUIRE client auth).
            ALLOWLISTED_CLIENT = CertFixtures.newClient("cert-rotate-allowlisted-client");
            Files.writeString(CLIENTS_DIR.resolve("allowlisted.crt"), ALLOWLISTED_CLIENT.pem());

            writeExecutableShim(SCRIPTS_DIR.resolve("spawn.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("attach.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("clean.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("lifecycle.sh"));

            // Same logback property handshake as the sibling tests — the
            // audit appender resolves ai-sandbox.server.audit.file during
            // ApplicationEnvironmentPreparedEvent (before
            // @DynamicPropertySource is merged), so set the value as a
            // JVM-wide system property fallback.
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
                            "ai-sandbox-cert-rotate-cleanup"));
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

    @Autowired
    ReloadableSslContextHolder holder;

    @LocalServerPort
    int port;

    /**
     * UC03 § AC14 (rebuild side). The watcher rebuilds the holder's
     * {@link SslContext} when {@code server.crt} / {@code server.key}
     * change on disk, within the debounce window.
     *
     * <p>Mechanics:
     *
     * <ol>
     *   <li>Capture {@code holder.current()} as the pre-rotation
     *       reference.</li>
     *   <li>Write fresh material via
     *       {@link CertFixtures#writeServerMaterialTo}. The helper
     *       writes {@code server.crt} then {@code server.key} in
     *       quick succession; both file events fall inside the same
     *       debounce window, so the watcher rebuilds once.</li>
     *   <li>{@code await()} until {@code holder.current() !=
     *       originalCtx} — instance inequality is sufficient since
     *       {@code ReloadableSslContextHolder.rebuild} stores a freshly
     *       constructed {@code SslContext} on every successful
     *       reload.</li>
     * </ol>
     *
     * <p>The await ceiling is 2 s — well above the watcher's 500 ms
     * debounce plus a comfortable margin for CI scheduler jitter.
     */
    @Test
    void watcher_rebuilds_holder_within_debounce_when_cert_files_change() throws Exception {
        SslContext originalCtx = holder.current();
        assertThat(originalCtx)
                .as("AC14 precondition: holder is populated by the time the test method runs")
                .isNotNull();

        // Rotate the on-disk material. Writes server.crt + server.key
        // back-to-back; both ENTRY_MODIFY events land inside the
        // watcher's 500 ms debounce, so the rebuild runs exactly once
        // against the new pair.
        CertFixtures.writeServerMaterialTo(PKI_DIR, "cert-rotate-rotated-server-a");

        await().atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> holder.current() != originalCtx);

        assertThat(holder.current())
                .as("AC14: watcher must publish a new SslContext after the on-disk material changes")
                .isNotNull()
                .isNotSameAs(originalCtx);
    }

    /**
     * UC03 § AC14 (in-flight side). A TLS session established BEFORE
     * a server-cert rotation MUST keep its original cert chain —
     * mid-flight connections are not torn down or re-handshook by the
     * rebuild. Only NEW handshakes see the rotated material.
     *
     * <p>Mechanics:
     *
     * <ol>
     *   <li>Open a real mTLS {@link SSLSocket} against the running
     *       Netty listener using the production
     *       {@link com.aisandbox.server.clients.service.ClientAllowlistService}
     *       allowlist (the static initialiser dropped a client PEM in
     *       {@code CLIENTS_DIR}). Complete the handshake; capture the
     *       peer cert chain.</li>
     *   <li>Capture {@code holder.current()} as the rotation
     *       reference.</li>
     *   <li>Rotate the server cert on disk and {@code await()} the
     *       holder swap (same barrier as test 1).</li>
     *   <li>Re-query the held {@link SSLSession}'s peer cert chain
     *       and assert byte-identical to the pre-rotation chain. The
     *       {@code SSLSession} object is the JDK-owned handshake
     *       snapshot, but the assertion is the explicit AC14 contract
     *       — if the server rebuilt the session under us (e.g. via
     *       TLS 1.3 re-handshake or session-ticket invalidation), the
     *       new chain would differ.</li>
     * </ol>
     *
     * <p>The hostname-verification bypass: {@link SSLSocket} does NOT
     * do endpoint identification by default, so the SAN-less server
     * cert from {@link CertFixtures} handshakes cleanly without
     * needing the JDK HttpClient workaround
     * ({@code jdk.internal.httpclient.disableHostnameVerification})
     * that {@code MtlsDispatchOverH2Test} uses.
     *
     * <p>The {@code TRUST_ALL} extended trust manager skips chain
     * validation (we're testing cert rotation, not chain semantics).
     */
    @Test
    void inflight_tls_session_retains_original_cert_across_rotation() throws Exception {
        assertThat(port).isGreaterThan(0);

        try (SSLSocket sock = openMtlsSocket("127.0.0.1", port, ALLOWLISTED_CLIENT)) {
            sock.startHandshake();
            SSLSession session = sock.getSession();
            Certificate[] originalChain = session.getPeerCertificates();
            assertThat(originalChain)
                    .as("AC14: precondition — handshake produced a peer cert chain")
                    .isNotEmpty();
            byte[] originalLeafBytes = ((X509Certificate) originalChain[0]).getEncoded();

            SslContext originalCtx = holder.current();
            assertThat(originalCtx).isNotNull();

            // Rotate while the held socket is still open.
            CertFixtures.writeServerMaterialTo(PKI_DIR, "cert-rotate-rotated-server-b");

            await().atMost(Duration.ofSeconds(2))
                    .pollInterval(Duration.ofMillis(50))
                    .until(() -> holder.current() != originalCtx);

            // Held SSLSession's peer cert chain must still report the
            // ORIGINAL leaf cert byte-for-byte. Re-query through
            // sock.getSession() (not the cached session reference) so
            // the assertion catches a hypothetical re-handshake that
            // would replace the SSLSession under the hood.
            Certificate[] postRotationChain = sock.getSession().getPeerCertificates();
            assertThat(postRotationChain)
                    .as("AC14: in-flight session must still have a peer cert chain post-rotation")
                    .isNotEmpty();
            byte[] postRotationLeafBytes = ((X509Certificate) postRotationChain[0]).getEncoded();

            assertThat(postRotationLeafBytes)
                    .as("AC14: held session's leaf cert MUST be byte-identical to the pre-rotation leaf"
                            + " (the rebuild affects only NEW handshakes; in-flight sessions are immutable)")
                    .isEqualTo(originalLeafBytes);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Build an {@link SSLSocket} that presents the supplied
     * {@code ClientMaterial} during the TLS handshake, trusts the
     * server unconditionally via {@link #TRUST_ALL}, and skips
     * hostname verification by virtue of {@code SSLSocket}'s default
     * (no endpoint identification algorithm set).
     */
    private static SSLSocket openMtlsSocket(String host, int port, CertFixtures.ClientMaterial mat) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("client", mat.keyPair().getPrivate(), new char[0], new Certificate[] {mat.certificate()});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(kmf.getKeyManagers(), new TrustManager[] {TRUST_ALL}, new SecureRandom());

        return (SSLSocket) sslCtx.getSocketFactory().createSocket(host, port);
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
