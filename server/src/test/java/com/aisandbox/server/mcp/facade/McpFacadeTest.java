package com.aisandbox.server.mcp.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.mcp.dto.McpActionOutcome;
import com.aisandbox.server.mcp.dto.McpServerStatus;
import com.aisandbox.server.mcp.dto.McpState;
import com.aisandbox.server.mcp.service.McpInventoryService;
import com.aisandbox.server.stream.facade.ConversationFacade;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * UC-67 — {@link McpFacade} is the use-case boundary for the per-session MCP
 * surface. It composes its own-domain {@link McpInventoryService} and reaches the
 * conversation domain via a facade-to-facade call into {@link ConversationFacade}
 * (per {@code profile-java-server-architecture} — never another domain's service).
 *
 * <p>These tests pin the action semantics (AC5/AC6): refresh/reconnect re-list and
 * report the named server's post-refresh state; login ADDITIONALLY surfaces the
 * interactive {@code /mcp} menu in the live session ({@link ConversationFacade#openMcpMenu})
 * before re-listing — and never claims headless OAuth completion; and an unknown
 * server degrades to {@link McpState#UNKNOWN} rather than throwing.
 */
class McpFacadeTest {

    private McpInventoryService inventory;
    private ConversationFacade conversationFacade;
    private McpFacade facade;

    private static ClientIdentity identity() {
        return new ClientIdentity("alice", "a".repeat(64), BigInteger.ONE);
    }

    private static McpServerStatus connected(String name) {
        return new McpServerStatus(name, "stdio", McpState.CONNECTED, "cmd");
    }

    private static McpServerStatus needsAuth(String name) {
        return new McpServerStatus(name, "sse", McpState.NEEDS_AUTH, "https://x/sse");
    }

    @BeforeEach
    void setUp() {
        inventory = mock(McpInventoryService.class);
        conversationFacade = mock(ConversationFacade.class);
        facade = new McpFacade(inventory, conversationFacade);
    }

    @Test
    void list_delegates_to_the_inventory_service() {
        List<McpServerStatus> servers = List.of(connected("call-graph"), needsAuth("atlassian"));
        when(inventory.list(7)).thenReturn(servers);

        assertThat(facade.list(7)).isSameAs(servers);
        verify(inventory).list(7);
    }

    // ──────────────────────── refresh / reconnect (AC5/AC6) ──────────────────

    @Test
    void refresh_relists_and_returns_the_named_servers_post_state() throws Exception {
        when(inventory.refresh(7)).thenReturn(List.of(connected("call-graph")));

        McpActionOutcome out = facade.operate(7, "call-graph", McpFacade.ACTION_REFRESH, identity());

        assertThat(out.name()).isEqualTo("call-graph");
        assertThat(out.state()).isEqualTo(McpState.CONNECTED);
        assertThat(out.message()).isNotBlank();
        // refresh must NOT initiate the login flow.
        verify(conversationFacade, never()).openMcpMenu(eq(7), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reconnect_relists_and_returns_the_named_servers_post_state() throws Exception {
        when(inventory.refresh(7)).thenReturn(List.of(connected("call-graph")));

        McpActionOutcome out = facade.operate(7, "call-graph", McpFacade.ACTION_RECONNECT, identity());

        assertThat(out.state()).isEqualTo(McpState.CONNECTED);
        verify(conversationFacade, never()).openMcpMenu(eq(7), org.mockito.ArgumentMatchers.any());
    }

    // ──────────────────────── login initiates the /mcp flow (AC6) ────────────

    @Test
    void login_surfaces_the_mcp_menu_in_the_live_session_then_relists() throws Exception {
        when(inventory.refresh(7)).thenReturn(List.of(needsAuth("atlassian")));

        McpActionOutcome out = facade.operate(7, "atlassian", McpFacade.ACTION_LOGIN, identity());

        // The facade-to-facade hand-off initiates auth in the live main pane (AC6).
        verify(conversationFacade).openMcpMenu(eq(7), eq(identity()));
        // It then re-lists and reports the (still needs-auth until the human finishes) state.
        assertThat(out.name()).isEqualTo("atlassian");
        assertThat(out.state()).isEqualTo(McpState.NEEDS_AUTH);
        // The message is honest about login only INITIATING the flow (no headless claim).
        assertThat(out.message()).containsIgnoringCase("complete it");
    }

    @Test
    void login_message_never_implies_headless_completion() throws Exception {
        when(inventory.refresh(7)).thenReturn(List.of(needsAuth("atlassian")));

        McpActionOutcome out = facade.operate(7, "atlassian", McpFacade.ACTION_LOGIN, identity());

        assertThat(out.message().toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("authenticated")
                .doesNotContain("logged in");
    }

    // ──────────────────────── defensive: unknown server / action ─────────────

    @Test
    void an_unknown_server_yields_UNKNOWN_without_throwing() throws Exception {
        // The refreshed inventory does not contain the requested name → UNKNOWN.
        when(inventory.refresh(7)).thenReturn(List.of(connected("call-graph")));

        McpActionOutcome out = facade.operate(7, "ghost", McpFacade.ACTION_RECONNECT, identity());

        assertThat(out.name()).isEqualTo("ghost");
        assertThat(out.state()).isEqualTo(McpState.UNKNOWN);
    }

    @Test
    void a_null_identity_is_tolerated_on_login() throws Exception {
        when(inventory.refresh(7)).thenReturn(List.of(needsAuth("atlassian")));

        McpActionOutcome out = facade.operate(7, "atlassian", McpFacade.ACTION_LOGIN, null);

        assertThat(out.state()).isEqualTo(McpState.NEEDS_AUTH);
        verify(conversationFacade).openMcpMenu(eq(7), eq(null));
    }

    @Test
    void an_unrecognised_action_degrades_to_a_relist_without_throwing() throws Exception {
        // Unreachable when the controller's path regex is in force; the facade is
        // defensive — it re-lists and reports the state rather than throwing.
        when(inventory.refresh(7)).thenReturn(List.of(connected("call-graph")));

        McpActionOutcome out = facade.operate(7, "call-graph", "bogus", identity());

        assertThat(out.state()).isEqualTo(McpState.CONNECTED);
        verify(conversationFacade, never()).openMcpMenu(eq(7), org.mockito.ArgumentMatchers.any());
    }
}
