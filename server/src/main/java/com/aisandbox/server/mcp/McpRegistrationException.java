package com.aisandbox.server.mcp;

/**
 * UC-82 — thrown by {@link
 * com.aisandbox.server.mcp.service.McpRegistrationService} when a {@code claude mcp
 * add} / {@code claude mcp remove} invocation fails (non-zero exit or I/O error).
 * A typed-degrade boundary: the underlying {@code IOException} is never propagated
 * raw into the request path. The controller maps it to {@code 500} ({@code
 * ErrorCode.MCP_ADD_FAILED} / {@code MCP_REMOVE_FAILED}).
 *
 * <p>The message is built from the operation, the server name, and the process's
 * exit code / stderr — never from the user-supplied env / header VALUES, which are
 * secret-grade and must not leak into an error body (AC: secrets out of
 * transcripts / error messages).
 */
public class McpRegistrationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public McpRegistrationException(String message) {
        super(message);
    }

    public McpRegistrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
