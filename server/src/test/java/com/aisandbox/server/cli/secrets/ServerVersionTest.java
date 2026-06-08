package com.aisandbox.server.cli.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * UC-38 — {@link ServerVersion} is the single source of truth for the
 * server package version stamped onto / compared against the session
 * image (AC2).
 *
 * <p>{@link ServerVersion#current()} reads the jar manifest's
 * {@code Implementation-Version}. Under the test classpath the classes
 * load from a plain directory (no manifest), so {@code current()}
 * returns the deterministic {@link ServerVersion#DEV_FALLBACK}. That
 * fallback is load-bearing: a {@code null}-everywhere version would
 * label every dev/test build differently and make the upgrade path see
 * perpetual staleness — the regression the {@code dev} fallback exists
 * to prevent.
 *
 * <p>The "injected version" half of the contract — onboard stamping a
 * real {@code server-v*} version onto the image — is exercised through
 * {@link com.aisandbox.server.cli.OnboardCommand}'s {@code
 * setPackageVersion(...)} seam in
 * {@link com.aisandbox.server.cli.OnboardCommandTest}, since {@code
 * ServerVersion.current()} reads the manifest directly and a unit test
 * cannot fabricate one off a directory classpath.
 */
class ServerVersionTest {

    @Test
    void current_falls_back_to_dev_outside_a_packaged_jar() {
        // The test classpath is a directory tree, not the bootJar, so no
        // Implementation-Version manifest attribute is present.
        assertThat(ServerVersion.current()).isEqualTo(ServerVersion.DEV_FALLBACK);
    }

    @Test
    void dev_fallback_constant_is_the_literal_dev() {
        // SandboxDockerfile defaults ARG IMAGE_VERSION=dev to match this,
        // so a label-less / unset build and the runtime fallback agree.
        assertThat(ServerVersion.DEV_FALLBACK).isEqualTo("dev");
    }

    @Test
    void current_is_never_null_or_blank() {
        // The image label + staleness comparison both rely on a non-null,
        // non-blank identity; a null would break classify()'s String.equals.
        String v = ServerVersion.current();
        assertThat(v).isNotNull();
        assertThat(v.isBlank()).isFalse();
    }
}
