package com.aisandbox.server.cli.secrets;

import com.aisandbox.server.cli.AisandboxctlCommand;

/**
 * UC-38 — single source of truth for the running server package version,
 * read at runtime from the jar manifest.
 *
 * <p>{@code build.gradle.kts} sets {@code project.version} from
 * {@code ai_sandbox_server_version} (the {@code server-v*} tag overrides
 * it on release builds), and Spring Boot's {@code BootJar} stamps that
 * value into the manifest's {@code Implementation-Version} attribute (the
 * build asserts it is present). At runtime {@link #current()} reads it
 * back via {@link Package#getImplementationVersion()} off a jar-root
 * class ({@link AisandboxctlCommand}).
 *
 * <p><b>Why the {@link #DEV_FALLBACK} matters.</b> When the classes are
 * loaded from a plain directory rather than the packaged jar — every unit
 * test, and a {@code java -cp build/classes ...} dev run — no manifest is
 * present and {@link Package#getImplementationVersion()} returns
 * {@code null}. We fall back to {@value #DEV_FALLBACK} so the value is
 * deterministic and never {@code null}. This is also what keeps the image
 * label stable across rebuilds in dev/test: a {@code null}-everywhere
 * version would label every image differently and make the upgrade path
 * see perpetual staleness ("rebuild on every upgrade" regression).
 */
public final class ServerVersion {

    /** Version reported when no manifest {@code Implementation-Version} is available (dev / test). */
    public static final String DEV_FALLBACK = "dev";

    private ServerVersion() {}

    /**
     * @return the server package version from the jar manifest, or
     *     {@value #DEV_FALLBACK} when running outside a packaged jar.
     */
    public static String current() {
        Package pkg = AisandboxctlCommand.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null || version.isBlank() ? DEV_FALLBACK : version;
    }
}
