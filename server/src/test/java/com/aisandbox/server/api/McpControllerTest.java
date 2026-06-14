package com.aisandbox.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.mcp.dto.McpActionOutcome;
import com.aisandbox.server.mcp.dto.McpServerStatus;
import com.aisandbox.server.mcp.dto.McpState;
import com.aisandbox.server.mcp.facade.McpFacade;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * UC-67 — wire shape of the per-session MCP surface
 * ({@code /v1/sessions/{n}/mcp}). The controller is the client's only window onto
 * the inventory + controls, so this pins: GET renders a bare JSON array of
 * {@code McpServerSummary} with a LOWERCASED state (AC3/AC4); an empty inventory
 * is a 200 + {@code []} (the screen's empty state, AC7) — not a 404; POST
 * {@code /{name}/{action}} returns the post-action result (AC6); and an action
 * token outside {@code login|reconnect|refresh} 404s because the path regex never
 * matches it (mirroring {@code SessionLifecycleController}).
 *
 * <p>Lightweight {@code WebTestClient.bindToController} wiring (no full Spring
 * boot), mirroring {@link ModelControllerTest}. The DTO mapping is also pinned by
 * {@code ApiMappersTest}; the action semantics by {@code McpFacadeTest}.
 */
class McpControllerTest {

    private static WebTestClient clientFor(McpFacade facade) {
        return WebTestClient.bindToController(new McpController(facade)).build();
    }

    @Test
    void list_renders_a_bare_array_of_summaries_with_lowercased_state() {
        McpFacade facade = mock(McpFacade.class);
        when(facade.list(7))
                .thenReturn(List.of(
                        new McpServerStatus("call-graph", "stdio", McpState.CONNECTED, "java -jar daemon.jar"),
                        new McpServerStatus(
                                "atlassian", "sse", McpState.NEEDS_AUTH, "https://mcp.atlassian.com/v1/sse")));

        clientFor(facade)
                .get()
                .uri("/v1/sessions/7/mcp")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(2)
                .jsonPath("$[0].name")
                .isEqualTo("call-graph")
                .jsonPath("$[0].transport")
                .isEqualTo("stdio")
                // AC3 — state is the lowercased wire value the client decodes.
                .jsonPath("$[0].state")
                .isEqualTo("connected")
                .jsonPath("$[0].detail")
                .isEqualTo("java -jar daemon.jar")
                .jsonPath("$[1].name")
                .isEqualTo("atlassian")
                .jsonPath("$[1].state")
                .isEqualTo("needs_auth");
    }

    @Test
    void an_empty_inventory_is_a_200_empty_array_not_a_404() {
        // AC7 — a session with no MCP servers (or a non-running session) renders the
        // client's empty state off a 200 + [], never an error.
        McpFacade facade = mock(McpFacade.class);
        when(facade.list(3)).thenReturn(List.of());

        clientFor(facade)
                .get()
                .uri("/v1/sessions/3/mcp")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(0);
    }

    @Test
    void login_action_returns_the_post_action_result() throws Exception {
        McpFacade facade = mock(McpFacade.class);
        when(facade.operate(eq(7), eq("atlassian"), eq("login"), any(ClientIdentity.class)))
                .thenReturn(new McpActionOutcome(
                        "atlassian", McpState.NEEDS_AUTH, "Opens MCP authentication in the live session."));

        clientFor(facade)
                .post()
                .uri("/v1/sessions/7/mcp/atlassian/login")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.name")
                .isEqualTo("atlassian")
                .jsonPath("$.state")
                .isEqualTo("needs_auth")
                .jsonPath("$.message")
                .isEqualTo("Opens MCP authentication in the live session.");

        verify(facade).operate(eq(7), eq("atlassian"), eq("login"), any(ClientIdentity.class));
    }

    @Test
    void reconnect_action_returns_the_post_action_result() throws Exception {
        McpFacade facade = mock(McpFacade.class);
        when(facade.operate(eq(7), eq("call-graph"), eq("reconnect"), any(ClientIdentity.class)))
                .thenReturn(new McpActionOutcome("call-graph", McpState.CONNECTED, "Re-checked the connection."));

        clientFor(facade)
                .post()
                .uri("/v1/sessions/7/mcp/call-graph/reconnect")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.state")
                .isEqualTo("connected");
    }

    @Test
    void refresh_action_returns_the_post_action_result() throws Exception {
        McpFacade facade = mock(McpFacade.class);
        when(facade.operate(eq(7), eq("call-graph"), eq("refresh"), any(ClientIdentity.class)))
                .thenReturn(new McpActionOutcome("call-graph", McpState.CONNECTED, "Refreshed."));

        clientFor(facade)
                .post()
                .uri("/v1/sessions/7/mcp/call-graph/refresh")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.state")
                .isEqualTo("connected");
    }

    @Test
    void an_action_outside_the_allowed_set_404s_because_the_path_regex_never_matches() throws Exception {
        // The @PostMapping path regex pins {login|reconnect|refresh}; any other token
        // is an unmapped path → 404 (mirroring SessionLifecycleController), and the
        // facade is never reached.
        McpFacade facade = mock(McpFacade.class);

        clientFor(facade)
                .post()
                .uri("/v1/sessions/7/mcp/atlassian/disable")
                .exchange()
                .expectStatus()
                .isNotFound();

        verify(facade, never()).operate(org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
    }
}
