package com.aisandbox.server.systemd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * UC11 § AC2 — parsed-content assertions on the packaged systemd unit
 * file {@code server/systemd/ai-sandbox-server.service}.
 *
 * <p>UC10 closed the chain-cleaning bug and the Android client reached
 * {@code POST /v1/enrollment} end-to-end for the first time on the
 * potato-server host, immediately surfacing the systemd-sandbox
 * read-only bug: the unit declared {@code ProtectSystem=strict} +
 * {@code ReadOnlyPaths=/etc/ai-sandbox-server …} which mounted the
 * entire {@code /etc/ai-sandbox-server} tree read-only inside the
 * service's mount namespace, including {@code clients/} where
 * {@link com.aisandbox.server.enrollment.facade.EnrollmentFacade} writes
 * the freshly-minted cert. UC11 § AC1 carves out
 * {@code /etc/ai-sandbox-server/clients} as a {@code ReadWritePaths=}
 * exception; this test parses the unit file's {@code [Service]} section
 * and asserts that carve-out is in place. Catches regressions if anyone
 * edits the unit file again.
 *
 * <p>Path discovery follows the same {@code System.getProperty("user.dir")}
 * pattern as {@link com.aisandbox.server.release.DebPackageTest} and
 * {@link com.aisandbox.server.release.ReleaseBundleTest} — the test JVM
 * cwd is {@code server/}, so the unit file lives at
 * {@code server/systemd/ai-sandbox-server.service}.
 */
class UnitFileContractTest {

    /** Test JVM cwd is {@code server/}; the unit file lives under {@code systemd/}. */
    private static final Path PROJECT_DIR = Path.of(System.getProperty("user.dir"));

    private static final Path UNIT_FILE = PROJECT_DIR.resolve("systemd").resolve("ai-sandbox-server.service");

    /** Repo root (parent of {@code server/}) — where {@code .github/workflows} live. */
    private static final Path REPO_ROOT = PROJECT_DIR.getParent();

    /**
     * UC-85 (AC-11, layer 2) — NO shipped/packaged surface may ever activate the deterministic-gate
     * {@code replay} Spring profile. The profile substitutes recorded fixtures for the live docker
     * transcript, exposes synthetic sessions, and echoes answers instead of injecting them — it must
     * only ever be turned on by the gate harness ({@code android/gate.sh} + the {@code android-gate}
     * CI job), never by anything an operator installs or runs in production.
     *
     * <p>This reads EACH packaging / launch surface and asserts none of them name {@code replay}
     * (case-insensitive). Because the profile name appears in NONE of these files today, the simplest
     * and strongest contract is "the literal token {@code replay} is absent" — any future edit that
     * pipes {@code --spring.profiles.active=replay} or {@code SPRING_PROFILES_ACTIVE=replay} into a
     * shipped artifact reintroduces the token and fails here. Project history shows one packaging
     * surface always gets missed, so every surface is read explicitly:
     *
     * <ul>
     *   <li>the systemd unit's {@code ExecStart} + {@code Environment} (run on the host);</li>
     *   <li>the {@code .deb} maintainer scripts ({@code debian/postinst,prerm,postrm,config});</li>
     *   <li>{@code server/build.gradle.kts} (the jdeb staging + {@code releaseBundle} operator zip
     *       are assembled here, and the only profile activation it may contain is the unrelated
     *       {@code docs-only} OpenAPI-generation task — never {@code replay});</li>
     *   <li>the bundled reference config ({@code sample-config.yaml}) and the baked default
     *       {@code application.yaml};</li>
     *   <li>the production CI workflows ({@code server-ci}, {@code server-release}, {@code android-ci},
     *       {@code android-release}) — the {@code android-gate*} workflows are intentionally excluded
     *       (they ARE the gate).</li>
     * </ul>
     */
    @Test
    void no_packaging_surface_activates_the_replay_profile() throws IOException {
        List<Path> surfaces = new ArrayList<>(List.of(
                UNIT_FILE,
                PROJECT_DIR.resolve("debian").resolve("postinst"),
                PROJECT_DIR.resolve("debian").resolve("prerm"),
                PROJECT_DIR.resolve("debian").resolve("postrm"),
                PROJECT_DIR.resolve("debian").resolve("config"),
                PROJECT_DIR.resolve("debian").resolve("control"),
                PROJECT_DIR.resolve("build.gradle.kts"),
                PROJECT_DIR.resolve("sample-config.yaml"),
                PROJECT_DIR.resolve("src/main/resources/application.yaml"),
                REPO_ROOT.resolve(".github/workflows/server-ci.yml"),
                REPO_ROOT.resolve(".github/workflows/server-release.yml"),
                REPO_ROOT.resolve(".github/workflows/android-ci.yml"),
                REPO_ROOT.resolve(".github/workflows/android-release.yml")));

        int checked = 0;
        for (Path surface : surfaces) {
            if (!Files.isRegularFile(surface)) {
                continue; // a surface that does not exist on this checkout cannot activate replay
            }
            checked++;
            String body = Files.readString(surface);
            assertThat(body.toLowerCase(java.util.Locale.ROOT))
                    .as(
                            "AC-11 — packaging/launch surface %s MUST NOT name the deterministic-gate "
                                    + "'replay' profile (only android/gate.sh + the android-gate CI job may)",
                            surface)
                    .doesNotContain("replay");
        }
        assertThat(checked)
                .as("the packaging-surface scan must actually have read the core surfaces "
                        + "(unit file, debian scripts, build.gradle.kts, configs)")
                .isGreaterThanOrEqualTo(8);
    }

    /**
     * UC-85 (AC-11) — the profile's own config file must NOT self-activate. {@code
     * application-replay.yaml} is a profile-specific document: it only applies when something else
     * names {@code replay} on the command line / environment. If it ever set {@code
     * spring.profiles.active} (or a {@code spring.config.activate.on-profile}-less self-include) it
     * could leak the profile on; pin it as inert configuration only.
     */
    @Test
    void replay_profile_config_does_not_self_activate() throws IOException {
        Path replayYaml = PROJECT_DIR.resolve("src/main/resources/application-replay.yaml");
        assumeTrue(Files.isRegularFile(replayYaml), "application-replay.yaml not found at " + replayYaml);
        // Ignore comment lines (the file documents how the gate activates the profile via the CLI);
        // only real YAML must be inert.
        String yamlBody = Files.readAllLines(replayYaml).stream()
                .filter(l -> !l.strip().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(yamlBody)
                .as("application-replay.yaml MUST NOT set spring.profiles.active (it would self-activate)")
                .doesNotContain("profiles.active")
                .doesNotContain("active:");
    }

    @Test
    void read_write_paths_includes_clients_allowlist_carve_out() throws IOException {
        assumeTrue(
                Files.isRegularFile(UNIT_FILE),
                "unit file not found at " + UNIT_FILE + " — test must run with cwd=server/");

        Set<String> rwPaths = parseSpaceSeparatedKey("ReadWritePaths");

        // UC05 § AC23 — original entries MUST stay.
        assertThat(rwPaths)
                .as("UC05 § AC23 — audit log path must remain in ReadWritePaths")
                .contains("/var/log/ai-sandbox-server");
        assertThat(rwPaths)
                .as("UC05 § AC23 — per-session workspace + token store path must remain in ReadWritePaths")
                .contains("/var/lib/ai-sandbox-server");

        // UC11 § AC1 — the actual carve-out under test.
        assertThat(rwPaths)
                .as("UC11 § AC1 — /etc/ai-sandbox-server/clients MUST be in ReadWritePaths so "
                        + "EnrollmentFacade can write <name>.crt into the allowlist directory")
                .contains("/etc/ai-sandbox-server/clients");
    }

    /**
     * UC-15 AC3 / AC7 — the systemd unit MUST point {@code DOCKER_CONFIG}
     * at the state-directory-managed docker config root so docker
     * invocations don't reach into {@code $HOME/.docker} (which is
     * unreadable under {@code ProtectHome=true}, and emits a
     * "WARNING: Error loading config file" line on every invocation).
     *
     * <p>The chosen redirect path is {@code
     * /var/lib/ai-sandbox-server/docker-config} — already covered by the
     * existing {@code ReadWritePaths=/var/lib/ai-sandbox-server} entry
     * asserted by {@link #read_write_paths_includes_clients_allowlist_carve_out()},
     * and pre-created with mode 0700 by the postinst (asserted by
     * {@link com.aisandbox.server.release.DebPostinstContractTest}).
     */
    @Test
    void environment_includes_docker_config_redirect() throws IOException {
        assumeTrue(
                Files.isRegularFile(UNIT_FILE),
                "unit file not found at " + UNIT_FILE + " — test must run with cwd=server/");

        Set<String> envEntries = parseEnvironmentEntries();

        // UC-15 AC7 — the literal key=value pair MUST appear in some
        // Environment= line in [Service]. The pair check is exact (not
        // a substring) so a typo'd path or a wrong owner directory
        // surfaces as a test failure.
        assertThat(envEntries)
                .as("UC-15 AC3 / AC7 — Environment= in [Service] MUST set DOCKER_CONFIG to the "
                        + "state-directory-managed docker config root so docker invocations don't "
                        + "reach into ai-sandbox-server's locked-down $HOME under ProtectHome=true")
                .contains("DOCKER_CONFIG=/var/lib/ai-sandbox-server/docker-config");
    }

    /**
     * v0.0.19 crashloop guard — restart rate-limiting MUST live in
     * {@code [Unit]}, not {@code [Service]}.
     *
     * <p>systemd reads {@code StartLimitBurst} / {@code StartLimitIntervalSec}
     * only from the {@code [Unit]} section. Placed under {@code [Service]} they
     * are silently ignored, so a crash-on-boot (e.g. the empty-allowlist abort
     * v0.0.19 shipped) would loop forever under {@code Restart=on-failure}
     * instead of latching {@code failed} after 5 attempts in 60s. The fix moved
     * the two directives from {@code [Service]} to {@code [Unit]}; this test
     * pins them there and asserts they are ABSENT from {@code [Service]} so a
     * future edit can't silently re-break the latch.
     */
    @Test
    void start_limit_directives_live_in_unit_section_not_service() throws IOException {
        assumeTrue(
                Files.isRegularFile(UNIT_FILE),
                "unit file not found at " + UNIT_FILE + " — test must run with cwd=server/");

        // Present in [Unit] with the documented values.
        assertThat(valuesForKeyInSection("[Unit]", "StartLimitBurst"))
                .as("StartLimitBurst MUST live in [Unit] (systemd ignores it under [Service])")
                .containsExactly("5");
        assertThat(valuesForKeyInSection("[Unit]", "StartLimitIntervalSec"))
                .as("StartLimitIntervalSec MUST live in [Unit] (systemd ignores it under [Service])")
                .containsExactly("60s");

        // Absent from [Service] — where systemd would silently ignore them and
        // the crash-on-boot latch would never engage.
        assertThat(valuesForKeyInSection("[Service]", "StartLimitBurst"))
                .as("StartLimitBurst MUST NOT appear in [Service] — systemd ignores it there, "
                        + "re-introducing the v0.0.19 infinite-restart crashloop")
                .isEmpty();
        assertThat(valuesForKeyInSection("[Service]", "StartLimitIntervalSec"))
                .as("StartLimitIntervalSec MUST NOT appear in [Service] — systemd ignores it there")
                .isEmpty();
    }

    @Test
    void read_only_paths_still_locks_down_etc_tree_parent() throws IOException {
        assumeTrue(
                Files.isRegularFile(UNIT_FILE),
                "unit file not found at " + UNIT_FILE + " — test must run with cwd=server/");

        Set<String> roPaths = parseSpaceSeparatedKey("ReadOnlyPaths");

        // UC11 § AC1 — the parent /etc/ai-sandbox-server tree stays
        // read-only; only the explicitly-carved-out clients/ subdir is
        // writable. Security model: config files (cert, key, config.yaml,
        // secrets) immutable; allowlist directory mutable.
        assertThat(roPaths)
                .as("UC11 § AC1 — /etc/ai-sandbox-server parent tree MUST stay in ReadOnlyPaths; "
                        + "only the clients/ subdir is carved out")
                .contains("/etc/ai-sandbox-server");
    }

    /**
     * UC-74 § AC2 — the systemd stop backstop MUST stay strictly greater than
     * the sum of the JVM's internal graceful-shutdown grace budgets, so a stuck
     * WebSocket can never drag the process all the way to {@code TimeoutStopSec}.
     * If it could, systemd would SIGKILL mid-shutdown — losing the UC-44 graceful
     * WS close and breaking the {@code SuccessExitStatus=143} contract (restarts
     * would log as failed).
     *
     * <p>The two internal budgets live in {@code application.yaml}:
     * {@code spring.lifecycle.timeout-per-shutdown-phase} (the SmartLifecycle
     * phase cap that wraps {@code GracefulShutdownHandler.stop()}) and
     * {@code ai-sandbox.server.shutdown.total-grace-seconds} (the graceful-WS
     * drain budget). This test reads {@code TimeoutStopSec} from the unit file
     * and both budgets from the baked yaml, and asserts the invariant — so a
     * future edit that bumps either budget past the backstop fails here.
     */
    @Test
    void timeout_stop_sec_exceeds_sum_of_internal_shutdown_grace_budgets() throws IOException {
        assumeTrue(
                Files.isRegularFile(UNIT_FILE),
                "unit file not found at " + UNIT_FILE + " — test must run with cwd=server/");

        List<String> timeoutStopSec = valuesForKeyInSection("[Service]", "TimeoutStopSec");
        assertThat(timeoutStopSec)
                .as("[Service] MUST declare a single TimeoutStopSec backstop")
                .hasSize(1);
        long timeoutStopSeconds = parseSystemdSeconds(timeoutStopSec.get(0));

        Binder yaml = applicationYamlBinder();
        long phaseSeconds = yaml.bind("spring.lifecycle.timeout-per-shutdown-phase", Bindable.of(Duration.class))
                .get()
                .getSeconds();
        long totalGraceSeconds = yaml.bind("ai-sandbox.server.shutdown.total-grace-seconds", Bindable.of(Long.class))
                .get();

        long internalGrace = phaseSeconds + totalGraceSeconds;
        assertThat(internalGrace)
                .as(
                        "UC-74 § AC2 — TimeoutStopSec=%d MUST stay strictly greater than the sum of the "
                                + "internal grace budgets (phase=%ds + total-grace=%ds = %ds); otherwise systemd "
                                + "SIGKILLs mid-shutdown and the SuccessExitStatus=143 contract breaks",
                        timeoutStopSeconds, phaseSeconds, totalGraceSeconds, internalGrace)
                .isLessThan(timeoutStopSeconds);
    }

    /**
     * UC-74 § AC5 / pitfall — the fix is about timeouts and draining, NOT about
     * changing the kill semantics. The unit MUST keep delivering {@code SIGTERM}
     * (so {@code GracefulShutdownHandler} runs the graceful WS-close ceremony)
     * and MUST honour {@code SuccessExitStatus=143} (128+SIGTERM) so an orderly
     * SIGTERM-driven exit logs as success, not failure. It MUST NOT have been
     * "sped up" by switching to {@code KillMode=mixed}/{@code SIGKILL}, which
     * would defeat the graceful close and route clients to the destructive
     * re-scan-QR identity path.
     */
    @Test
    void graceful_kill_semantics_are_preserved_not_replaced_with_sigkill() throws IOException {
        assumeTrue(
                Files.isRegularFile(UNIT_FILE),
                "unit file not found at " + UNIT_FILE + " — test must run with cwd=server/");

        assertThat(valuesForKeyInSection("[Service]", "KillSignal"))
                .as("UC-74 § AC5 — KillSignal MUST stay SIGTERM so the graceful WS-close ceremony runs")
                .containsExactly("SIGTERM");
        assertThat(valuesForKeyInSection("[Service]", "SuccessExitStatus"))
                .as("UC-74 § AC5 — SIGTERM-driven exit (143) MUST be honoured as success")
                .containsExactly("143");
        assertThat(valuesForKeyInSection("[Service]", "KillMode"))
                .as("UC-74 pitfall — do NOT introduce KillMode=mixed/SIGKILL to 'speed up' the stop; "
                        + "that defeats the UC-44 graceful close and triggers the UC-52/UC-61 re-scan-QR path")
                .isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────

    /**
     * Load the baked {@code application.yaml} from the classpath (main resources
     * are on the test classpath) and expose it as a {@link Binder}, so duration
     * and numeric budgets bind with the same {@code DurationStyle} semantics the
     * runtime uses — rather than re-implementing systemd/Spring duration parsing.
     */
    private static Binder applicationYamlBinder() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        assertThat(sources)
                .as("application.yaml must be on the test classpath and parseable")
                .isNotEmpty();
        return new Binder(ConfigurationPropertySources.from(sources));
    }

    /**
     * Parse a systemd time value (e.g. {@code "70"}, {@code "70s"}, {@code "1min"})
     * into seconds. The unit file uses bare-seconds form ({@code TimeoutStopSec=70}),
     * but accept the common {@code s}/{@code sec}/{@code min} suffixes defensively so
     * a future edit using an explicit unit doesn't silently break the parse.
     */
    private static long parseSystemdSeconds(String raw) {
        String v = raw.strip();
        if (v.endsWith("min")) {
            return Long.parseLong(v.substring(0, v.length() - 3).strip()) * 60L;
        }
        if (v.endsWith("sec")) {
            return Long.parseLong(v.substring(0, v.length() - 3).strip());
        }
        if (v.endsWith("s")) {
            return Long.parseLong(v.substring(0, v.length() - 1).strip());
        }
        return Long.parseLong(v);
    }

    /**
     * Collect the values of {@code key} within the given systemd section
     * (e.g. {@code "[Unit]"} / {@code "[Service]"}). Each matching
     * {@code key=value} line contributes one entry (the trimmed right-hand
     * side), in file order. Returns an empty list when the key never appears
     * in that section — which is exactly the assertion the StartLimit guard
     * makes against {@code [Service]}. Comment lines and other-section lines
     * are ignored; section matching is case-insensitive to mirror systemd.
     */
    private static List<String> valuesForKeyInSection(String section, String key) throws IOException {
        List<String> values = new ArrayList<>();
        boolean inSection = false;
        String prefix = key + "=";
        for (String raw : Files.readAllLines(UNIT_FILE)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                inSection = section.equalsIgnoreCase(line);
                continue;
            }
            if (inSection && line.startsWith(prefix)) {
                values.add(line.substring(prefix.length()).trim());
            }
        }
        return values;
    }

    /**
     * Parse the supplied space-separated systemd unit-file key from the
     * {@code [Service]} section. Returns the union across all
     * occurrences of that key (systemd treats repeated keys as
     * additive). Comment lines and other-section lines are ignored.
     */
    private static Set<String> parseSpaceSeparatedKey(String key) throws IOException {
        Set<String> values = new LinkedHashSet<>();
        boolean inService = false;
        String prefix = key + "=";
        for (String raw : Files.readAllLines(UNIT_FILE)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                inService = "[Service]".equalsIgnoreCase(line);
                continue;
            }
            if (!inService) {
                continue;
            }
            // Strip systemd line-continuations (trailing backslash) for the
            // values we care about — the current unit file doesn't use them,
            // but the parser shouldn't break if a future edit does.
            if (line.startsWith(prefix)) {
                String tail = line.substring(prefix.length()).trim();
                Arrays.stream(tail.split("\\s+"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .forEach(values::add);
            }
        }
        return values;
    }

    /**
     * Parse every {@code Environment=KEY=VALUE} entry in the
     * {@code [Service]} section. Systemd accepts multiple {@code KEY=VAL}
     * pairs on a single {@code Environment=} line separated by spaces
     * (with optional quoting), but the current unit file uses one entry
     * per line and the UC-15 invariant ({@code
     * DOCKER_CONFIG=/var/lib/ai-sandbox-server/docker-config}) is one
     * such single-pair line. Returns the set of literal {@code KEY=VAL}
     * tokens across every {@code Environment=} line, so a caller can
     * assert {@code .contains("DOCKER_CONFIG=/var/lib/...")} without
     * caring whether someone groups multiple variables onto one line.
     *
     * <p>Mirrors the parsing pattern in {@link #parseSpaceSeparatedKey(String)}
     * (Service-section gating + comment skipping) so the two helpers
     * have a consistent surface.
     */
    private static Set<String> parseEnvironmentEntries() throws IOException {
        Set<String> entries = new LinkedHashSet<>();
        boolean inService = false;
        String prefix = "Environment=";
        for (String raw : Files.readAllLines(UNIT_FILE)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                inService = "[Service]".equalsIgnoreCase(line);
                continue;
            }
            if (!inService) {
                continue;
            }
            if (line.startsWith(prefix)) {
                String tail = line.substring(prefix.length()).trim();
                // Single-pair line is the only shape the current unit
                // file uses. Split on whitespace defensively in case a
                // future edit groups multiple variables — entries are
                // KEY=VALUE tokens.
                Arrays.stream(tail.split("\\s+"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty() && s.contains("="))
                        .forEach(entries::add);
            }
        }
        return entries;
    }
}
