package com.aisandbox.server.sessions.dto;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated input to {@code spawn.sh}. Construction enforces the
 * argument-array invariants — label must match the allowed regex, modes
 * are enums — so no untrusted string ever reaches a shell.
 */
public record SpawnCommand(String label, WorkspaceMode workspaceMode, ClaudeConfigMode claudeConfigMode) {

    private static final Pattern LABEL_RE = Pattern.compile("[A-Za-z0-9._:/+\\- ]{1,64}");

    public SpawnCommand {
        Objects.requireNonNull(workspaceMode, "workspaceMode");
        Objects.requireNonNull(claudeConfigMode, "claudeConfigMode");
        if (label != null && !LABEL_RE.matcher(label).matches()) {
            throw new IllegalArgumentException("label must match [A-Za-z0-9._:/+\\- ]{1,64}; got: " + label);
        }
    }
}
