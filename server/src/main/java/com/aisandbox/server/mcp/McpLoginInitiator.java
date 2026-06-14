package com.aisandbox.server.mcp;

import com.aisandbox.server.identity.ClientIdentity;
import java.io.IOException;

/**
 * UC-67 — port (dependency-inversion seam) the {@code mcp} domain uses to INITIATE
 * an MCP server's login flow without depending on the {@code stream} domain.
 *
 * <p><b>Why this exists.</b> The login control hands off to the conversation
 * domain (it surfaces Claude Code's interactive {@code /mcp} menu in the session's
 * live main pane). A direct {@code mcp → stream} call would create a package cycle
 * — {@code api → mcp → stream → api} — because the {@code stream} domain already
 * depends on {@code api} (its exception handlers import {@code api.error} types),
 * which {@code LayeringTest.no_cycles_between_top_level_feature_packages} forbids.
 * Inverting the dependency keeps the contract here in {@code mcp} and the
 * implementation in {@code stream}, so the edges become {@code api → mcp},
 * {@code stream → mcp}, {@code stream → api} — acyclic. {@code mcp} depends only on
 * the neutral leaf {@code identity} package (for {@link ClientIdentity}), never on
 * {@code stream} or {@code api}.
 *
 * <p>Spring injects the single implementation by type (the conversation domain's
 * facade). The honesty contract is the implementation's: login only INITIATES —
 * it never completes OAuth headlessly.
 */
public interface McpLoginInitiator {

    /**
     * Surface the interactive {@code /mcp} login menu in session {@code n}'s live
     * main pane so a human can complete authentication there.
     *
     * @param identity the calling client's mTLS identity (for audit); never null
     *                 (callers pass {@code ClientIdentity.ANONYMOUS} when unauthenticated)
     */
    void openMcpMenu(int n, ClientIdentity identity) throws IOException;
}
