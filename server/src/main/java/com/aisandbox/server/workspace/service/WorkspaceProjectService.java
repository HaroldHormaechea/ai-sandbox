package com.aisandbox.server.workspace.service;

import com.aisandbox.server.config.ServerProperties;
import com.aisandbox.server.workspace.dto.WorkspaceProject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * UC-98 — domain service for the workspace-project catalogue. Lists the
 * immediate sub-directories of the server's shared workspace root
 * ({@code <sessions.hostStateRoot>/workspace}) as selectable
 * {@link WorkspaceProject} DTOs.
 *
 * <p>There is no persistence tier here (the catalogue is filesystem-backed), so
 * this service is the source of the read; no {@code @Transactional} (per
 * {@code profile-java-server-architecture} — the project has no transactional
 * resource).
 *
 * <p>Listing rule (AC1): every immediate sub-directory of the root is a
 * project, sorted by name. Non-directories, the {@code .gitkeep} placeholder,
 * and dangling symlinks are skipped; the result is empty (never an error) when
 * the root does not exist. A <b>pluggable filter predicate</b> (default:
 * accept-all) is applied on top, so a later configuration-driven rule (e.g.
 * only git repos, or only folders with a {@code PROJECT_BRIEF.md}) can be
 * introduced without any API change.
 */
@Service
public class WorkspaceProjectService {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceProjectService.class);

    /** The git placeholder that keeps an otherwise-empty workspace root in VCS — never a project. */
    private static final String GITKEEP = ".gitkeep";

    private final ServerProperties props;

    /**
     * The listing filter. Applied to each candidate directory that already
     * passed the structural checks (is a directory, not {@code .gitkeep}, not a
     * dangling symlink). Defaults to accept-all; the package-private constructor
     * lets a future config binding (or a test) supply a narrower rule without
     * changing the API contract (AC1).
     */
    private final Predicate<Path> filter;

    public WorkspaceProjectService(ServerProperties props) {
        this(props, path -> true);
    }

    /** Visible for testing / future configuration — inject a narrower listing filter (AC1). */
    WorkspaceProjectService(ServerProperties props, Predicate<Path> filter) {
        this.props = props;
        this.filter = filter;
    }

    /** The shared workspace root the listing enumerates (regardless of any session's workspace mode, AC7). */
    private Path workspaceRoot() {
        return props.sessions().hostStateRoot().resolve("workspace");
    }

    /**
     * List the selectable workspace projects — immediate sub-directories of the
     * shared workspace root, filtered and sorted by name (AC1). Returns an empty
     * list (never throws) when the root is absent or unreadable, so a
     * not-yet-seeded server simply advertises no projects.
     */
    public List<WorkspaceProject> list() {
        Path root = workspaceRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                if (isEligible(entry)) {
                    dirs.add(entry);
                }
            }
        } catch (IOException | UncheckedIOException e) {
            // Conservative: an unreadable root advertises no projects rather than
            // failing the endpoint. The client renders "None" + an empty list.
            LOG.warn("Cannot enumerate workspace root {}: {}", root, e.toString());
            return List.of();
        }
        dirs.sort(Comparator.comparing(p -> p.getFileName().toString()));
        List<WorkspaceProject> out = new ArrayList<>(dirs.size());
        for (Path dir : dirs) {
            String name = dir.getFileName().toString();
            out.add(new WorkspaceProject(name, name));
        }
        return out;
    }

    private boolean isEligible(Path entry) {
        String name = entry.getFileName().toString();
        if (GITKEEP.equals(name)) {
            return false;
        }
        // Files.isDirectory follows symlinks and returns false for a dangling
        // symlink, so a broken link (or a plain file) is skipped in one check.
        if (!Files.isDirectory(entry)) {
            return false;
        }
        return filter.test(entry);
    }
}
