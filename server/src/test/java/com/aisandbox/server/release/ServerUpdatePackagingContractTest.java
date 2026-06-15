package com.aisandbox.server.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * UC-84 — source-level packaging + security contracts for the self-updater,
 * runnable in the ordinary {@code :server:test} lane (no built {@code .deb}
 * required). These mirror, in JUnit, the cross-checks the {@code server-ci.yml}
 * workflow performs at release time, so a drift is caught locally rather than
 * only in CI.
 *
 * <p>Path discovery follows {@link DebPackageTest} / {@link ReleaseBundleTest}:
 * the test JVM cwd is {@code server/}, so its parent is the repo root where
 * {@code ai-sandbox-updater.sh} lives.
 *
 * <h2>What it pins</h2>
 *
 * <ul>
 *   <li>Trigger-path 3-way literal cross-check (AC8/AC11): the Java constant,
 *       the {@code postinst install -d} target, and the {@code .path} unit's
 *       {@code DirectoryNotEmpty} all carry the SAME canonical literal — if any
 *       two drift, a server-written marker never fires the updater.</li>
 *   <li>No-credentials contract (AC13): neither the updater script nor the
 *       {@code serverupdate} Java package attaches a GitHub token / Authorization
 *       header / {@code gh auth}.</li>
 *   <li>Parameter-free contract (AC11/AC12): the updater ignores {@code "$@"}
 *       and reads no env-borne config for its target.</li>
 * </ul>
 */
class ServerUpdatePackagingContractTest {

    /** Test JVM cwd is {@code server/}. */
    private static final Path SERVER_DIR = Path.of(System.getProperty("user.dir"));

    private static final Path REPO_ROOT = SERVER_DIR.getParent();

    private static final String CANONICAL_TRIGGER_DIR = "/var/lib/ai-sandbox-server/update-trigger";

    private static final Path TRIGGER_SERVICE_JAVA =
            SERVER_DIR.resolve("src/main/java/com/aisandbox/server/serverupdate/service/UpdateTriggerService.java");
    private static final Path POSTINST = SERVER_DIR.resolve("debian/postinst");
    private static final Path PATH_UNIT = SERVER_DIR.resolve("systemd/ai-sandbox-updater.path");
    private static final Path UPDATER_SH = REPO_ROOT == null ? null : REPO_ROOT.resolve("ai-sandbox-updater.sh");

    private static String read(Path p) throws IOException {
        return Files.readString(p);
    }

    /**
     * Returns the file's content with whole-line shell/systemd comments stripped
     * (lines whose first non-blank char is {@code #}). The prose comments in
     * these files legitimately MENTION the very strings we forbid in actual
     * directives/commands (e.g. "no gh auth", "NO EnvironmentFile here"); the
     * contract is about what the script/unit DOES, not what it documents.
     */
    private static String codeOnly(Path p) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : Files.readAllLines(p)) {
            if (line.strip().startsWith("#")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // ── Trigger-path 3-way literal cross-check (AC8/AC11) ────────────────────

    @Test
    void trigger_path_literal_is_identical_in_java_postinst_and_path_unit() throws IOException {
        assumeTrue(Files.isRegularFile(TRIGGER_SERVICE_JAVA), "run with cwd=server/");
        assumeTrue(Files.isRegularFile(POSTINST), "run with cwd=server/");
        assumeTrue(Files.isRegularFile(PATH_UNIT), "run with cwd=server/");

        // 1. Java constant — UpdateTriggerService.TRIGGER_DIR.
        assertThat(read(TRIGGER_SERVICE_JAVA))
                .as("Java constant must pin the canonical trigger dir")
                .contains("TRIGGER_DIR = \"" + CANONICAL_TRIGGER_DIR + "\"");

        // 2. postinst install -d target.
        assertThat(read(POSTINST))
                .as("postinst must pre-create exactly the canonical trigger dir")
                .containsPattern("install -d[^\\n]* " + java.util.regex.Pattern.quote(CANONICAL_TRIGGER_DIR));

        // 3. .path unit watched directory.
        assertThat(read(PATH_UNIT))
                .as(".path unit must watch exactly the canonical trigger dir")
                .contains("DirectoryNotEmpty=" + CANONICAL_TRIGGER_DIR);

        // And the updater script targets the same literal when it clears the dir blindly.
        assumeTrue(UPDATER_SH != null && Files.isRegularFile(UPDATER_SH), "updater script at repo root");
        assertThat(read(UPDATER_SH))
                .as("updater script must reference the same canonical trigger dir")
                .contains(CANONICAL_TRIGGER_DIR);
    }

    // ── No-credentials contract (AC13) ──────────────────────────────────────

    @Test
    void updater_script_uses_no_github_credentials() throws IOException {
        assumeTrue(UPDATER_SH != null && Files.isRegularFile(UPDATER_SH), "updater script at repo root");
        // Strip prose comments: they legitimately say "no token / no gh auth".
        String sh = codeOnly(UPDATER_SH);

        // No token, no Authorization header, no gh-auth, no curl basic-auth on the wire.
        assertThat(sh).doesNotContain("Authorization:");
        assertThat(sh).doesNotContain("GITHUB_TOKEN");
        assertThat(sh).doesNotContain("GH_TOKEN");
        assertThat(sh).doesNotContain("gh auth");
        assertThat(sh).doesNotContain("Bearer ");
        // curl -u / --user would smuggle basic-auth credentials.
        assertThat(sh).doesNotContainPattern("curl[^\\n|]*\\s-u\\s");
        assertThat(sh).doesNotContainPattern("curl[^\\n|]*--user");
    }

    @Test
    void serverupdate_java_package_attaches_no_authorization_header() throws IOException {
        Path pkg = SERVER_DIR.resolve("src/main/java/com/aisandbox/server/serverupdate");
        assumeTrue(Files.isDirectory(pkg), "serverupdate package present");

        try (var walk = Files.walk(pkg)) {
            for (Path java : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = read(java);
                assertThat(src)
                        .as("%s must not set an Authorization header (AC13)", java.getFileName())
                        .doesNotContain(".header(\"Authorization\"")
                        .doesNotContain("GITHUB_TOKEN")
                        .doesNotContain("Bearer ");
            }
        }
    }

    // ── Parameter-free contract (AC11/AC12) ─────────────────────────────────

    @Test
    void updater_script_is_parameter_free_and_self_determines_its_target() throws IOException {
        assumeTrue(UPDATER_SH != null && Files.isRegularFile(UPDATER_SH), "updater script at repo root");
        String sh = read(UPDATER_SH);

        // Hardcoded identity it derives the target from — never request input.
        assertThat(sh).contains("REPO=\"HaroldHormaechea/ai-sandbox\"");
        assertThat(sh).contains("TRACK=\"server-v\"");
        assertThat(sh).contains("PKG=\"ai-sandbox-server\"");
        // It explicitly acknowledges + ignores any arguments handed to it.
        assertThat(sh).containsPattern("\\[ \"\\$#\" -ne 0 \\]");
        // It verifies the downloaded package name before installing (refuses a foreign .deb).
        assertThat(sh).contains("expected '$PKG'");
    }

    // ── Packaging reach (AC16) ──────────────────────────────────────────────

    @Test
    void jdeb_packages_the_updater_script_and_systemd_units() throws IOException {
        Path buildScript = SERVER_DIR.resolve("build.gradle.kts");
        assumeTrue(Files.isRegularFile(buildScript), "server build script present");
        String gradle = read(buildScript);

        // The updater script is staged under opt/ai-sandbox-server/updater (NOT host/, so
        // the server has no exec path to it).
        assertThat(gradle).contains("ai-sandbox-updater.sh");
        assertThat(gradle).contains("opt/ai-sandbox-server/updater");
        // The systemd dir (carrying both .service and .path units) lands in lib/systemd/system.
        assertThat(gradle).contains("lib/systemd/system");
    }

    @Test
    void committed_openapi_documents_the_new_update_endpoints_and_schemas() throws IOException {
        // AC16 — new endpoints/enums must appear in the regenerated, committed OAS
        // (CI also guards drift via `git diff --exit-code -- server/openapi.yaml`).
        Path oas = SERVER_DIR.resolve("openapi.yaml");
        assumeTrue(Files.isRegularFile(oas), "committed openapi.yaml present");
        String spec = read(oas);

        assertThat(spec).contains("/v1/server/update/check");
        assertThat(spec).contains("/v1/server/update/apply");
        assertThat(spec).contains("UpdateCheckResponse");
        assertThat(spec).contains("UpdateApplyResponse");
    }

    @Test
    void postinst_creates_trigger_dir_and_enables_the_path_watch_idempotently() throws IOException {
        assumeTrue(Files.isRegularFile(POSTINST), "run with cwd=server/");
        String postinst = read(POSTINST);

        // The trigger dir is pre-created server-writable (0750, owned by the service).
        assertThat(postinst)
                .containsPattern("install -d -m 0750[^\\n]*ai-sandbox-server[^\\n]*"
                        + java.util.regex.Pattern.quote(CANONICAL_TRIGGER_DIR));
        // The path-activated watch is enabled idempotently (guarded for container/chroot hosts).
        assertThat(postinst).contains("systemctl enable --now ai-sandbox-updater.path");
    }

    @Test
    void updater_service_unit_passes_no_environment_file() throws IOException {
        Path serviceUnit = SERVER_DIR.resolve("systemd/ai-sandbox-updater.service");
        assumeTrue(Files.isRegularFile(serviceUnit), "service unit present");
        // Strip prose comments: the unit's comment legitimately says "NO EnvironmentFile here".
        String unit = codeOnly(serviceUnit);

        // No EnvironmentFile= directive (especially none from a server-writable path) so
        // injected env can never steer the root updater (AC12).
        assertThat(unit).doesNotContain("EnvironmentFile");
        assertThat(unit).contains("ExecStart=/opt/ai-sandbox-server/updater/ai-sandbox-updater.sh");
        // ExecStart carries NO arguments after the script path.
        assertThat(unit).containsPattern("ExecStart=/opt/ai-sandbox-server/updater/ai-sandbox-updater\\.sh\\s*\\n");
    }
}
