package com.aisandbox.server.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.workspace.dto.WorkspaceProject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

/**
 * UC-98 — the workspace-project catalogue service enumerates the immediate
 * sub-directories of the server's shared workspace root
 * ({@code <sessions.hostStateRoot>/workspace}) as selectable
 * {@link WorkspaceProject} DTOs (AC1). These unit tests pin the listing rule
 * against a real temp filesystem:
 *
 * <ul>
 *   <li>every immediate sub-directory is a project, sorted by name (AC1);</li>
 *   <li>{@code id == name == folder-name} today (AC5);</li>
 *   <li>plain files, the {@code .gitkeep} placeholder, and dangling symlinks are
 *       skipped;</li>
 *   <li>an absent / unreadable root advertises an empty list rather than
 *       throwing (a not-yet-seeded server simply offers "None");</li>
 *   <li>the pluggable filter predicate narrows the listing with no API change
 *       (AC1's config-driven-filter extension point).</li>
 * </ul>
 */
class WorkspaceProjectServiceTest {

    @TempDir
    Path hostStateRoot;

    /** A minimal ServerProperties whose only meaningful field is the sessions host-state root. */
    private static ServerProperties propsWithRoot(Path root) {
        return new ServerProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                new ServerProperties.Sessions(root),
                null,
                new ServerProperties.ServerSsh(true, null, null, null, null, null));
    }

    private Path seedWorkspaceRoot() throws Exception {
        Path workspace = hostStateRoot.resolve("workspace");
        Files.createDirectories(workspace);
        return workspace;
    }

    @Test
    void lists_every_immediate_subdirectory_sorted_by_name() throws Exception {
        Path workspace = seedWorkspaceRoot();
        Files.createDirectory(workspace.resolve("zebra"));
        Files.createDirectory(workspace.resolve("alpha"));
        Files.createDirectory(workspace.resolve("mid"));

        List<WorkspaceProject> out = new WorkspaceProjectService(propsWithRoot(hostStateRoot)).list();

        // AC1 — one project per folder, sorted by name.
        assertThat(out).extracting(WorkspaceProject::id).containsExactly("alpha", "mid", "zebra");
        // AC5 — id and display name are both the folder name today.
        assertThat(out).allSatisfy(p -> assertThat(p.id()).isEqualTo(p.name()));
    }

    @Test
    void skips_plain_files_gitkeep_and_dangling_symlinks() throws Exception {
        Path workspace = seedWorkspaceRoot();
        Files.createDirectory(workspace.resolve("realproject"));
        Files.createFile(workspace.resolve("loose-file.txt"));
        Files.createFile(workspace.resolve(".gitkeep"));
        // A dangling symlink → its target does not exist, so Files.isDirectory is false.
        Files.createSymbolicLink(workspace.resolve("broken-link"), workspace.resolve("does-not-exist"));

        List<WorkspaceProject> out = new WorkspaceProjectService(propsWithRoot(hostStateRoot)).list();

        assertThat(out).extracting(WorkspaceProject::id).containsExactly("realproject");
    }

    @Test
    void empty_list_when_workspace_root_is_absent() {
        // hostStateRoot exists (temp dir) but its workspace/ subfolder was never created.
        WorkspaceProjectService svc = new WorkspaceProjectService(propsWithRoot(hostStateRoot));

        assertThatCode(() -> assertThat(svc.list()).isEmpty()).doesNotThrowAnyException();
    }

    @Test
    void empty_list_when_root_exists_but_has_no_folders() throws Exception {
        seedWorkspaceRoot(); // workspace/ exists but is empty

        assertThat(new WorkspaceProjectService(propsWithRoot(hostStateRoot)).list()).isEmpty();
    }

    @Test
    void filter_predicate_narrows_the_listing_without_an_api_change() throws Exception {
        // AC1 — the config-driven-filter extension point: a narrower predicate
        // (here: only folders containing a PROJECT_BRIEF.md) drops in with no
        // change to the DTO or the endpoint.
        Path workspace = seedWorkspaceRoot();
        Path withBrief = Files.createDirectory(workspace.resolve("has-brief"));
        Files.createFile(withBrief.resolve("PROJECT_BRIEF.md"));
        Files.createDirectory(workspace.resolve("no-brief"));

        Predicate<Path> onlyWithBrief = dir -> Files.isRegularFile(dir.resolve("PROJECT_BRIEF.md"));
        List<WorkspaceProject> out =
                new WorkspaceProjectService(propsWithRoot(hostStateRoot), onlyWithBrief).list();

        assertThat(out).extracting(WorkspaceProject::id).containsExactly("has-brief");
    }
}
