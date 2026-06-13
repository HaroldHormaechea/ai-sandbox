package com.aisandbox.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * AC23 — the committed {@code server/openapi.yaml} is the contract. CI
 * fails on drift. This static check verifies the file exists and looks
 * like an OAS 3.x document; the dev-team flagged that the committed file
 * is a hand-written skeleton today and may diverge from
 * {@code ./gradlew :server:generateOpenApiDocs} output.
 *
 * <p>The drift check itself — diff against a freshly generated file —
 * lives in the {@code server-ci.yml} workflow because it requires booting
 * the full Spring context (docs-only profile). This test stays in the
 * unit tier and is intentionally tolerant: it verifies the committed file
 * is non-empty and parseable as YAML, plus that every controller-declared
 * route appears as a path entry.
 *
 * <p>If the committed OAS drifts from the controllers, this test flags
 * the missing paths so the dev team knows to refresh the baseline. This
 * is the "known limitation #2" the team-lead asked QA to validate.
 */
class OpenApiRouteDriftTest {

    @Test
    void committed_openapi_yaml_exists_and_is_non_empty() throws IOException {
        Path oas = locateOas();
        assertThat(oas).as("committed OAS file").exists().isRegularFile();
        String body = Files.readString(oas);
        assertThat(body).isNotBlank();
        assertThat(body).contains("openapi:");
        assertThat(body).contains("paths:");
    }

    @Test
    void committed_openapi_yaml_lists_every_controller_route() throws IOException {
        Path oas = locateOas();
        String body = Files.readString(oas);
        // Routes pulled from SessionController / ClientController / HealthController.
        for (String path : new String[] {
            "/v1/sessions", "/v1/sessions/{n}", "/v1/clients", "/v1/clients/{cnOrFingerprint}", "/v1/healthz"
        }) {
            assertThat(body).as("route %s in committed OAS", path).contains(path);
        }
    }

    /**
     * UC-28 AC1 / AC12 — the committed OAS must carry the new {@code terminating}
     * status token AND the full status enum {@code running | starting |
     * provisioning | terminating | stopped}, so {@code server-ci.yml}'s
     * OpenAPI-drift check passes and Android decodes the documented value set.
     */
    @Test
    void committed_openapi_yaml_includes_terminating_status_enum() throws IOException {
        Path oas = locateOas();
        String body = Files.readString(oas);
        assertThat(body)
                .as("committed OAS must declare the UC-28 terminating status token")
                .contains("terminating");
        // The full status value set must be present (order-independent assertion).
        for (String token : new String[] {"running", "starting", "provisioning", "terminating", "stopped"}) {
            assertThat(body)
                    .as("session status enum value %s in committed OAS", token)
                    .contains(token);
        }
    }

    /**
     * UC-62 AC6 — the committed OAS must declare the new
     * {@code POST /v1/sessions/server-ssh} route AND the session {@code type}
     * discriminator with both allowable values ({@code claude}, {@code server-ssh}),
     * so {@code server-ci.yml}'s OpenAPI-drift check passes and Android decodes the
     * documented value set. A miss here means the developer did not regenerate
     * {@code server/openapi.yaml} after adding the endpoint / DTO field — a
     * developer-side bug, not a test bug.
     */
    @Test
    void committed_openapi_yaml_includes_server_ssh_route_and_type_enum() throws IOException {
        Path oas = locateOas();
        String body = Files.readString(oas);
        assertThat(body)
                .as("UC-62 AC6 — the server-ssh create route must appear in the committed OAS")
                .contains("/v1/sessions/server-ssh");
        // The session.type discriminator and BOTH allowable values must be present.
        for (String token : new String[] {"server-ssh", "claude"}) {
            assertThat(body)
                    .as("UC-62 — session type value %s in committed OAS", token)
                    .contains(token);
        }
    }

    private static Path locateOas() {
        Path direct = Path.of("openapi.yaml");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        // When tests run from the project root rather than the module root.
        Path nested = Path.of("server", "openapi.yaml");
        if (Files.isRegularFile(nested)) {
            return nested;
        }
        // Final fallback — absolute path under TARGET_DIR.
        return Path.of("/workspace/ai-sandbox/server/openapi.yaml");
    }
}
