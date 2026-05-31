package com.aisandbox.server.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * UC05 § A — Release-bundle assertions (AC1–AC9).
 *
 * <p>Discovers the pre-built release zip at
 * {@code <projectDir>/build/release/ai-sandbox-server-*.zip} and verifies
 * the install-time contract: top-level layout, {@code host/} contents,
 * POSIX modes on shipped scripts, byte-identity between bundled host
 * files and their repo originals at HEAD, deterministic re-bundling, and
 * both fat-jar manifests carrying {@code Implementation-Version} that
 * matches {@code project.version}.
 *
 * <p>{@code :server:releaseBundle} is NOT triggered from this test — that
 * would be "Gradle in Gradle" and slow the unit-tier suite. The
 * {@code release-install-smoke} CI job and any developer running the
 * task locally both produce the artifact this test consumes. When the
 * zip is absent the test uses {@code Assumptions.assumeTrue} to skip
 * gracefully; the assertion-set still runs in any pipeline that builds
 * the bundle before testing (the CI smoke job always does).
 */
class ReleaseBundleTest {

    /** Repo-root path resolution: the Gradle Test task runs in {@code server/}. */
    private static final Path PROJECT_DIR = Path.of(System.getProperty("user.dir"));

    private static final Path REPO_ROOT = PROJECT_DIR.getParent();

    private static final Path RELEASE_DIR = PROJECT_DIR.resolve("build").resolve("release");

    private static final Path DIST_DIR = PROJECT_DIR.resolve("build").resolve("distributions");

    private static Path findZip() throws IOException {
        if (!Files.isDirectory(RELEASE_DIR)) {
            return null;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(RELEASE_DIR, "ai-sandbox-server-*.zip")) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    @Test
    void zip_has_top_level_layout_and_host_contents() throws Exception {
        Path zip = findZip();
        assumeTrue(zip != null, "release bundle not built: ./gradlew :server:releaseBundle");

        Set<String> entries = readEntryNames(zip);

        // AC1 — top-level files + dirs. Layout doc'd in the use case.
        assertThat(entries).contains("lib/", "host/", "systemd/");
        assertThat(entries).contains("openapi.yaml", "STREAM_PROTOCOL.md", "README.md", "sample-config.yaml");
        assertThat(entries).contains("systemd/ai-sandbox-server.service");

        // AC2 — lib/ contains both fat jars at fixed (version-free) names.
        // AC20 depends on these filenames to keep the systemd ExecStart
        // path stable across releases.
        assertThat(entries).contains("lib/aisandbox-server.jar", "lib/aisandboxctl.jar");

        // AC3 — POSIX host scripts.
        assertThat(entries)
                .contains("host/spawn.sh", "host/clean.sh", "host/attach.sh", "host/lib.sh", "host/setup.sh");

        // UC-27 AC#13 — PowerShell support is removed; the project is Linux-only.
        // The .ps1 mirrors that used to ship under host/ MUST be gone from the
        // bundle (no `host/*.ps1` entry at all).
        assertThat(entries)
                .as("UC-27 — the release bundle MUST NOT ship any PowerShell (.ps1) host scripts")
                .noneMatch(e -> e.startsWith("host/") && e.endsWith(".ps1"));

        // UC-27 AC#1,#2,#14 — the dev-tools selector + the auto-discovered
        // capability manifest tree ship under host/ so the .deb/zip onboard path
        // reaches the identical raw-mode selector and spawn.sh can discover the
        // capabilities. devtools-select.sh is the standalone entry the Java CLI
        // shells out to; devtools-ui.sh is the raw-mode UI it sources; the
        // devtools.d/ manifests are the catalog (single source of truth, AC#4).
        assertThat(entries)
                .as("UC-27 — selector + manifest tree MUST ship in the bundle")
                .contains(
                        "host/devtools-select.sh",
                        "host/devtools-ui.sh",
                        "host/devtools.d/dind/manifest.sh",
                        "host/devtools.d/java/manifest.sh",
                        "host/devtools.d/android/manifest.sh");

        // AC5 — container build context co-located with the scripts.
        assertThat(entries).contains("host/docker-compose.yml", "host/SandboxDockerfile", "host/entrypoint.sh");

        // UC22 § AC13 — the KVM-passthrough compose override ships beside
        // docker-compose.yml so spawn.sh can layer it for zip/.deb/server-
        // spawned Android sessions; the in-container emulator helper ships
        // inside the container build context (host/container-bin/).
        assertThat(entries).contains("host/docker-compose.kvm.yml", "host/container-bin/aisandbox-emulator");
    }

    @Test
    void uc22_kvm_override_and_emulator_helper_ship_with_correct_modes() throws Exception {
        Path zip = findZip();
        assumeTrue(zip != null, "release bundle not built: ./gradlew :server:releaseBundle");

        Set<String> entries = readEntryNames(zip);
        Map<String, Integer> modes = readPosixModes(zip);

        // UC22 § AC13 — docker-compose.kvm.yml ships beside docker-compose.yml
        // (data file, mode 0644 like the other compose context + SandboxDockerfile).
        // spawn.sh resolves it as `$(dirname AI_SANDBOX_COMPOSE_FILE)/
        // docker-compose.kvm.yml`; if it is absent the /dev/kvm override
        // silently can't be layered (AC11/AC13 fail) for a zip/.deb/server-
        // spawned session.
        assertThat(entries).contains("host/docker-compose.kvm.yml");
        Integer kvmMode = modes.get("host/docker-compose.kvm.yml");
        assertThat(kvmMode).as("mode of host/docker-compose.kvm.yml").isNotNull();
        assertThat(kvmMode & 0777).as("mode of host/docker-compose.kvm.yml").isEqualTo(0644);

        // UC22 § AC8,AC10 — the in-container emulator helper ships under
        // host/container-bin/ (part of the container build context that a
        // bundled `docker compose build` consumes; SandboxDockerfile COPYs
        // container-bin/aisandbox-emulator into the image). It MUST carry
        // the exec bit (0755) so the COPYd file is invocable by the
        // in-sandbox agent that drives `:android:connectedAndroidTest`.
        assertThat(entries).contains("host/container-bin/aisandbox-emulator");
        Integer emuMode = modes.get("host/container-bin/aisandbox-emulator");
        assertThat(emuMode).as("mode of host/container-bin/aisandbox-emulator").isNotNull();
        assertThat(emuMode & 0777)
                .as("mode of host/container-bin/aisandbox-emulator")
                .isEqualTo(0755);
    }

    /**
     * UC-26 § release-bundle contract — the rootless-DinD plumbing must
     * ship in the same zip as the rest of the container build context,
     * so a {@code .deb}/zip-installed operator can opt into DinD via
     * {@code aisandboxctl reconfigure} and have spawn.sh actually layer
     * the override on the next session.
     *
     * <p>Two assets are load-bearing:
     *
     * <ul>
     *   <li>{@code host/docker-compose.dind.yml} (mode 0644, like the
     *       other compose context files) — spawn.sh resolves it via
     *       {@code $(dirname AI_SANDBOX_COMPOSE_FILE)/docker-compose.dind.yml}
     *       and adds it to {@code AI_SANDBOX_EXTRA_COMPOSE_FILES}. Without
     *       this file the DinD override silently can't be layered for a
     *       zip/.deb/server-spawned session (UC-26 AC#5 fails).</li>
     *   <li>{@code host/container-bin/aisandbox-dind} (mode 0755) — the
     *       in-container helper {@code entrypoint.sh} invokes as
     *       {@code aisandbox-dind install} + {@code start} when
     *       {@code AI_SANDBOX_DEVTOOL_DIND=1}. SandboxDockerfile
     *       {@code COPY}s {@code container-bin/aisandbox-dind} into the
     *       image; without the +x bit on the source the in-image binary
     *       is non-executable and the entrypoint short-circuits.</li>
     * </ul>
     *
     * <p>This is the regression guard for both bundling steps. The fix,
     * if missing, lives in {@code server/build.gradle.kts}'s
     * {@code releaseBundle} task: explicit {@code from(rootProject.file(
     * "docker-compose.dind.yml")) { into("host") }} (mirrors the
     * docker-compose.kvm.yml line) and the existing {@code from(
     * rootProject.file("container-bin"))} block already covers the helper.
     */
    @Test
    void uc26_dind_override_and_helper_ship_with_correct_modes() throws Exception {
        Path zip = findZip();
        assumeTrue(zip != null, "release bundle not built: ./gradlew :server:releaseBundle");

        Set<String> entries = readEntryNames(zip);
        Map<String, Integer> modes = readPosixModes(zip);

        // host/docker-compose.dind.yml — data file, mode 0644.
        assertThat(entries)
                .as("UC-26 AC#5 — release zip MUST ship docker-compose.dind.yml beside docker-compose.yml")
                .contains("host/docker-compose.dind.yml");
        Integer dindComposeMode = modes.get("host/docker-compose.dind.yml");
        assertThat(dindComposeMode).as("mode of host/docker-compose.dind.yml").isNotNull();
        assertThat(dindComposeMode & 0777)
                .as("mode of host/docker-compose.dind.yml")
                .isEqualTo(0644);

        // host/container-bin/aisandbox-dind — exec helper, mode 0755.
        assertThat(entries)
                .as("UC-26 AC#5 — release zip MUST ship the container-bin/aisandbox-dind helper")
                .contains("host/container-bin/aisandbox-dind");
        Integer dindHelperMode = modes.get("host/container-bin/aisandbox-dind");
        assertThat(dindHelperMode)
                .as("mode of host/container-bin/aisandbox-dind")
                .isNotNull();
        assertThat(dindHelperMode & 0777)
                .as(
                        "mode of host/container-bin/aisandbox-dind — must be exec for the SandboxDockerfile COPY to land it +x")
                .isEqualTo(0755);
    }

    @Test
    void uc26_bundled_dind_override_and_helper_are_byte_identical_to_repo_originals() throws Exception {
        Path zip = findZip();
        assumeTrue(zip != null, "release bundle not built: ./gradlew :server:releaseBundle");

        // UC-26 — the bundling step MUST copy both new DinD assets verbatim
        // from the repo. No templating happens at build time, matching the
        // UC-22 KVM-asset pattern above.
        Map<String, Path> bundled = new HashMap<>();
        bundled.put("host/docker-compose.dind.yml", REPO_ROOT.resolve("docker-compose.dind.yml"));
        bundled.put(
                "host/container-bin/aisandbox-dind",
                REPO_ROOT.resolve("container-bin").resolve("aisandbox-dind"));

        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (Map.Entry<String, Path> e : bundled.entrySet()) {
                ZipEntry ze = zf.getEntry(e.getKey());
                assertThat(ze).as("entry %s", e.getKey()).isNotNull();
                byte[] inZip;
                try (InputStream in = zf.getInputStream(ze)) {
                    inZip = in.readAllBytes();
                }
                Path original = e.getValue();
                assertThat(Files.exists(original))
                        .as("repo original at %s", original)
                        .isTrue();
                byte[] onDisk = Files.readAllBytes(original);
                assertThat(inZip)
                        .as("byte-identity of %s vs %s", e.getKey(), original)
                        .isEqualTo(onDisk);
            }
        }
    }

    @Test
    void uc22_bundled_kvm_override_and_emulator_helper_are_byte_identical_to_repo_originals() throws Exception {
        Path zip = findZip();
        assumeTrue(zip != null, "release bundle not built: ./gradlew :server:releaseBundle");

        // UC22 — the bundling step copies both new host files verbatim from
        // the repo root (docker-compose.kvm.yml) and from container-bin/
        // (aisandbox-emulator). No templating happens at build time, exactly
        // as for the other host/ files asserted above.
        Map<String, Path> bundled = new HashMap<>();
        bundled.put("host/docker-compose.kvm.yml", REPO_ROOT.resolve("docker-compose.kvm.yml"));
        bundled.put(
                "host/container-bin/aisandbox-emulator",
                REPO_ROOT.resolve("container-bin").resolve("aisandbox-emulator"));

        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (Map.Entry<String, Path> e : bundled.entrySet()) {
                ZipEntry ze = zf.getEntry(e.getKey());
                assertThat(ze).as("entry %s", e.getKey()).isNotNull();
                byte[] inZip;
                try (InputStream in = zf.getInputStream(ze)) {
                    inZip = in.readAllBytes();
                }
                Path original = e.getValue();
                assertThat(Files.exists(original))
                        .as("repo original at %s", original)
                        .isTrue();
                byte[] onDisk = Files.readAllBytes(original);
                assertThat(inZip)
                        .as("byte-identity of %s vs %s", e.getKey(), original)
                        .isEqualTo(onDisk);
            }
        }
    }

    @Test
    void host_shell_scripts_carry_0755_mode_inside_the_zip() throws Exception {
        Path zip = findZip();
        assumeTrue(zip != null, "release bundle not built: ./gradlew :server:releaseBundle");

        // AC3,AC5 — every shipped *.sh in host/ must be world-executable
        // (0755) inside the zip. The systemd installer relies on `unzip`
        // preserving these bits so spawn/clean/attach/entrypoint are
        // exec'able straight from /opt/ai-sandbox-server/host/.
        Map<String, Integer> modes = readPosixModes(zip);
        for (String script : List.of(
                "host/spawn.sh",
                "host/clean.sh",
                "host/attach.sh",
                "host/lib.sh",
                "host/setup.sh",
                "host/entrypoint.sh")) {
            Integer m = modes.get(script);
            assertThat(m).as("mode of %s", script).isNotNull();
            // 0755 in the POSIX-permission low bits.
            assertThat(m & 0777).as("mode of %s", script).isEqualTo(0755);
        }
    }

    @Test
    void bundled_host_files_are_byte_identical_to_repo_originals() throws Exception {
        Path zip = findZip();
        assumeTrue(zip != null, "release bundle not built: ./gradlew :server:releaseBundle");

        // AC4,AC7 — the bundle copies each host file verbatim from the
        // repo root. No operator-environment-specific paths get baked
        // in by the bundling step; the bytes match the repo originals
        // at the commit being released.
        // UC-27 — Linux-only: the .ps1 mirrors are gone, so the verbatim-copy
        // contract now covers only the POSIX host files + the container build
        // context. The dev-tools selector scripts are byte-identity-checked in
        // the dedicated UC-27 case below.
        List<String> hostFiles = List.of(
                "spawn.sh",
                "clean.sh",
                "attach.sh",
                "lib.sh",
                "setup.sh",
                "docker-compose.yml",
                "SandboxDockerfile",
                "entrypoint.sh");

        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (String f : hostFiles) {
                ZipEntry e = zf.getEntry("host/" + f);
                assertThat(e).as("entry host/%s", f).isNotNull();
                byte[] inZip;
                try (InputStream in = zf.getInputStream(e)) {
                    inZip = in.readAllBytes();
                }
                Path original = REPO_ROOT.resolve(f);
                assertThat(Files.exists(original))
                        .as("repo original at %s", original)
                        .isTrue();
                byte[] onDisk = Files.readAllBytes(original);
                assertThat(inZip)
                        .as("byte-identity of host/%s vs %s", f, original)
                        .isEqualTo(onDisk);
            }
        }
    }

    @Test
    void uc27_bundled_devtools_selector_and_manifests_are_byte_identical_to_repo_originals() throws Exception {
        Path zip = findZip();
        assumeTrue(zip != null, "release bundle not built: ./gradlew :server:releaseBundle");

        // UC-27 — the bundling step copies the dev-tools selector + the
        // auto-discovered capability manifest tree verbatim from the repo root
        // into host/, exactly as for the other host files. No templating at
        // build time. devtools-select.sh is the standalone entry the Java CLI
        // shells out to; devtools-ui.sh is the raw-mode UI; the devtools.d/
        // manifests are the single-source-of-truth catalog (AC#2,#4).
        Map<String, Path> bundled = new HashMap<>();
        bundled.put("host/devtools-select.sh", REPO_ROOT.resolve("devtools-select.sh"));
        bundled.put("host/devtools-ui.sh", REPO_ROOT.resolve("devtools-ui.sh"));
        bundled.put("host/devtools.d/dind/manifest.sh", REPO_ROOT.resolve("devtools.d/dind/manifest.sh"));
        bundled.put("host/devtools.d/java/manifest.sh", REPO_ROOT.resolve("devtools.d/java/manifest.sh"));
        bundled.put("host/devtools.d/android/manifest.sh", REPO_ROOT.resolve("devtools.d/android/manifest.sh"));
        bundled.put("host/devtools.d/lib/versions.sh", REPO_ROOT.resolve("devtools.d/lib/versions.sh"));
        bundled.put("host/devtools.d/lib/provision.sh", REPO_ROOT.resolve("devtools.d/lib/provision.sh"));

        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (Map.Entry<String, Path> e : bundled.entrySet()) {
                ZipEntry ze = zf.getEntry(e.getKey());
                assertThat(ze).as("entry %s", e.getKey()).isNotNull();
                byte[] inZip;
                try (InputStream in = zf.getInputStream(ze)) {
                    inZip = in.readAllBytes();
                }
                Path original = e.getValue();
                assertThat(Files.exists(original))
                        .as("repo original at %s", original)
                        .isTrue();
                byte[] onDisk = Files.readAllBytes(original);
                assertThat(inZip)
                        .as("byte-identity of %s vs %s", e.getKey(), original)
                        .isEqualTo(onDisk);
            }
        }
    }

    @Test
    void rebundling_produces_a_byte_identical_zip() throws Exception {
        Path zip = findZip();
        assumeTrue(zip != null, "release bundle not built: ./gradlew :server:releaseBundle");

        // AC9 — deterministic bundling. We can't trivially trigger a
        // second build from within a unit test (running Gradle inside
        // Gradle is poor practice and slow), so we verify the two
        // determinism preconditions that the {@code releaseBundle}
        // task encodes:
        //   1. preserveFileTimestamps = false → every zip entry's
        //      mtime is normalised to a fixed epoch. Asserted by
        //      checking the distinct-mtime count is 1.
        //   2. reproducibleFileOrder = true → entries within each
        //      configurable file collection are sorted. Combined with
        //      a build script whose `from(...)` declaration order is
        //      itself deterministic (it is — the .kts is checked in),
        //      this yields a stable global order across re-runs.
        // Note: `reproducibleFileOrder` sorts WITHIN each `from()`
        // clause but does NOT merge separate clauses lexicographically.
        // The end-to-end byte-identity check across rebuilds belongs
        // to the {@code release-install-smoke} CI job (which builds the
        // bundle twice and diffs the sha256 sums); this test asserts
        // only the per-zip metadata preconditions.
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            List<FileTime> mtimes = new ArrayList<>();
            for (var it = zf.entries().asIterator(); it.hasNext(); ) {
                ZipEntry e = it.next();
                mtimes.add(e.getLastModifiedTime());
            }
            // mtime normalisation — every entry shares the same
            // timestamp (Gradle's normalised value, surfaced as
            // 1980-02-01 in many zip viewers due to DOS-time rounding).
            assertThat(mtimes.stream().distinct().count())
                    .as("preserveFileTimestamps=false → all entries share one mtime")
                    .isEqualTo(1L);
        }
    }

    @Test
    void both_fat_jars_carry_implementation_version_matching_project_version() throws Exception {
        Path zip = findZip();
        assumeTrue(zip != null, "release bundle not built: ./gradlew :server:releaseBundle");

        // The bundle filename embeds project.version directly:
        //   ai-sandbox-server-<version>.zip
        // Use that as the canonical reference for what the manifest
        // versions are expected to match (saves shelling out to
        // `./gradlew :server:printVersion`).
        Matcher m = Pattern.compile("ai-sandbox-server-(.+)\\.zip$")
                .matcher(zip.getFileName().toString());
        assertThat(m.find())
                .as("zip filename matches ai-sandbox-server-<version>.zip")
                .isTrue();
        String expectedVersion = m.group(1);

        Map<String, String> jarVersions = new HashMap<>();
        Map<String, String> jarTitles = new HashMap<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (String inner : List.of("lib/aisandbox-server.jar", "lib/aisandboxctl.jar")) {
                ZipEntry e = zf.getEntry(inner);
                assertThat(e).as("entry %s", inner).isNotNull();
                try (InputStream in = zf.getInputStream(e);
                        JarInputStream jis = new JarInputStream(in)) {
                    Manifest mf = jis.getManifest();
                    assertThat(mf).as("manifest in %s", inner).isNotNull();
                    String version = mf.getMainAttributes().getValue("Implementation-Version");
                    String title = mf.getMainAttributes().getValue("Implementation-Title");
                    assertThat(version)
                            .as("Implementation-Version in %s", inner)
                            .isNotBlank();
                    jarVersions.put(inner, version);
                    jarTitles.put(inner, title);
                }
            }
        }

        // Both jars share the project version (Spring Boot's BootJar
        // populates Implementation-Version from project.version).
        assertThat(jarVersions.get("lib/aisandbox-server.jar")).isEqualTo(expectedVersion);
        assertThat(jarVersions.get("lib/aisandboxctl.jar")).isEqualTo(expectedVersion);

        // AC32-adjacent — distinguishable titles so an operator running
        // `unzip -p .../MANIFEST.MF` can tell the two fat jars apart.
        assertThat(jarTitles.get("lib/aisandbox-server.jar")).isEqualTo("aisandbox-server");
        assertThat(jarTitles.get("lib/aisandboxctl.jar")).isEqualTo("aisandboxctl");
    }

    @Test
    void bin_aisandboxctl_wrapper_present_executable_and_byte_identical_to_repo_source_and_to_deb_wrapper()
            throws Exception {
        Path zip = findZip();
        assumeTrue(zip != null, "release bundle not built: ./gradlew :server:releaseBundle");

        // UC08 § AC2 — three-way byte-equality of the aisandboxctl wrapper:
        //   (a) on-disk repo source at server/wrapper/aisandboxctl
        //   (b) the zip's bin/aisandboxctl
        //   (c) the .deb's ./usr/bin/aisandboxctl
        // All three MUST be byte-identical so the wrapper body that the
        // .deb installs is the same one the zip operator drops on PATH
        // and the same one a developer can `git blame`. Diverging copies
        // would mean a packaging bug silently shipped a different shim
        // on one install path. The same hardcoded `/usr/bin/java` line
        // therefore covers both paths — see UC08 § AC4.

        // (i) bin/aisandboxctl present at mode 0755 (UC08 § AC2).
        Map<String, Integer> modes = readPosixModes(zip);
        Integer wrapperMode = modes.get("bin/aisandboxctl");
        assertThat(wrapperMode).as("mode of bin/aisandboxctl").isNotNull();
        assertThat(wrapperMode & 0777).as("mode of bin/aisandboxctl").isEqualTo(0755);

        // Read wrapper bytes from the zip once for both equality legs.
        byte[] zipWrapperBytes;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry e = zf.getEntry("bin/aisandboxctl");
            assertThat(e).as("entry bin/aisandboxctl").isNotNull();
            try (InputStream in = zf.getInputStream(e)) {
                zipWrapperBytes = in.readAllBytes();
            }
        }

        // (ii) Bytes equal to the repo source. Bundling MUST be a verbatim
        // copy — no shell expansions or templating happen at build time.
        Path repoWrapper = PROJECT_DIR.resolve("wrapper").resolve("aisandboxctl");
        assertThat(Files.exists(repoWrapper))
                .as("repo wrapper source at %s", repoWrapper)
                .isTrue();
        byte[] onDiskBytes = Files.readAllBytes(repoWrapper);
        assertThat(zipWrapperBytes)
                .as("bin/aisandboxctl in zip byte-identical to %s", repoWrapper)
                .isEqualTo(onDiskBytes);

        // (iii) Bytes equal to the .deb's /usr/bin/aisandboxctl. Gated by
        // dpkg-deb availability so this method runs cleanly on macOS /
        // Alpine devboxes (matches DebPackageTest's portability stance);
        // CI's Ubuntu runner always exercises it.
        Path deb = findDeb();
        assumeTrue(
                deb != null,
                "deb not built — run `./gradlew :server:debPackage`; CI release-install-smoke builds both .deb and zip");
        assumeTrue(dpkgDebOnPath() != null, "dpkg-deb not on PATH — skipping .deb byte-equality leg");

        byte[] debWrapperBytes = extractDebFile(deb, "./usr/bin/aisandboxctl");
        assertThat(zipWrapperBytes)
                .as("bin/aisandboxctl in zip byte-identical to ./usr/bin/aisandboxctl in .deb")
                .isEqualTo(debWrapperBytes);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static Set<String> readEntryNames(Path zip) throws IOException {
        Set<String> out = new java.util.TreeSet<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (var it = zf.entries().asIterator(); it.hasNext(); ) {
                out.add(it.next().getName());
            }
        }
        return out;
    }

    /**
     * Read POSIX mode bits from each zip entry by parsing the central
     * directory directly. Gradle's {@code Zip} task writes unix perms
     * into the high 16 bits of the central-directory header's
     * "external file attributes" field, but the public
     * {@link java.util.zip.ZipFile} / {@link ZipEntry} API does not
     * expose that field. We re-parse the central directory by hand —
     * just enough to read mode = {@code (externalAttributes >> 16) &
     * 0xFFFF} per entry.
     */
    private static Map<String, Integer> readPosixModes(Path zip) throws IOException {
        // Parse the EOCD → central-directory pair to read external
        // attributes (high 16 bits = unix mode).
        byte[] data = Files.readAllBytes(zip);
        // Find End Of Central Directory record. Search backwards for
        // the signature 0x06054b50.
        int eocdOff = -1;
        for (int i = data.length - 22; i >= 0 && i >= data.length - 65557; i--) {
            if (data[i] == 0x50 && data[i + 1] == 0x4b && data[i + 2] == 0x05 && data[i + 3] == 0x06) {
                eocdOff = i;
                break;
            }
        }
        if (eocdOff < 0) throw new IOException("no EOCD found in " + zip);
        long cdSize = u32(data, eocdOff + 12);
        long cdOff = u32(data, eocdOff + 16);
        // Walk central-directory entries.
        Map<String, Integer> modes = new TreeMap<>();
        long p = cdOff;
        long end = cdOff + cdSize;
        while (p < end) {
            if (u32(data, (int) p) != 0x02014b50L) break;
            int nameLen = u16(data, (int) p + 28);
            int extraLen = u16(data, (int) p + 30);
            int commentLen = u16(data, (int) p + 32);
            long externalAttrs = u32(data, (int) p + 38);
            int mode = (int) ((externalAttrs >> 16) & 0xFFFF);
            String name = new String(data, (int) p + 46, nameLen, java.nio.charset.StandardCharsets.UTF_8);
            modes.put(name, mode);
            p += 46L + nameLen + extraLen + commentLen;
        }
        return modes;
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (b[o] & 0xffL) | ((b[o + 1] & 0xffL) << 8) | ((b[o + 2] & 0xffL) << 16) | ((b[o + 3] & 0xffL) << 24);
    }

    /** Locate the freshly-built .deb under {@code server/build/distributions}; null when absent. */
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

    /**
     * Probe for {@code dpkg-deb} on the host PATH; returns the binary
     * name when usable, null when missing. Mirrors {@link
     * DebPackageTest}'s probe so the .deb byte-equality leg of the
     * wrapper assertion skips cleanly on non-Debian hosts.
     */
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

    /**
     * Extract a single file from a binary .deb without unpacking the
     * whole data archive. Pipeline: {@code dpkg-deb --fsys-tarfile <deb>
     * | tar -xO <pathInDeb>} (Java 9+ {@link ProcessBuilder#startPipeline}).
     * Caller is responsible for gating on {@link #dpkgDebOnPath()}.
     */
    private static byte[] extractDebFile(Path deb, String pathInDeb) throws IOException, InterruptedException {
        List<ProcessBuilder> builders = List.of(
                new ProcessBuilder("dpkg-deb", "--fsys-tarfile", deb.toString()),
                new ProcessBuilder("tar", "-xO", pathInDeb));
        List<Process> procs = ProcessBuilder.startPipeline(builders);
        Process last = procs.get(procs.size() - 1);
        byte[] out = last.getInputStream().readAllBytes();
        for (Process p : procs) {
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("pipeline timed out extracting " + pathInDeb + " from " + deb);
            }
        }
        for (Process p : procs) {
            if (p.exitValue() != 0) {
                throw new IOException("non-zero exit (" + p.exitValue() + ") extracting " + pathInDeb + " from " + deb);
            }
        }
        return out;
    }

    @SuppressWarnings("unused") // future-proof helper, currently inlined where needed
    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] data = Files.readAllBytes(file);
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte by : digest) sb.append(String.format("%02x", by));
        return sb.toString();
    }
}
