package com.aisandbox.server.config;

import com.aisandbox.server.clients.service.ClientAllowlistService;
import com.aisandbox.server.enrollment.service.EnrollmentTokenStore;
import com.aisandbox.server.tls.ReloadableSslContextHolder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Bean wiring for objects that aren't simply {@code @Component}-annotated
 * (custom constructors, JCE state, etc.).
 */
@Configuration
public class PrimaryConfiguration {

    /**
     * Build the {@link ReloadableSslContextHolder} and populate it with the
     * initial server cert+key in the bean factory itself, BEFORE the reactive
     * web server is created.
     *
     * <p>{@code NettyServerCustomizer#applyTls} calls
     * {@link ReloadableSslContextHolder#current()} inside
     * {@code ReactiveWebServerApplicationContext.onRefresh()}, which runs
     * during context refresh — strictly earlier than any
     * {@code SmartLifecycle#start()} callback and earlier than any
     * {@code @PostConstruct} on a non-eager bean such as
     * {@code ServerCertWatcher}. Loading the cert here guarantees the
     * customizer sees an initialised context and avoids the
     * {@code IllegalStateException: SSL context not yet initialised}
     * crash-loop on fresh boots.
     */
    @Bean
    @Profile("!docs-only")
    public ReloadableSslContextHolder reloadableSslContextHolder(
            ClientAllowlistService allowlist, ServerProperties props) {
        ReloadableSslContextHolder holder = new ReloadableSslContextHolder(allowlist);
        Path pkiDir = props.pki().dir();
        Path crt = pkiDir.resolve("server.crt");
        Path key = pkiDir.resolve("server.key");
        if (!Files.isRegularFile(crt) || !Files.isRegularFile(key)) {
            throw new IllegalStateException("Missing server cert or key in " + pkiDir);
        }
        try {
            holder.rebuild(crt, key);
        } catch (IOException
                | CertificateException
                | NoSuchAlgorithmException
                | KeyManagementException
                | KeyStoreException e) {
            throw new IllegalStateException("Cannot initialise server TLS material from " + pkiDir, e);
        }
        return holder;
    }

    /**
     * UC04 — token store. Plain class (not {@code @Service}) so the
     * {@code aisandboxctl client invite} CLI can construct it directly
     * without dragging Spring along; here we expose it as a bean for the
     * server-side {@code EnrollmentTokenService} to consume.
     *
     * <p>Available in every profile (including {@code docs-only}) so
     * springdoc can include {@code POST /v1/enrollment} in the OAS
     * render without nulling out the facade graph.
     */
    @Bean
    public EnrollmentTokenStore enrollmentTokenStore(ServerProperties props) {
        return new EnrollmentTokenStore(props.enrollment().dir());
    }
}
