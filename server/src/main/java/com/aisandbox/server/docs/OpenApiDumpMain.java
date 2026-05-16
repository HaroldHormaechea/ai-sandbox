package com.aisandbox.server.docs;

import com.aisandbox.server.ServerApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Boots the application in the {@code docs-only} profile, fetches the
 * springdoc-generated YAML, writes it to {@code --output=<path>}, and
 * shuts down. Drives the Gradle {@code :server:generateOpenApiDocs} task.
 *
 * <p>The OAS is later diff-checked in CI (AC23). Treat this binary as
 * deterministic — its output is committed to the repo.
 */
public final class OpenApiDumpMain {

    private OpenApiDumpMain() {}

    public static void main(String[] args) throws Exception {
        String output = null;
        for (String a : args) {
            if (a.startsWith("--output=")) {
                output = a.substring("--output=".length());
            }
        }
        if (output == null) {
            throw new IllegalArgumentException("--output=<path> is required");
        }
        // Force the profile defensively in case the caller forgot.
        System.setProperty("spring.profiles.active", "docs-only");
        try (ConfigurableApplicationContext ctx = SpringApplication.run(ServerApplication.class, args)) {
            int port = ctx.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            String body = WebClient.builder()
                    .baseUrl("http://127.0.0.1:" + port)
                    .build()
                    .get()
                    .uri("/v1/openapi.yaml")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(20));
            if (body == null || body.isBlank()) {
                throw new IllegalStateException("springdoc returned empty OAS body");
            }
            Files.writeString(Path.of(output), body);
            System.out.println("OAS written to " + output + " (" + body.length() + " bytes)");
        }
    }
}
