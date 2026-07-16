package com.aisandbox.server.sessions.dto;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated input to {@code spawn.sh}. Construction enforces the
 * argument-array invariants — label must match the allowed regex, modes
 * are enums — so no untrusted string ever reaches a shell.
 *
 * <p>UC-98 — carries the optionally-selected workspace project end-to-end
 * (AC9). {@code workspaceProject} is the selected project's id (its folder
 * name) or {@code null} for the "None" default; a {@code null} selection is a
 * first-class, valid request that preserves today's spawn behaviour byte-for-
 * byte (AC3) — the field never reaches {@code spawn.sh}'s argv (the choice is
 * applied purely by the server's post-spawn injection, not by the container
 * spawn). When present it is validated with a conservative folder-name regex
 * as defense-in-depth (the authoritative safety is the live-listing membership
 * check in the facade before injection); {@code null} skips validation.
 */
public record SpawnCommand(
        String label, WorkspaceMode workspaceMode, ClaudeConfigMode claudeConfigMode, String workspaceProject) {

    private static final Pattern LABEL_RE = Pattern.compile("[A-Za-z0-9._:/+\\- ]{1,64}");

    /**
     * UC-98 — conservative allow-list for a workspace-project id (folder name):
     * letters, digits, dot, underscore, plus, dash and space; no path
     * separators ({@code /}), no {@code :}, no shell metacharacters. Defense-in-
     * depth only — the folder name is passed to tmux as a {@code send-keys -l}
     * literal (never a shell string) and is membership-checked against the live
     * listing before use.
     */
    private static final Pattern PROJECT_RE = Pattern.compile("[A-Za-z0-9._+\\- ]{1,128}");

    public SpawnCommand {
        Objects.requireNonNull(workspaceMode, "workspaceMode");
        Objects.requireNonNull(claudeConfigMode, "claudeConfigMode");
        if (label != null && !LABEL_RE.matcher(label).matches()) {
            throw new IllegalArgumentException("label must match [A-Za-z0-9._:/+\\- ]{1,64}; got: " + label);
        }
        // null == "None" is first-class valid (AC9); only validate a real selection.
        if (workspaceProject != null && !PROJECT_RE.matcher(workspaceProject).matches()) {
            throw new IllegalArgumentException(
                    "workspaceProject must match [A-Za-z0-9._+\\- ]{1,128}; got: " + workspaceProject);
        }
    }

    /**
     * Back-compat constructor for callers / tests built before UC-98 added the
     * {@code workspaceProject} field. Equivalent to selecting "None" (no
     * project), so pre-UC-98 spawn behaviour is unchanged.
     */
    public SpawnCommand(String label, WorkspaceMode workspaceMode, ClaudeConfigMode claudeConfigMode) {
        this(label, workspaceMode, claudeConfigMode, null);
    }
}
