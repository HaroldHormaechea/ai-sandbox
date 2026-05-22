package com.aisandbox.server.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * UC-17 — static contract guard for the uid-alignment pieces that live
 * in the repo-root container/compose/host-script files, mirroring
 * {@link com.aisandbox.server.systemd.UnitFileContractTest}'s
 * parse-the-packaged-file-and-assert pattern.
 *
 * <p>The <b>runtime</b> behaviour these lines produce — a container
 * actually booting as an arbitrary host uid, self-registering its
 * {@code /etc/passwd} entry, and reading the 0600 git-key — is a Docker
 * concern and is verified by CI only (the {@code server-ci.yml}
 * "uid-aligned session bootstrap (run as uid 4242:0)" step and the
 * gated {@code realDockerOnboardingTest} / {@code real-docker-onboarding.sh}
 * lane). There is <b>no Docker daemon in the JVM unit-test environment</b>,
 * so this test does what a JVM test can soundly do: assert the source
 * lines that the recipe depends on exist and are ordered correctly.
 * Treat the CI Docker probes as the acceptance evidence for AC5/AC6;
 * this guard catches a regression where someone edits the
 * Dockerfile / entrypoint / compose / spawn.sh and silently drops one
 * of the required lines.
 *
 * <p>Path discovery: the test JVM cwd is {@code server/}, so the
 * repo-root files live one directory up — same convention
 * {@link HostScriptComposeEnvTest} uses.
 */
class SessionUidAlignmentContractTest {

    /** Test JVM cwd is {@code server/}; the container/compose files live at the repo root. */
    private static final Path REPO_ROOT =
            Path.of(System.getProperty("user.dir")).getParent();

    private static final Path DOCKERFILE = REPO_ROOT.resolve("SandboxDockerfile");
    private static final Path ENTRYPOINT = REPO_ROOT.resolve("entrypoint.sh");
    private static final Path COMPOSE = REPO_ROOT.resolve("docker-compose.yml");
    private static final Path SPAWN = REPO_ROOT.resolve("spawn.sh");

    /**
     * AC5/AC6 (build-time half) — the Dockerfile must prepare the image
     * for the OpenShift arbitrary-uid recipe: a pinned {@code HOME}, a
     * group-0 + group-writable {@code $HOME}, and a group-0 +
     * group-writable {@code /etc/passwd} so any gid-0 process can
     * self-register a passwd entry on boot.
     */
    @Test
    void dockerfile_prepares_arbitrary_uid_home_and_passwd() throws IOException {
        assumeTrue(Files.isRegularFile(DOCKERFILE), "SandboxDockerfile not found at " + DOCKERFILE);
        String text = Files.readString(DOCKERFILE);

        assertThat(text)
                .as("UC-17 — ENV HOME pins a resolvable home for an arbitrary uid with no passwd entry")
                .contains("ENV HOME=/home/claude");
        assertThat(text)
                .as("UC-17 — $HOME must be group-writable with g=u so a gid-0 runtime uid can write dotfiles")
                .contains("chmod -R g=u /home/claude");
        assertThat(text)
                .as("UC-17 — /etc/passwd must be group-0-owned so the entrypoint can append a passwd line")
                .contains("chgrp 0 /etc/passwd");
        assertThat(text)
                .as("UC-17 — /etc/passwd must be group-writable for the gid-0 self-register on boot")
                .contains("chmod g+w /etc/passwd");
    }

    /**
     * AC6 (runtime half, static guard) — the entrypoint must
     * self-register a {@code /etc/passwd} entry for the running uid
     * when getpwuid would otherwise fail, and it must do so BEFORE the
     * first {@code $HOME}-resolving call (the {@code ~/.claude.json}
     * symlink), or ssh/git/gh error with "no such user".
     */
    @Test
    void entrypoint_self_registers_passwd_entry_before_any_home_resolving_call() throws IOException {
        assumeTrue(Files.isRegularFile(ENTRYPOINT), "entrypoint.sh not found at " + ENTRYPOINT);
        String text = Files.readString(ENTRYPOINT);

        // getent-guarded append (idempotent: a resolvable uid is a no-op).
        assertThat(text)
                .as("UC-17 — getent-guarded passwd self-register")
                .contains("getent passwd \"$(id -u)\"")
                .contains(">> /etc/passwd");
        // The synthesised line pins gid 0 and the image's HOME.
        assertThat(text)
                .as("UC-17 — synthesised passwd line pins gid 0 and /home/claude")
                .contains("sandbox:x:$(id -u):0:sandbox:/home/claude:/bin/sh");

        // Ordering: the passwd append MUST precede the first $HOME-resolving
        // operation (the ~/.claude.json symlink), per the entrypoint comment
        // "MUST be the first thing we do".
        int passwdIdx = text.indexOf(">> /etc/passwd");
        int homeUseIdx = text.indexOf(".claude.json");
        assertThat(passwdIdx).as("passwd append present").isGreaterThanOrEqualTo(0);
        assertThat(homeUseIdx).as(".claude.json handling present").isGreaterThanOrEqualTo(0);
        assertThat(passwdIdx)
                .as("UC-17 — passwd self-register MUST run before the first $HOME-resolving call")
                .isLessThan(homeUseIdx);
    }

    /**
     * AC5 (static guard) — the compose service must run as the
     * server-injected {@code AI_SANDBOX_RUN_AS_USER}, defaulting to the
     * image's {@code claude} user when the var is unset (developer
     * mode), so install-mode sessions match the server-owned tree they
     * mount.
     */
    @Test
    void compose_runs_session_as_injected_run_as_user_with_claude_default() throws IOException {
        assumeTrue(Files.isRegularFile(COMPOSE), "docker-compose.yml not found at " + COMPOSE);
        String text = Files.readString(COMPOSE);

        assertThat(text)
                .as("UC-17 — session container runs as ${AI_SANDBOX_RUN_AS_USER:-claude} (compose user:)")
                .contains("user: \"${AI_SANDBOX_RUN_AS_USER:-claude}\"");
    }

    /**
     * AC7 (static guard) — {@code spawn.sh} must pre-create BOTH resolved
     * bind-mount source dirs (shared and isolated) BEFORE {@code compose
     * up}, so Docker never auto-creates a missing source as {@code
     * root:root} (which a non-root session container then cannot write).
     * Created here as the (server) user who runs spawn.sh, the dirs get
     * the right owner up front.
     */
    @Test
    void spawn_precreates_bind_mount_dirs_before_compose_up() throws IOException {
        assumeTrue(Files.isRegularFile(SPAWN), "spawn.sh not found at " + SPAWN);
        String text = Files.readString(SPAWN);

        String precreate = "mkdir -p \"$WORKSPACE_HOST_PATH\" \"$CLAUDE_CONFIG_HOST_PATH\"";
        assertThat(text)
                .as("UC-17 AC7 — spawn.sh pre-creates both bind-mount source dirs unconditionally")
                .contains(precreate);

        int mkdirIdx = text.indexOf(precreate);
        int upIdx = text.indexOf("up -d");
        assertThat(mkdirIdx).as("pre-create line present").isGreaterThanOrEqualTo(0);
        assertThat(upIdx).as("compose up present").isGreaterThanOrEqualTo(0);
        assertThat(mkdirIdx)
                .as("UC-17 AC7 — bind-mount dirs MUST be created before `compose up` so Docker "
                        + "never auto-creates them as root")
                .isLessThan(upIdx);
    }
}
