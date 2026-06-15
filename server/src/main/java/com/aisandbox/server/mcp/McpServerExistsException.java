package com.aisandbox.server.mcp;

/**
 * UC-82 — thrown by {@code McpFacade.add} when the requested MCP server name is
 * already configured for the session. The controller maps it to {@code 409
 * Conflict} ({@code ErrorCode.MCP_SERVER_EXISTS}); the config is never silently
 * overwritten (AC6).
 */
public class McpServerExistsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public McpServerExistsException(String name) {
        super("MCP server already exists: " + name);
    }
}
