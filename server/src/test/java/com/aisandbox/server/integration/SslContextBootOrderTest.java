package com.aisandbox.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisandbox.server.test.CertFixtures;
import com.aisandbox.server.tls.ReloadableSslContextHolder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * UC03 boot-order regression guard.
 *
 * <p>Before commit {@code b45e7d6}, the production wiring loaded the
 * initial server cert+key inside {@code ServerCertWatcher.@PostConstruct}.
 * {@code NettyServerCustomizer#applyTls} reads
 * {@link ReloadableSslContextHolder#current()} during
 * {@code ReactiveWebServerApplicationContext.onRefresh()} — strictly
 * earlier in the lifecycle than any non-eager {@code @PostConstruct} —
 * so a fresh boot crashed with {@code IllegalStateException: SSL context
 * not yet initialised} and the listener never came up. v0.0.4 and v0.0.5
 * shipped this bug.
 *
 * <p>The fix moves the initial load into the
 * {@code PrimaryConfiguration#reloadableSslContextHolder} bean factory,
 * so the holder is populated before the reactive web server is created.
 *
 * <p>This test boots a real Spring context against
 * {@link CertFixtures}-generated material in a temp PKI tree. The pass
 * criteria:
 *
 * <ol>
 *   <li>Context refreshes without throwing (implicit — JUnit fails the
 *       test class if it cannot wire up).</li>
 *   <li>{@link ReloadableSslContextHolder#current()} returns a non-null
 *       {@code SslContext} — the bean-creation-order invariant.</li>
 *   <li>The reactive web server's TLS listener actually bound (we open
 *       and close a TCP socket against the random port).</li>
 * </ol>
 *
 * <p>Naming note: this class deliberately drops the {@code IT} suffix the
 * other Spring-context classes in this package use. {@code server/build
 * .gradle.kts} excludes {@code **&#47;*IT.class} from {@code :server:test}
 * and only runs them via the gated {@code integrationTest} task when
 * {@code AI_SANDBOX_DIND=1}. This test needs neither Docker nor DinD —
 * it is a plain unit-tier Spring-context test — so the {@code Test}
 * suffix is the semantically correct fit, and it ensures the regression
 * guard actually runs on every PR.
 *
 * <p>Temp-dir strategy: this test populates its scratch tree via
 * {@link Files#createTempDirectory(String, java.nio.file.attribute.FileAttribute[])}
 * inside a static initialiser rather than using JUnit's {@code @TempDir}.
 * Static {@code @TempDir} fields are injected by the {@code TempDirectory}
 * extension's {@code beforeAll} callback, which fires AFTER
 * {@code SpringExtension}'s {@code beforeAll} that boots the context —
 * the {@code @DynamicPropertySource} suppliers would then see null paths
 * and the {@code PropertiesValidationStartupCheck} would fail. Doing it
 * in {@code <clinit>} keeps the dirs ready before any extension runs. A
 * shutdown hook handles cleanup so the project's existing {@code @TempDir}
 * machinery is not bypassed by leaked scratch trees across CI runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SslContextBootOrderTest {

    private static final Path PKI_DIR;
    private static final Path CLIENTS_DIR;
    private static final Path SCRIPTS_DIR;
    private static final Path AUDIT_DIR;
    private static final Path ENROLLMENT_DIR;
    private static final Path SESSIONS_DIR;
    private static final Path SECRETS_DIR;
    private static final Path ROOT;

    static {
        try {
            ROOT = Files.createTempDirectory("ai-sandbox-boot-order-");
            PKI_DIR = Files.createDirectories(ROOT.resolve("pki"));
            CLIENTS_DIR = Files.createDirectories(ROOT.resolve("clients"));
            SCRIPTS_DIR = Files.createDirectories(ROOT.resolve("hostscripts"));
            AUDIT_DIR = Files.createDirectories(ROOT.resolve("audit"));
            ENROLLMENT_DIR = Files.createDirectories(ROOT.resolve("enrollment"));
            SESSIONS_DIR = Files.createDirectories(ROOT.resolve("sessions"));
            SECRETS_DIR = Files.createDirectories(ROOT.resolve("secrets"));

            // Real server cert + key inside pkiDir.
            CertFixtures.writeServerMaterialTo(PKI_DIR, "boot-order-test-server");
            // One client PEM so PropertiesValidationStartupCheck's
            // refuse-empty allowlist policy is satisfied.
            CertFixtures.writeClientPemTo(CLIENTS_DIR, "boot-order-test-client");
            // UC02 host scripts as executable empty shims — only the
            // Files.isRegularFile && Files.isExecutable predicate is
            // checked at boot.
            writeExecutableShim(SCRIPTS_DIR.resolve("spawn.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("attach.sh"));
            writeExecutableShim(SCRIPTS_DIR.resolve("clean.sh"));

            // logback-spring.xml resolves ${ai-sandbox.server.audit.file}
            // during ApplicationEnvironmentPreparedEvent, which fires
            // BEFORE @DynamicPropertySource entries are merged into the
            // Environment. Set the value as a JVM-wide system property
            // so logback's property substitution picks it up via its
            // system-property fallback. Without this the AUDIT_FILE
            // RollingFileAppender tries to open
            // /var/log/ai-sandbox-server/audit.log and crashes the
            // context-startup before any of our beans are touched.
            System.setProperty(
                    "ai-sandbox.server.audit.file",
                    AUDIT_DIR.resolve("audit.log").toString());

            // Cleanup the whole scratch tree on JVM exit.
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
                            "ai-sandbox-boot-order-cleanup"));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        // Port 0 — let the OS pick a free port (no race / no flake).
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
        // The reactive web server itself; setPort(0) in the customizer
        // already drives this from ai-sandbox.server.tls.port, but
        // Spring Boot's WebServerInitializedEvent reflects the bound
        // port into local.server.port for @LocalServerPort to read.
        r.add("server.port", () -> 0);
        // Deliberately do NOT set server.http2.enabled here. Production
        // wiring relies on NettyServerCustomizer#applyTls calling
        // .protocol(HttpProtocol.HTTP11, HttpProtocol.H2) — see commit
        // fe0420f. Setting server.http2.enabled in the test environment
        // would let Spring repopulate the protocol list with H2C and
        // mask the very class of bug Change 3+4 is here to catch (H2C
        // + TLS at bind time). Mirror prod: silence on this property.
        r.add("server.shutdown", () -> "immediate");
        r.add("ai-sandbox.server.shutdown.rest-grace-seconds", () -> 1);
        r.add("ai-sandbox.server.shutdown.total-grace-seconds", () -> 2);
    }

    @Autowired
    ReloadableSslContextHolder holder;

    @LocalServerPort
    int port;

    /**
     * Core invariant: the holder is populated by the time the reactive
     * web server is built. Before the fix, {@code current()} threw
     * {@code IllegalStateException: SSL context not yet initialised}
     * inside the {@code NettyServerCustomizer}'s
     * {@code .secure(sslHolder.current())} call during context refresh,
     * which Spring re-wrapped as a context-startup failure — the test
     * class would fail to wire up at all.
     */
    @Test
    void holder_is_populated_before_the_reactive_web_server_is_built() {
        assertThat(holder).isNotNull();
        assertThat(holder.current()).isNotNull();
    }

    /**
     * Belt-and-braces: the TLS listener actually bound. If
     * {@code NettyServerCustomizer#applyTls} had failed (the pre-fix
     * symptom), the context would have aborted and {@code port} would
     * be {@code -1} or unbound. We connect a plain TCP socket — no TLS
     * handshake — just to confirm the port is open. Connection success
     * is sufficient; we close immediately.
     */
    @Test
    void tls_listener_is_bound_on_a_real_port() throws IOException {
        assertThat(port).isGreaterThan(0);
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            assertThat(s.isConnected()).isTrue();
        }
    }

    /**
     * UC-07 § AC3 ALPN-negotiates-H2 regression guard.
     *
     * <p>v0.0.8 re-enables HTTP/2 over TLS via option (c) — a
     * parent-channel identity walk in
     * {@code ClientIdentityExtractor.channelIdOf}. Under HTTP/2 the
     * per-request {@code Http2StreamChannel} does NOT inherit the parent
     * TLS connection channel's {@code IDENTITY_ATTR} (the registry was
     * keyed at {@code SslHandshakeCompletionEvent} time on the parent's
     * {@code ChannelId}), and a direct lookup on the stream channel's
     * id missed — {@code MtlsEnforcementFilter} 401-ed every H2 request.
     * The v0.0.8 fix detects {@code Http2StreamChannel} in
     * {@code channelIdOf} and walks to {@code streamChannel.parent()} to
     * recover the registry key.
     *
     * <p>With the propagation fix landed,
     * {@code NettyServerCustomizer#applyTls} declares the listener's
     * protocol set as {@code HttpProtocol.HTTP11, HttpProtocol.H2} and
     * {@code ReloadableSslContextHolder.rebuild(...)} advertises
     * {@code "h2", "http/1.1"} via ALPN — {@code h2} first as the
     * server-preferred protocol.
     *
     * <p>This test does a real TLS handshake against the running server,
     * offering BOTH {@code "h2"} AND {@code "http/1.1"} from the client
     * side, and asserts the server-driven negotiation lands on
     * {@code "h2"}. Keeping {@code "http/1.1"} on the client list is
     * intentional — trimming the client offer would make the assertion
     * tautological. The point is to prove the SERVER is the side
     * driving the choice (because the server's ALPN list lists
     * {@code "h2"} ahead of {@code "http/1.1"} as the negotiated
     * preference), not that we asked for {@code "h2"} and got back what
     * we asked for.
     *
     * <p>Failure modes this guards against:
     *
     * <ul>
     *   <li>Someone re-drops {@code "h2"} from the holder's ALPN list
     *       (e.g. a partial revert of the v0.0.8 propagation fix) but
     *       leaves {@code .protocol(HttpProtocol.HTTP11, HttpProtocol.H2)}
     *       on the customizer → ALPN can no longer negotiate {@code h2}
     *       but the listener still advertises it via its protocol set,
     *       producing the protocol-mismatch confusion that motivated
     *       dropping H2 from v0.0.6 in the first place.</li>
     *   <li>Conversely, if someone trims {@code HttpProtocol.H2} from
     *       the customizer's protocol set but leaves {@code "h2"} on
     *       the ALPN list, ALPN selects {@code "h2"} but the listener
     *       can't speak it. Either drift surfaces here as the negotiated
     *       ALPN value being something other than {@code "h2"}.</li>
     * </ul>
     *
     * <p>Reads the ALPN result directly from the SSLEngine via
     * {@link SslHandler#applicationProtocol()} after handshake
     * completion. This is the unambiguous TLS-layer assertion — it
     * sidesteps higher-level HTTP-client behaviour (downgrade rules,
     * version negotiation, etc.) and tests exactly the
     * application-protocol selection the production wiring delivers.
     */
    @Test
    void alpn_negotiates_h2_over_tls() throws Exception {
        assertThat(port).isGreaterThan(0);

        // Client-side SslContext mirroring the v0.0.8 production stack:
        //   - SslProvider.JDK matches the holder.
        //   - InsecureTrustManagerFactory bypasses chain + hostname
        //     verification; chain verification is owned by
        //     AllowlistTrustManagerTest, not this test.
        //   - applicationProtocolConfig offers "h2","http/1.1" — keeping
        //     "http/1.1" on the OFFER list (despite the server now
        //     preferring h2) is what proves the server is the side
        //     driving the choice. Trimming to "h2" only would make the
        //     assertion below tautological.
        SslContext clientCtx = SslContextBuilder.forClient()
                .sslProvider(SslProvider.JDK)
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocolConfig(new ApplicationProtocolConfig(
                        ApplicationProtocolConfig.Protocol.ALPN,
                        ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                        ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                        "h2",
                        "http/1.1"))
                .build();

        EventLoopGroup group = new NioEventLoopGroup(1);
        CompletableFuture<String> negotiated = new CompletableFuture<>();
        try {
            Bootstrap b = new Bootstrap();
            b.group(group).channel(NioSocketChannel.class).handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    SslHandler h = clientCtx.newHandler(ch.alloc(), "127.0.0.1", port);
                    h.handshakeFuture().addListener(f -> {
                        if (f.isSuccess()) {
                            // SslHandler#applicationProtocol returns the
                            // ALPN-selected protocol or null if ALPN was
                            // not negotiated. With the production
                            // ApplicationProtocolConfig on both ends,
                            // null is itself a failure mode.
                            negotiated.complete(h.applicationProtocol());
                        } else {
                            negotiated.completeExceptionally(f.cause());
                        }
                    });
                    ch.pipeline().addLast(h);
                }
            });
            b.connect("127.0.0.1", port).sync();
            String alpn = negotiated.get(15, TimeUnit.SECONDS);
            assertThat(alpn).isEqualTo("h2");
        } finally {
            group.shutdownGracefully();
        }
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
            // Windows path — fall back to the platform's chmod.
            target.toFile().setExecutable(true, false);
        }
    }
}
