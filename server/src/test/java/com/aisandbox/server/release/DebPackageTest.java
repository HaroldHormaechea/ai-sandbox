package com.aisandbox.server.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * UC07 Feature E — assertions on the {@code :server:debPackage} output.
 *
 * <p>The test consumes the pre-built {@code ai-sandbox-server_*_amd64.deb}
 * under {@code server/build/distributions/}; it does NOT trigger the
 * Gradle task (Gradle-in-Gradle would slow the unit-tier suite and risk
 * deadlocking the build cache). The CI release-install-smoke job runs
 * {@code :server:debPackage} before {@code :server:test}; local-dev
 * runs need to invoke it once via {@code ./gradlew :server:debPackage}.
 *
 * <p>Verification uses {@code dpkg-deb} (the canonical Debian-package
 * inspector). On hosts that lack the tool (Alpine, macOS without Brew
 * dpkg) the test {@link org.junit.jupiter.api.Assumptions#assumeTrue
 * skips} rather than fails — matches {@link ReleaseBundleTest}'s
 * portability pattern. The CI runner is Ubuntu and always has it.
 *
 * <p>Three inspection facets:
 *
 * <ul>
 *   <li>{@code dpkg-deb -c} — contents listing: jars, host scripts (with
 *       mode 0755), systemd unit, doc files.</li>
 *   <li>{@code dpkg-deb -f} — control fields: package metadata, Depends
 *       line, Maintainer, Description.</li>
 *   <li>{@code dpkg-deb -e} — control archive: postinst / prerm / postrm
 *       extracted and executable.</li>
 * </ul>
 */
class DebPackageTest {

    /** Test JVM cwd is {@code server/}; {@code build/distributions} is where jdeb writes. */
    private static final Path PROJECT_DIR = Path.of(System.getProperty("user.dir"));

    private static final Path DIST_DIR = PROJECT_DIR.resolve("build").resolve("distributions");

    private static Path findDeb() throws IOException {
        if (!Files.isDirectory(DIST_DIR)) {
            return null;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(DIST_DIR, "ai-sandbox-server_*_amd64.deb")) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    /** Probe for {@code dpkg-deb} on the host PATH; returns null when missing. */
    private static String dpkgDebOnPath() {
        try {
            Process p = new ProcessBuilder("dpkg-deb", "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? "dpkg-deb" : null;
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    @Test
    void deb_contents_carry_jars_host_scripts_systemd_unit_and_docs() throws Exception {
        Path deb = findDeb();
        assumeTrue(
                deb != null,
                "deb not built yet — run `./gradlew :server:debPackage` to produce build/distributions/ai-sandbox-server_*_amd64.deb");
        assumeTrue(dpkgDebOnPath() != null, "dpkg-deb not on PATH — skipping (CI runner always has it)");

        String contents = runCapturing("dpkg-deb", "-c", deb.toString());

        // Two executable jars under /opt/ai-sandbox-server/lib.
        assertThat(contents).contains("/opt/ai-sandbox-server/lib/aisandbox-server.jar");
        assertThat(contents).contains("/opt/ai-sandbox-server/lib/aisandboxctl.jar");

        // Systemd unit at the Debian-canonical location.
        assertThat(contents).contains("/lib/systemd/system/ai-sandbox-server.service");

        // Host scripts — every shell script in the bundled host tree
        // MUST be mode 0755 (the host scripts are operator-runnable and
        // the systemd unit invokes them directly). dpkg-deb -c emits
        // the file-mode in the first column of each row.
        for (String name : new String[] {"spawn.sh", "clean.sh", "attach.sh", "lib.sh", "setup.sh", "entrypoint.sh"}) {
            String hostPath = "/opt/ai-sandbox-server/host/" + name;
            assertThat(contents).as("missing entry %s", hostPath).contains(hostPath);
            // Find the row carrying this path and verify its mode is
            // 0755 (-rwxr-xr-x in the dpkg-deb listing).
            assertThat(modeLineFor(contents, hostPath))
                    .as("mode on %s", hostPath)
                    .startsWith("-rwxr-xr-x");
        }

        // Documentation + sample config at the bundle root.
        for (String name : new String[] {"README.md", "openapi.yaml", "sample-config.yaml", "STREAM_PROTOCOL.md"}) {
            assertThat(contents).contains("/opt/ai-sandbox-server/" + name);
        }
    }

    @Test
    void deb_control_fields_match_published_metadata() throws Exception {
        Path deb = findDeb();
        assumeTrue(
                deb != null,
                "deb not built yet — run `./gradlew :server:debPackage` to produce build/distributions/ai-sandbox-server_*_amd64.deb");
        assumeTrue(dpkgDebOnPath() != null, "dpkg-deb not on PATH — skipping (CI runner always has it)");

        String control = runCapturing("dpkg-deb", "-f", deb.toString());

        // Identity + classification.
        assertThat(control).containsPattern("(?m)^Package:\\s+ai-sandbox-server\\s*$");
        assertThat(control).containsPattern("(?m)^Architecture:\\s+amd64\\s*$");
        assertThat(control).containsPattern("(?m)^Section:\\s+admin\\s*$");
        assertThat(control).containsPattern("(?m)^Priority:\\s+optional\\s*$");

        // Runtime deps — every one of these alternatives MUST be in the
        // Depends line. We match individual substrings so the test
        // doesn't pin field ordering or whitespace.
        assertThat(control).containsPattern("(?m)^Depends:.*$");
        for (String dep : new String[] {
            "openjdk-21-jre-headless | openjdk-21-jdk-headless", "docker.io | docker-ce", "unzip", "openssh-client"
        }) {
            assertThat(control).as("Depends line MUST include `%s`", dep).contains(dep);
        }

        // Operator-facing metadata.
        assertThat(control).containsPattern("(?m)^Maintainer:\\s+.+$");
        assertThat(control).containsPattern("(?m)^Description:\\s+.+$");
    }

    @Test
    void deb_control_scripts_are_present_and_executable(@TempDir Path tmp) throws Exception {
        Path deb = findDeb();
        assumeTrue(
                deb != null,
                "deb not built yet — run `./gradlew :server:debPackage` to produce build/distributions/ai-sandbox-server_*_amd64.deb");
        assumeTrue(dpkgDebOnPath() != null, "dpkg-deb not on PATH — skipping (CI runner always has it)");

        // dpkg-deb -e <deb> <dir> extracts the control archive into <dir>.
        runCapturing("dpkg-deb", "-e", deb.toString(), tmp.toString());

        for (String script : new String[] {"postinst", "prerm", "postrm"}) {
            Path p = tmp.resolve(script);
            assertThat(p)
                    .as("control archive MUST contain %s — see server/debian/%s", script, script)
                    .exists();
            // POSIX execute bit on the owner; dpkg-deb preserves the
            // mode from the source control.tar.
            assertThat(Files.isExecutable(p))
                    .as("control script %s MUST be executable; dpkg refuses to run a non-+x hook", p)
                    .isTrue();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────

    /** Run a process, capture stdout, assert exit 0, return captured bytes as UTF-8. */
    private static String runCapturing(String... argv) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException(String.join(" ", argv) + " timed out");
        }
        String text = new String(out);
        assertThat(p.exitValue())
                .as("`%s` exited non-zero. Output:\n%s", String.join(" ", argv), text)
                .isZero();
        return text;
    }

    /**
     * Find the {@code dpkg-deb -c} listing row whose final column ends
     * with the given absolute path; return the row's leading mode
     * column ({@code -rwxr-xr-x ...}). Returns the empty string when
     * the row is missing — assertions on the result will fail with a
     * useful context message.
     */
    private static String modeLineFor(String contents, String absolutePath) {
        for (String line : contents.split("\\R")) {
            // dpkg-deb -c row: "<mode> <owner/group> <size> <date> <time> <path>"
            // path always starts with "./" in a binary deb.
            if (line.endsWith(absolutePath) || line.contains("." + absolutePath)) {
                return line.trim();
            }
        }
        return "";
    }
}
