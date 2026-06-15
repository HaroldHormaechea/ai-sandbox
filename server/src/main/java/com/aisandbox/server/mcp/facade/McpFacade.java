package com.aisandbox.server.mcp.facade;

import com.aisandbox.server.audit.AuditAction;
import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.identity.ClientIdentity;
import com.aisandbox.server.mcp.McpLoginInitiator;
import com.aisandbox.server.mcp.McpServerExistsException;
import com.aisandbox.server.mcp.McpServerNotFoundException;
import com.aisandbox.server.mcp.dto.McpActionOutcome;
import com.aisandbox.server.mcp.dto.McpAddSpec;
import com.aisandbox.server.mcp.dto.McpServerStatus;
import com.aisandbox.server.mcp.dto.McpState;
import com.aisandbox.server.mcp.service.McpInventoryService;
import com.aisandbox.server.mcp.service.McpRegistrationService;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
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

    /** AC6 — recognised transports for an add request. */
    private static final List<String> TRANSPORTS = List.of("stdio", "http", "sse");

    /**
     * AC6 — a valid MCP server name: filename/config-safe, no spaces, no leading
     * dash (so it can never be mistaken for a flag once it lands on the argv).
     */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final McpInventoryService inventory;
    private final McpRegistrationService registration;
    private final McpLoginInitiator loginInitiator;
    private final AuditLogger audit;

    public McpFacade(
            McpInventoryService inventory,
            McpRegistrationService registration,
            McpLoginInitiator loginInitiator,
            AuditLogger audit) {
        this.inventory = inventory;
        this.registration = registration;
        this.loginInitiator = loginInitiator;
        this.audit = audit;
    }

    /** List the session's MCP servers and their current state (AC3/AC4). */
    public List<McpServerStatus> list(int n) {
        return inventory.list(n);
    }

    /**
     * UC-82 — register a new MCP server for session {@code n} (AC1).
     *
     * <p>Flow: validate the request (AC6) → reject a duplicate name against the live
     * inventory (AC6, no silent overwrite) → register argv-only via {@link
     * McpRegistrationService} (AC4/AC5) → re-inventory and return the new server's
     * state so the screen reflects it live (AC3).
     *
     * <p>Audit records only {@code n}, {@code name}, and {@code transport} — NEVER the
     * env / header / secret VALUES carried in the spec.
     *
     * @throws IllegalArgumentException  malformed / missing required fields (→ 400)
     * @throws McpServerExistsException  the name is already configured (→ 409)
     * @throws com.aisandbox.server.mcp.McpRegistrationException the exec failed (→ 500)
     */
    public McpActionOutcome add(int n, McpAddSpec spec) {
        validateSpec(spec);
        if (existsInInventory(n, spec.name())) {
            throw new McpServerExistsException(spec.name());
        }
        registration.add(n, spec);
        McpState state = stateOf(inventory.refresh(n), spec.name());
        audit.logEvent(AuditAction.MCP_ADD, "ok", "n", n, "name", spec.name(), "transport", spec.transport());
        return new McpActionOutcome(spec.name(), state, "Added \"" + spec.name() + "\" to this session's MCP servers.");
    }

    /**
     * UC-82 — deregister MCP server {@code name} from session {@code n} (AC2).
     *
     * <p>AC2 (user-accepted): this only deregisters and lets the next MCP reload
     * reconcile the running set; an already-spawned child process is NOT force-killed.
     * The returned message says so honestly so the client's snackbar does too.
     *
     * @throws IllegalArgumentException     malformed name (→ 400)
     * @throws McpServerNotFoundException   no such server in the session (→ 404)
     * @throws com.aisandbox.server.mcp.McpRegistrationException the exec failed (→ 500)
     */
    public McpActionOutcome remove(int n, String name) {
        validateName(name);
        if (!existsInInventory(n, name)) {
            throw new McpServerNotFoundException(name);
        }
        registration.remove(n, name);
        inventory.refresh(n);
        audit.logEvent(AuditAction.MCP_REMOVE, "ok", "n", n, "name", name);
        return new McpActionOutcome(
                name,
                McpState.UNKNOWN,
                "Deregistered \"" + name + "\" — it won't reappear in the list. Any already-running process keeps "
                        + "running until the session's MCP servers are next reloaded; it isn't force-killed.");
    }

    private boolean existsInInventory(int n, String name) {
        return inventory.list(n).stream().anyMatch(s -> name.equals(s.name()));
    }

    // ──────────────────────── validation (AC6 + arg-injection hardening) ────────────────────────

    private static void validateSpec(McpAddSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("request body is required");
        }
        validateName(spec.name());
        String transport = spec.transport() == null ? "" : spec.transport().toLowerCase(Locale.ROOT);
        if (!TRANSPORTS.contains(transport)) {
            throw new IllegalArgumentException("transport must be one of " + TRANSPORTS);
        }
        if ("stdio".equals(transport)) {
            if (isBlank(spec.command())) {
                throw new IllegalArgumentException("stdio transport requires a command");
            }
            if (spec.command().startsWith("-")) {
                throw new IllegalArgumentException("command must not start with '-'");
            }
        } else {
            // http / sse
            if (isBlank(spec.url())) {
                throw new IllegalArgumentException(transport + " transport requires a url");
            }
            String url = spec.url();
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                throw new IllegalArgumentException("url must use the http:// or https:// scheme");
            }
        }
    }

    private static void validateName(String name) {
        if (isBlank(name)) {
            throw new IllegalArgumentException("MCP server name is required");
        }
        if (name.startsWith("-")) {
            throw new IllegalArgumentException("MCP server name must not start with '-'");
        }
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "MCP server name must match [A-Za-z0-9._-], be 1-128 chars, and contain no spaces");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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
