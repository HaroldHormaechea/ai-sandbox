package com.aisandbox.server.mcp.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.mcp.McpLoginInitiator;
import com.aisandbox.server.mcp.McpRegistrationException;
import com.aisandbox.server.mcp.McpServerExistsException;
import com.aisandbox.server.mcp.McpServerNotFoundException;
import com.aisandbox.server.mcp.dto.McpActionOutcome;
import com.aisandbox.server.mcp.dto.McpAddSpec;
import com.aisandbox.server.mcp.dto.McpServerStatus;
import com.aisandbox.server.mcp.dto.McpState;
import com.aisandbox.server.mcp.service.McpInventoryService;
import com.aisandbox.server.mcp.service.McpRegistrationService;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link McpFacade} is the use-case boundary for the per-session MCP surface. It
 * composes its own-domain {@link McpInventoryService} + {@link McpRegistrationService}
 * and reaches the conversation domain via the {@link McpLoginInitiator} port (per
 * {@code profile-java-server-architecture} — never another domain's service, and the
 * inverted edge avoids the {@code api → mcp → stream → api} cycle).
 *
 * <p>UC-67 contracts (operate/list): refresh/reconnect re-list and report the named
 * server's post-refresh state; login ADDITIONALLY surfaces the {@code /mcp} menu in
 * the live session and never claims headless completion; an unknown server degrades
 * to {@link McpState#UNKNOWN}.
 *
 * <p>UC-82 contracts (add/remove, AC1/AC2/AC6/secret-hygiene): add validates the
 * request, rejects duplicate names with no silent overwrite (409), registers, then
 * re-inventories so the screen reflects it live (AC3); remove validates, 404s on an
 * absent server, deregisters, re-inventories, and returns an HONEST message (AC2 — a
 * running child is not force-killed). Both audit only {@code n}/{@code name}/{@code
 * transport} — NEVER the secret env / header VALUES.
 */
class McpFacadeTest {

    private McpInventoryService inventory;
    private McpRegistrationService registration;
    private McpLoginInitiator loginInitiator;
    private AuditLogger audit;
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

    private static McpAddSpec stdioSpec(String name) {
        return new McpAddSpec(name, "stdio", "npx", List.of("-y", "server"), null, Map.of("TOKEN", "s3cr3t"), null);
    }

    private static McpAddSpec httpSpec(String name) {
        return new McpAddSpec(
                name, "http", null, null, "https://mcp.example.com/v1", null, List.of("Authorization: Bearer s3cr3t"));
    }

    @BeforeEach
    void setUp() {
        inventory = mock(McpInventoryService.class);
        registration = mock(McpRegistrationService.class);
        loginInitiator = mock(McpLoginInitiator.class);
        audit = mock(AuditLogger.class);
        facade = new McpFacade(inventory, registration, loginInitiator, audit);
    }

    @Test
    void list_delegates_to_the_inventory_service() {
        List<McpServerStatus> servers = List.of(connected("call-graph"), needsAuth("atlassian"));
        when(inventory.list(7)).thenReturn(servers);

        assertThat(facade.list(7)).isSameAs(servers);
        verify(inventory).list(7);
    }

    // ──────────────────────── UC-82 add (AC1 / AC3 / AC6) ────────────────────

    @Test
    void add_registers_then_relists_and_returns_the_new_servers_live_state() {
        // Not yet present (duplicate check) → register → refreshed inventory has it (AC3).
        when(inventory.list(7)).thenReturn(List.of(connected("other")));
        when(inventory.refresh(7)).thenReturn(List.of(connected("other"), connected("fresh")));

        McpActionOutcome out = facade.add(7, stdioSpec("fresh"));

        verify(registration).add(eq(7), any(McpAddSpec.class));
        verify(inventory).refresh(7); // AC3 — re-inventory so the screen updates live.
        assertThat(out.name()).isEqualTo("fresh");
        assertThat(out.state()).isEqualTo(McpState.CONNECTED);
        assertThat(out.message()).contains("fresh");
    }

    @Test
    void add_rejects_a_duplicate_name_with_no_silent_overwrite() {
        // AC6 — the name already exists → 409 path; registration is NEVER attempted.
        when(inventory.list(7)).thenReturn(List.of(connected("dupe")));

        assertThatThrownBy(() -> facade.add(7, stdioSpec("dupe"))).isInstanceOf(McpServerExistsException.class);
        verify(registration, never()).add(eq(7), any());
        verify(inventory, never()).refresh(7);
    }

    @Test
    void add_rejects_a_blank_name() {
        assertThatThrownBy(() -> facade.add(7, stdioSpec(""))).isInstanceOf(IllegalArgumentException.class);
        verify(registration, never()).add(eq(7), any());
    }

    @Test
    void add_rejects_a_leading_dash_name_so_it_can_never_be_mistaken_for_a_flag() {
        assertThatThrownBy(() -> facade.add(7, stdioSpec("--privileged"))).isInstanceOf(IllegalArgumentException.class);
        verify(registration, never()).add(eq(7), any());
    }

    @Test
    void add_rejects_a_name_with_spaces_or_illegal_chars() {
        assertThatThrownBy(() -> facade.add(7, stdioSpec("evil name"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> facade.add(7, stdioSpec("name;rm"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void add_rejects_an_unknown_transport() {
        McpAddSpec bad = new McpAddSpec("s", "websocket", "npx", null, null, null, null);
        assertThatThrownBy(() -> facade.add(7, bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void add_rejects_stdio_without_a_command() {
        McpAddSpec bad = new McpAddSpec("s", "stdio", "  ", null, null, null, null);
        assertThatThrownBy(() -> facade.add(7, bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void add_rejects_a_stdio_command_that_starts_with_a_dash() {
        McpAddSpec bad = new McpAddSpec("s", "stdio", "--evil", null, null, null, null);
        assertThatThrownBy(() -> facade.add(7, bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void add_rejects_http_without_a_url() {
        McpAddSpec bad = new McpAddSpec("s", "http", null, null, "  ", null, null);
        assertThatThrownBy(() -> facade.add(7, bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void add_rejects_a_url_with_a_non_http_scheme() {
        // A file:// / javascript: scheme is rejected before it ever reaches exec.
        McpAddSpec fileUrl = new McpAddSpec("s", "http", null, null, "file:///etc/passwd", null, null);
        McpAddSpec jsUrl = new McpAddSpec("s", "sse", null, null, "javascript:alert(1)", null, null);
        assertThatThrownBy(() -> facade.add(7, fileUrl)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> facade.add(7, jsUrl)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void add_accepts_a_valid_http_spec() {
        when(inventory.list(7)).thenReturn(List.of());
        when(inventory.refresh(7)).thenReturn(List.of(needsAuth("remote")));

        McpActionOutcome out = facade.add(7, httpSpec("remote"));

        verify(registration).add(eq(7), any());
        assertThat(out.state()).isEqualTo(McpState.NEEDS_AUTH);
    }

    @Test
    void add_propagates_a_registration_failure_and_does_not_relist() {
        when(inventory.list(7)).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new McpRegistrationException("boom"))
                .when(registration)
                .add(eq(7), any());

        assertThatThrownBy(() -> facade.add(7, stdioSpec("fresh"))).isInstanceOf(McpRegistrationException.class);
        verify(inventory, never()).refresh(7);
    }

    @Test
    void add_audits_without_logging_any_secret_env_or_header_values() {
        when(inventory.list(7)).thenReturn(List.of());
        when(inventory.refresh(7)).thenReturn(List.of(connected("fresh")));

        facade.add(7, stdioSpec("fresh"));

        ArgumentCaptor<Object[]> fields = ArgumentCaptor.forClass(Object[].class);
        verify(audit).logEvent(eq(AuditAction.MCP_ADD), eq("ok"), fields.capture());
        // The secret env VALUE ("s3cr3t") must never appear in audit fields.
        for (Object f : fields.getValue()) {
            assertThat(String.valueOf(f)).doesNotContain("s3cr3t");
        }
        // …and the audit must carry the safe metadata.
        assertThat(java.util.Arrays.asList(fields.getValue())).contains("name", "fresh", "transport", "stdio");
    }

    // ──────────────────────── UC-82 remove (AC2 / AC6) ───────────────────────

    @Test
    void remove_deregisters_relists_and_returns_an_honest_message() {
        when(inventory.list(7)).thenReturn(List.of(connected("gone")));

        McpActionOutcome out = facade.remove(7, "gone");

        verify(registration).remove(7, "gone");
        verify(inventory).refresh(7);
        assertThat(out.name()).isEqualTo("gone");
        // AC2 honesty — the message says it was deregistered and is explicit that a
        // running child is NOT force-killed (no false "stopped/killed the process" claim).
        assertThat(out.message().toLowerCase(java.util.Locale.ROOT)).contains("deregistered");
        assertThat(out.message()).containsIgnoringCase("isn't force-killed");
        assertThat(out.message().toLowerCase(java.util.Locale.ROOT)).doesNotContain("stopped the process");
    }

    @Test
    void remove_404s_when_the_server_is_absent_and_never_execs() {
        when(inventory.list(7)).thenReturn(List.of(connected("other")));

        assertThatThrownBy(() -> facade.remove(7, "ghost")).isInstanceOf(McpServerNotFoundException.class);
        verify(registration, never()).remove(eq(7), any());
        verify(inventory, never()).refresh(7);
    }

    @Test
    void remove_rejects_a_blank_or_leading_dash_name_before_exec() {
        assertThatThrownBy(() -> facade.remove(7, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> facade.remove(7, "--scope")).isInstanceOf(IllegalArgumentException.class);
        verify(registration, never()).remove(eq(7), any());
    }

    @Test
    void remove_audits_with_safe_metadata_only() {
        when(inventory.list(7)).thenReturn(List.of(connected("gone")));

        facade.remove(7, "gone");

        verify(audit).logEvent(eq(AuditAction.MCP_REMOVE), eq("ok"), any(Object[].class));
    }

    // ──────────────────────── UC-67 operate (refresh / reconnect / login) ─────

    @Test
    void refresh_relists_and_returns_the_named_servers_post_state() throws Exception {
        when(inventory.refresh(7)).thenReturn(List.of(connected("call-graph")));

        McpActionOutcome out = facade.operate(7, "call-graph", McpFacade.ACTION_REFRESH, identity());

        assertThat(out.name()).isEqualTo("call-graph");
        assertThat(out.state()).isEqualTo(McpState.CONNECTED);
        assertThat(out.message()).isNotBlank();
        verify(loginInitiator, never()).openMcpMenu(eq(7), any());
    }

    @Test
    void reconnect_relists_and_returns_the_named_servers_post_state() throws Exception {
        when(inventory.refresh(7)).thenReturn(List.of(connected("call-graph")));

        McpActionOutcome out = facade.operate(7, "call-graph", McpFacade.ACTION_RECONNECT, identity());

        assertThat(out.state()).isEqualTo(McpState.CONNECTED);
        verify(loginInitiator, never()).openMcpMenu(eq(7), any());
    }

    @Test
    void login_surfaces_the_mcp_menu_in_the_live_session_then_relists() throws Exception {
        when(inventory.refresh(7)).thenReturn(List.of(needsAuth("atlassian")));

        McpActionOutcome out = facade.operate(7, "atlassian", McpFacade.ACTION_LOGIN, identity());

        verify(loginInitiator).openMcpMenu(eq(7), eq(identity()));
        assertThat(out.name()).isEqualTo("atlassian");
        assertThat(out.state()).isEqualTo(McpState.NEEDS_AUTH);
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

    @Test
    void an_unknown_server_yields_UNKNOWN_without_throwing() throws Exception {
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
        verify(loginInitiator).openMcpMenu(eq(7), eq(null));
    }

    @Test
    void an_unrecognised_action_degrades_to_a_relist_without_throwing() throws Exception {
        when(inventory.refresh(7)).thenReturn(List.of(connected("call-graph")));

        McpActionOutcome out = facade.operate(7, "call-graph", "bogus", identity());

        assertThat(out.state()).isEqualTo(McpState.CONNECTED);
        verify(loginInitiator, never()).openMcpMenu(eq(7), any());
        verify(inventory, atLeastOnce()).refresh(7);
    }
}
