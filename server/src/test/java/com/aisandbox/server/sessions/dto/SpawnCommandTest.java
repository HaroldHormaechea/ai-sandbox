package com.aisandbox.server.sessions.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * UC-98 — {@link SpawnCommand} carries the optionally-selected workspace project
 * end-to-end (AC9). These tests pin the construction contract:
 *
 * <ul>
 *   <li>a {@code null} project is first-class valid ("None") and skips validation
 *       entirely (AC3/AC9);</li>
 *   <li>a real selection is validated with the conservative folder-name regex
 *       (defense-in-depth — the authoritative check is the live-listing
 *       membership test in the facade);</li>
 *   <li>the pre-UC-98 3-arg constructor still compiles and defaults the field to
 *       null (back-compat), so existing spawn behaviour is unchanged.</li>
 * </ul>
 */
class SpawnCommandTest {

    @Test
    void null_workspace_project_is_first_class_valid() {
        // AC9 — "None" is a valid request; no validation is applied.
        SpawnCommand cmd = new SpawnCommand("lbl", WorkspaceMode.SHARED, ClaudeConfigMode.SHARED, null);

        assertThat(cmd.workspaceProject()).isNull();
    }

    @Test
    void a_folder_safe_workspace_project_is_accepted() {
        assertThatCode(() ->
                        new SpawnCommand("lbl", WorkspaceMode.SHARED, ClaudeConfigMode.SHARED, "my-project.v2 name"))
                .doesNotThrowAnyException();
    }

    @Test
    void a_workspace_project_with_shell_metacharacters_is_rejected() {
        // Defense-in-depth: no path separators, no shell metacharacters.
        assertThatThrownBy(() ->
                        new SpawnCommand("lbl", WorkspaceMode.SHARED, ClaudeConfigMode.SHARED, "../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new SpawnCommand("lbl", WorkspaceMode.SHARED, ClaudeConfigMode.SHARED, "proj; rm -rf /"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void back_compat_three_arg_constructor_defaults_project_to_null() {
        // AC3 — pre-UC-98 callers build a "None" spawn unchanged.
        SpawnCommand cmd = new SpawnCommand("lbl", WorkspaceMode.SHARED, ClaudeConfigMode.SHARED);

        assertThat(cmd.workspaceProject()).isNull();
    }
}
