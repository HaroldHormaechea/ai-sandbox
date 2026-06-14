package com.aisandbox.server.mcp.dto;

/**
 * UC-67 — internal DTO for one MCP server configured in a session, as parsed
 * from {@code claude mcp list}. Lives strictly inside the {@code mcp} service /
 * facade layers; the API layer has its own {@code ApiDtos.McpServerSummary} and
 * a mapper at the controller boundary (per {@code profile-java-server-architecture}
 * rule 5). No API-shaping annotations belong here.
 *
 * @param name      MCP server identifier (the key in the session's MCP config)
 * @param transport best-effort transport hint: {@code stdio} / {@code http} /
 *                  {@code sse} / {@code unknown}
 * @param state     coarse connection {@link McpState}
 * @param detail    the raw connection detail from the list line (command or URL),
 *                  trimmed; surfaced to the user for context, never parsed by code
 */
public record McpServerStatus(String name, String transport, McpState state, String detail) {}
