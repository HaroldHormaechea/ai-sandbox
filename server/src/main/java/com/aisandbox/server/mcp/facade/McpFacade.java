package com.aisandbox.server.mcp.facade;

import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.mcp.McpLoginInitiator;
import com.aisandbox.server.mcp.dto.McpActionOutcome;
import com.aisandbox.server.mcp.dto.McpServerStatus;
import com.aisandbox.server.mcp.dto.McpState;
import com.aisandbox.server.mcp.service.McpInventoryService;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * UC-67 — use-case-level entry point for the per-session MCP management surface
 * ({@code /v1/sessions/{n}/mcp}). Read-only facade like {@code ModelCatalogFacade}
 * / {@code HealthFacade}: no {@code @Transactional}, because the inventory is
 * process-backed ({@code claude mcp list}), not DB-backed — there is no
 * transactional resource to bound.
 *
 * <p>Layering ({@code profile-java-server-architecture}): the controller calls
 * only this facade; the facade composes its own-domain {@link McpInventoryService}
 * and reaches the conversation domain (to surface the {@code /mcp} login menu)
 * through the {@link McpLoginInitiator} port — a dependency-inversion seam owned by
 * this package, implemented by the {@code stream} domain — so {@code mcp} never
 * imports {@code stream} (which would create an {@code api → mcp → stream → api}
 * package cycle that {@code LayeringTest} forbids). The runtime call is still a
 * facade-to-facade hand-off; only the compile-time arrow is inverted.
 */
@Component
public class McpFacade {

    private static final Logger LOG = LoggerFactory.getLogger(McpFacade.class);

    /** Recognised control actions. The controller's path regex pins the same set. */
    public static final String ACTION_LOGIN = "login";

    public static final String ACTION_RECONNECT = "reconnect";
    public static final String ACTION_REFRESH = "refresh";

    private final McpInventoryService inventory;
    private final McpLoginInitiator loginInitiator;

    public McpFacade(McpInventoryService inventory, McpLoginInitiator loginInitiator) {
        this.inventory = inventory;
        this.loginInitiator = loginInitiator;
    }

    /** List the session's MCP servers and their current state (AC3/AC4). */
    public List<McpServerStatus> list(int n) {
        return inventory.list(n);
    }

    /**
     * Drive a control action against one MCP server (AC5/AC6) and report the
     * resulting state from a fresh inventory.
     *
     * <ul>
     *   <li>{@code refresh} / {@code reconnect} — re-run {@code claude mcp list}
     *       (a reconnect for a stdio server is, honestly, a re-health-check) and
     *       return the named server's post-refresh state.</li>
     *   <li>{@code login} — INITIATE auth by surfacing the interactive {@code /mcp}
     *       menu in the session's live main pane (facade-to-facade), then re-list
     *       and return the current state. The auth is completed by the human in
     *       that session; this never claims headless completion.</li>
     * </ul>
     *
     * Never throws on a missing server or a degraded inventory: an unknown server
     * yields {@link McpState#UNKNOWN} with an explanatory message.
     *
     * @param action one of {@link #ACTION_LOGIN} / {@link #ACTION_RECONNECT} /
     *               {@link #ACTION_REFRESH} (the controller pins this set)
     */
    public McpActionOutcome operate(int n, String name, String action, ClientIdentity identity) throws IOException {
        String act = action == null ? "" : action.toLowerCase(Locale.ROOT);
        if (ACTION_LOGIN.equals(act)) {
            // Initiate only — surface /mcp in the live session for the human to finish.
            loginInitiator.openMcpMenu(n, identity);
            McpState state = stateOf(inventory.refresh(n), name);
            return new McpActionOutcome(
                    name, state, "Opens MCP authentication in the live session — complete it there, then refresh.");
        }
        if (ACTION_RECONNECT.equals(act)) {
            McpState state = stateOf(inventory.refresh(n), name);
            return new McpActionOutcome(name, state, "Re-checked the server's connection.");
        }
        if (ACTION_REFRESH.equals(act)) {
            McpState state = stateOf(inventory.refresh(n), name);
            return new McpActionOutcome(name, state, "Refreshed the server's state.");
        }
        // Unreachable when the controller's path regex is in force; defensive only.
        LOG.warn("unrecognised MCP action '{}' for n={} name={}", action, n, name);
        return new McpActionOutcome(name, stateOf(inventory.refresh(n), name), "Unsupported action.");
    }

    /** Find {@code name}'s state in a fresh inventory; {@link McpState#UNKNOWN} when absent. */
    private static McpState stateOf(List<McpServerStatus> inventory, String name) {
        if (name == null) {
            return McpState.UNKNOWN;
        }
        return inventory.stream()
                .filter(s -> name.equals(s.name()))
                .map(McpServerStatus::state)
                .findFirst()
                .orElse(McpState.UNKNOWN);
    }
}
