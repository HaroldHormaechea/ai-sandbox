package com.aisandbox.server.sessions.dto;

/**
 * Internal enum mapping to the {@code --shared-claude-config} /
 * {@code --isolated-claude-config} flags accepted by UC02's
 * {@code spawn.sh}.
 */
public enum ClaudeConfigMode {
    SHARED("--shared-claude-config"),
    ISOLATED("--isolated-claude-config");

    private final String flag;

    ClaudeConfigMode(String flag) {
        this.flag = flag;
    }

    public String flag() {
        return flag;
    }
}
