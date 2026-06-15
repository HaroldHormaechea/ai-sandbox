package com.aisandbox.server.mcp.dto;

import java.util.List;
import java.util.Map;

/**
 * UC-82 — internal DTO describing a request to register a new MCP server for a
 * session. Lives strictly inside the {@code mcp} service / facade layers; the API
 * layer has its own {@code ApiDtos.McpAddRequest} and a mapper at the controller
 * boundary (per {@code profile-java-server-architecture} rule 5). No API-shaping
 * annotations belong here.
 *
 * <p>The fields are transport-dependent: a {@code stdio} server carries a
 * {@code command} (+ optional {@code args} / {@code env}); an {@code http} / {@code sse}
 * server carries a {@code url} (+ optional {@code headers}). Validation of which
 * fields are required for which transport lives in {@link
 * com.aisandbox.server.mcp.facade.McpFacade}, and the argv that reaches process
 * execution is assembled exclusively by {@link
 * com.aisandbox.server.mcp.service.McpRegistrationService} so every user-supplied
 * value lands as a discrete argv element, never a shell string (AC4).
 *
 * @param name      MCP server identifier (its key in the session's MCP config)
 * @param transport {@code stdio} / {@code http} / {@code sse} (already normalised
 *                  to lower-case by the mapper)
 * @param command   stdio transport — the executable to run (required for stdio)
 * @param args      stdio transport — arguments passed AFTER a {@code --} guard so a
 *                  flag-looking value is inert; never null-checked into a shell
 * @param url       http / sse transport — the server URL (required for those)
 * @param env       stdio transport — environment entries passed as {@code -e K=V};
 *                  VALUES are secret-grade and are never logged or audited
 * @param headers   http / sse transport — {@code "Header: value"} strings passed as
 *                  {@code --header}; VALUES are secret-grade and are never logged
 */
public record McpAddSpec(
        String name,
        String transport,
        String command,
        List<String> args,
        String url,
        Map<String, String> env,
        List<String> headers) {}
