package com.aisandbox.server.mcp.dto;

/**
 * UC-67 — internal DTO describing the result of an MCP control action
 * (refresh / reconnect / login) on one server. Lives strictly inside the
 * {@code mcp} service / facade layers; the API layer has its own
 * {@code ApiDtos.McpActionResult} and a mapper at the controller boundary
 * (per {@code profile-java-server-architecture} rule 5).
 *
 * @param name    the MCP server the action targeted
 * @param state   the server's {@link McpState} after the action (from a re-list)
 * @param message a short, honest human-readable note about what happened —
 *                e.g. that login only INITIATES the flow in the live session
 */
public record McpActionOutcome(String name, McpState state, String message) {}
