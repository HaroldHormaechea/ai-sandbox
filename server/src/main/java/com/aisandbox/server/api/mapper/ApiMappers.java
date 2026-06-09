package com.aisandbox.server.api.mapper;

import com.aisandbox.server.api.dto.ApiDtos;
import com.aisandbox.server.clients.dto.AllowedClient;
import com.aisandbox.server.sessions.dto.ClaudeConfigMode;
import com.aisandbox.server.sessions.dto.SessionDetail;
import com.aisandbox.server.sessions.dto.SessionRecord;
import com.aisandbox.server.sessions.dto.SpawnCommand;
import com.aisandbox.server.sessions.dto.WorkspaceMode;
import java.util.List;

/**
 * Hand-written controller-boundary mappers. We don't pull in MapStruct
 * for this MVP — there are six mappings, all trivial.
 */
public final class ApiMappers {

    private ApiMappers() {}

    public static ApiDtos.SessionSummary toSummary(SessionRecord r) {
        return new ApiDtos.SessionSummary(
                r.n(),
                r.label(),
                r.tmuxTitle(),
                r.state(),
                r.uptimeSec(),
                r.activeStreams(),
                r.startedAt(),
                r.conversationName());
    }

    public static List<ApiDtos.SessionSummary> toSummaries(List<SessionRecord> rs) {
        return rs.stream().map(ApiMappers::toSummary).toList();
    }

    public static ApiDtos.SessionDetailDto toDetail(SessionDetail d) {
        return new ApiDtos.SessionDetailDto(
                toSummary(d.summary()), d.workspaceHostPath(), d.claudeConfigHostPath(), d.connectedClients());
    }

    public static SpawnCommand toSpawnCommand(ApiDtos.SpawnRequest req) {
        WorkspaceMode wm = parseWorkspace(req == null ? null : req.workspaceMode());
        ClaudeConfigMode cm = parseClaudeConfig(req == null ? null : req.claudeConfigMode());
        String label = req == null ? null : req.label();
        return new SpawnCommand(label, wm, cm);
    }

    private static WorkspaceMode parseWorkspace(String s) {
        if (s == null || s.isBlank() || s.equalsIgnoreCase("shared")) {
            return WorkspaceMode.SHARED;
        }
        if (s.equalsIgnoreCase("isolated")) {
            return WorkspaceMode.ISOLATED;
        }
        throw new IllegalArgumentException("workspaceMode must be 'shared' or 'isolated'; got: " + s);
    }

    private static ClaudeConfigMode parseClaudeConfig(String s) {
        if (s == null || s.isBlank() || s.equalsIgnoreCase("shared")) {
            return ClaudeConfigMode.SHARED;
        }
        if (s.equalsIgnoreCase("isolated")) {
            return ClaudeConfigMode.ISOLATED;
        }
        throw new IllegalArgumentException("claudeConfigMode must be 'shared' or 'isolated'; got: " + s);
    }

    public static ApiDtos.ClientSummary toClientSummary(AllowedClient c) {
        return new ApiDtos.ClientSummary(
                c.name(),
                c.cn(),
                c.fingerprintHex(),
                c.serial() == null ? "0" : c.serial().toString(),
                c.addedAt());
    }
}
