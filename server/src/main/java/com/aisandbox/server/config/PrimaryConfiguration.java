package com.aisandbox.server.config;

import com.aisandbox.server.clients.service.ClientAllowlistService;
import com.aisandbox.server.enrollment.service.EnrollmentTokenStore;
import com.aisandbox.server.tls.ReloadableSslContextHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Bean wiring for objects that aren't simply {@code @Component}-annotated
 * (custom constructors, JCE state, etc.).
 */
@Configuration
public class PrimaryConfiguration {

    @Bean
    @Profile("!docs-only")
    public ReloadableSslContextHolder reloadableSslContextHolder(ClientAllowlistService allowlist) {
        return new ReloadableSslContextHolder(allowlist);
    }

    /**
     * UC04 — token store. Plain class (not {@code @Service}) so the
     * {@code aisandboxctl client invite} CLI can construct it directly
     * without dragging Spring along; here we expose it as a bean for the
     * server-side {@code EnrollmentTokenService} to consume.
     */
    @Bean
    @Profile("!docs-only")
    public EnrollmentTokenStore enrollmentTokenStore(ServerProperties props) {
        return new EnrollmentTokenStore(props.enrollment().dir());
    }
}
