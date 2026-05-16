package com.aisandbox.server.sessions.dto;

import java.util.List;

/**
 * Internal projection for {@code GET /v1/sessions/{n}} — list-shape plus
 * bind-mount paths and the identities currently attached via WebSocket.
 */
public record SessionDetail(
        SessionRecord summary, String workspaceHostPath, String claudeConfigHostPath, List<String> connectedClients) {}
