package com.aisandbox.server.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
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

        // UC08 § AC1 — /usr/bin/aisandboxctl POSIX shell wrapper.
        // The .deb owns this path directly (the zip-install path
        // ships the same wrapper at bin/aisandboxctl and asks the
        // operator to symlink it onto PATH). dpkg-deb -c MUST list
        // the entry, and its mode column MUST start with -rwxr-xr-x
        // (0755). The wrapper body itself (byte-equality with the
        // repo source and the zip's bin/aisandboxctl) is asserted by
        // ReleaseBundleTest.
        String wrapperPath = "/usr/bin/aisandboxctl";
        assertThat(contents).as("missing entry %s", wrapperPath).contains(wrapperPath);
        assertThat(modeLineFor(contents, wrapperPath))
                .as("mode on %s", wrapperPath)
                .startsWith("-rwxr-xr-x");
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
            "openjdk-21-jre-headless | openjdk-21-jdk-headless",
            "docker.io | docker-ce",
            "unzip",
            "openssh-client",
            // UC-17 — the debconf-driven onboarding wizard needs the
            // debconf frontend; it MUST be a runtime dependency.
            "debconf"
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

    // ── UC-17 — debconf control-archive contents (JDK-only parser) ────
    //
    // Unlike the dpkg-deb-based tests above, these run WITHOUT dpkg-deb on
    // PATH: they parse the .deb (an `ar` archive) → control.tar.gz (gzip+tar)
    // with the JDK alone, so the UC-17 control-archive contract is a hard
    // automated gate locally (the sandbox has no dpkg-deb / ar) AND in CI.
    // Mirrors the developer's manual "extract control.tar.gz" verification.

    /**
     * Select the most-recently-built {@code ai-sandbox-server_*_amd64.deb}
     * under {@code build/distributions/}. Picking newest-by-mtime avoids a
     * stale leftover .deb (an older version string) masking a fresh build.
     */
    private static Path newestDeb() throws IOException {
        if (!Files.isDirectory(DIST_DIR)) {
            return null;
        }
        Path newest = null;
        long best = Long.MIN_VALUE;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(DIST_DIR, "ai-sandbox-server_*_amd64.deb")) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                long m = Files.getLastModifiedTime(p).toMillis();
                if (m > best) {
                    best = m;
                    newest = p;
                }
            }
        }
        return newest;
    }

    /** A single tar entry: POSIX mode (parsed from the octal header) + body bytes. */
    private record TarEntry(long mode, byte[] body) {
        boolean ownerExecutable() {
            return (mode & 0100) != 0;
        }
    }

    @Test
    void control_archive_carries_debconf_config_script_and_templates() throws Exception {
        Path deb = newestDeb();
        assumeTrue(
                deb != null,
                "deb not built yet — run `./gradlew :server:debPackage` to produce build/distributions/ai-sandbox-server_*_amd64.deb");

        Map<String, TarEntry> control = controlArchive(deb);

        // UC-17 — the debconf `config` maintainer script ships and is
        // executable (dpkg refuses to run a non-+x maintainer script).
        assertThat(control)
                .as("control archive MUST contain the debconf `config` script — see server/debian/config")
                .containsKey("config");
        assertThat(control.get("config").ownerExecutable())
                .as("debconf `config` MUST be executable (it is run by the frontend before postinst)")
                .isTrue();

        // UC-17 — the debconf `templates` (questions + types) ships as data.
        assertThat(control)
                .as("control archive MUST contain the debconf `templates` file — see server/debian/templates")
                .containsKey("templates");

        // The standard maintainer scripts remain present + executable.
        for (String script : new String[] {"postinst", "prerm", "postrm"}) {
            assertThat(control).containsKey(script);
            assertThat(control.get(script).ownerExecutable())
                    .as("maintainer script %s MUST be executable", script)
                    .isTrue();
        }

        // The onboarding question is registered in the shipped templates, and
        // debconf is declared as a runtime dependency in the control file.
        String templates = new String(control.get("templates").body(), StandardCharsets.UTF_8);
        assertThat(templates)
                .as("UC-17 — the onboarding question MUST be registered in the templates")
                .contains("ai-sandbox-server/onboard");
        String controlFile = new String(control.get("control").body(), StandardCharsets.UTF_8);
        assertThat(controlFile)
                .as("UC-17 — debconf MUST be a Depends in the packaged control file")
                .containsPattern("(?m)^Depends:.*debconf");
    }

    // ── ar + gzip + tar parsing (pure JDK) ────────────────────────────

    /** Read the {@code control.tar.gz} member of a .deb and return its tar entries by (normalised) name. */
    private static Map<String, TarEntry> controlArchive(Path deb) throws IOException {
        byte[] bytes = Files.readAllBytes(deb);
        byte[] controlTarGz = arMember(bytes, "control.tar");
        assertThat(controlTarGz)
                .as("`.deb` MUST contain a control.tar.* member (ar archive layout)")
                .isNotNull();
        return readTarGz(controlTarGz);
    }

    /**
     * Locate an {@code ar} archive member whose name starts with the given
     * prefix and return its raw bytes. {@code .deb} is a System-V {@code ar}
     * archive: an {@code "!<arch>\n"} magic followed by 60-byte member
     * headers (name[16], mtime[12], uid[6], gid[6], mode[8], size[10],
     * terminator[2]); each body is padded to a 2-byte boundary.
     */
    private static byte[] arMember(byte[] ar, String namePrefix) {
        if (ar.length < 8 || !"!<arch>\n".equals(new String(ar, 0, 8, StandardCharsets.US_ASCII))) {
            throw new IllegalStateException("not an ar archive (bad magic)");
        }
        int off = 8;
        while (off + 60 <= ar.length) {
            String name = new String(ar, off, 16, StandardCharsets.US_ASCII).trim();
            long size = Long.parseLong(
                    new String(ar, off + 48, 10, StandardCharsets.US_ASCII).trim());
            int dataStart = off + 60;
            if (name.startsWith(namePrefix)) {
                return Arrays.copyOfRange(ar, dataStart, dataStart + (int) size);
            }
            off = dataStart + (int) size;
            if ((size & 1L) == 1L) {
                off++; // 2-byte alignment padding
            }
        }
        return null;
    }

    /**
     * Gunzip + walk a ustar/GNU tar stream, returning every regular-file
     * entry keyed by its name with any leading {@code ./} stripped. tar
     * header layout: name[0..100], mode[100..108] (octal ASCII),
     * size[124..136] (octal ASCII); each entry is a 512-byte header
     * followed by the body rounded up to a 512-byte boundary; two zero
     * blocks terminate the archive.
     */
    private static Map<String, TarEntry> readTarGz(byte[] gz) throws IOException {
        Map<String, TarEntry> out = new LinkedHashMap<>();
        byte[] tar;
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            tar = gis.readAllBytes();
        }
        int off = 0;
        while (off + 512 <= tar.length) {
            String name = cString(tar, off, 100);
            if (name.isEmpty()) {
                break; // end-of-archive zero block
            }
            long mode = octal(tar, off + 100, 8);
            long size = octal(tar, off + 124, 12);
            int dataStart = off + 512;
            byte[] body = Arrays.copyOfRange(tar, dataStart, dataStart + (int) size);
            out.put(normalizeTarName(name), new TarEntry(mode, body));
            int blocks = (int) ((size + 511) / 512);
            off = dataStart + blocks * 512;
        }
        return out;
    }

    private static String normalizeTarName(String name) {
        String n = name;
        while (n.startsWith("./")) {
            n = n.substring(2);
        }
        while (n.endsWith("/")) {
            n = n.substring(0, n.length() - 1);
        }
        return n;
    }

    /** Read a NUL-terminated (or field-length) ASCII string from a fixed-width tar field. */
    private static String cString(byte[] buf, int offset, int len) {
        int end = offset;
        int limit = offset + len;
        while (end < limit && buf[end] != 0) {
            end++;
        }
        return new String(buf, offset, end - offset, StandardCharsets.US_ASCII).trim();
    }

    /** Parse an octal ASCII tar numeric field (space/NUL padded). */
    private static long octal(byte[] buf, int offset, int len) {
        long v = 0;
        for (int i = offset; i < offset + len; i++) {
            int ch = buf[i] & 0xff;
            if (ch == 0 || ch == ' ') {
                continue;
            }
            if (ch < '0' || ch > '7') {
                continue;
            }
            v = (v << 3) + (ch - '0');
        }
        return v;
    }
}
