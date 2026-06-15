package com.aisandbox.server.mcp;

/**
 * UC-82 — thrown by {@code McpFacade.remove} when the targeted MCP server is not
 * configured for the session. The controller maps it to {@code 404 Not Found}
 * ({@code ErrorCode.MCP_SERVER_NOT_FOUND}).
 */
public class McpServerNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public McpServerNotFoundException(String name) {
        super("MCP server not found: " + name);
    }
}
