package com.aisandbox.server.sessions.dto;

/**
 * Internal enum mapping to the {@code --shared-workspace} /
 * {@code --isolated-workspace} flags accepted by UC02's {@code spawn.sh}.
 */
public enum WorkspaceMode {
    SHARED("--shared-workspace"),
    ISOLATED("--isolated-workspace");

    private final String flag;

    WorkspaceMode(String flag) {
        this.flag = flag;
    }

    public String flag() {
        return flag;
    }
}
