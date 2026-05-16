package com.aisandbox.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Boot entry point for {@code aisandbox-server.jar}.
 *
 * <p>The fat jar produced by Gradle's {@code bootJar} task is started by the
 * systemd unit. All wiring — TLS, allowlist watcher, WebSocket handler,
 * audit logger — happens through Spring component scanning rooted at this
 * package. Two profiles matter:
 *
 * <ul>
 *   <li>{@code default} — the running server.</li>
 *   <li>{@code docs-only} — used by {@code :server:generateOpenApiDocs} to
 *       boot a minimal context, render the OAS, and exit. Filesystem
 *       watchers, the Netty TLS customizer, and the audit file appender are
 *       all opted-out via {@code @Profile("!docs-only")}.</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.aisandbox.server.config")
@EnableScheduling
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
