package com.aisandbox.server.serverupdate.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aisandbox.server.audit.AuditLogger;
import com.aisandbox.server.cli.secrets.ServerVersion;
import com.aisandbox.server.serverupdate.dto.ApplyResult;
import com.aisandbox.server.serverupdate.dto.ReleaseInfo;
import com.aisandbox.server.serverupdate.dto.UpdateStatus;
import com.aisandbox.server.serverupdate.service.GitHubReleaseService;
import com.aisandbox.server.serverupdate.service.ServerUpdateException;
import com.aisandbox.server.serverupdate.service.UpdateTriggerService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * UC-84 — use-case-boundary behaviour of {@link ServerUpdateFacade} (AC5, AC9,
 * AC14). The two collaborating services are mocked; the static
 * {@link ServerVersion#current()} (jar-manifest read) is stubbed via
 * {@code mockStatic} so the non-dev code paths are reachable from a plain unit
 * test (outside a packaged jar it always returns {@code "dev"}).
 *
 * <h2>AC → method map</h2>
 *
 * <ul>
 *   <li>AC5 — {@link #check_dev_runtime_never_contacts_github_and_offers_nothing()},
 *       {@link #check_newer_release_marks_update_available()},
 *       {@link #check_equal_or_older_release_is_not_available()}.</li>
 *   <li>AC14 — {@link #check_newer_release_without_asset_throws_no_asset()},
 *       {@link #check_propagates_rate_limited_as_typed_exception()}.</li>
 *   <li>AC9 — {@link #apply_writes_trigger_before_touching_github_and_returns_promptly()},
 *       {@link #apply_succeeds_even_when_github_lookup_fails()}.</li>
 * </ul>
 */
class ServerUpdateFacadeTest {

    private final GitHubReleaseService github = mock(GitHubReleaseService.class);
    private final UpdateTriggerService trigger = mock(UpdateTriggerService.class);
    private final AuditLogger audit = mock(AuditLogger.class, RETURNS_DEEP_STUBS);

    private final ServerUpdateFacade facade = new ServerUpdateFacade(github, trigger, audit);

    // ── AC5 — check semantics ───────────────────────────────────────────────

    @Test
    void check_dev_runtime_never_contacts_github_and_offers_nothing() {
        try (var ms = mockStatic(ServerVersion.class)) {
            ms.when(ServerVersion::current).thenReturn(ServerVersion.DEV_FALLBACK);

            UpdateStatus status = facade.check();

            assertThat(status.currentVersion()).isEqualTo("dev");
            assertThat(status.updateAvailable()).isFalse();
            assertThat(status.latestVersion()).isNull();
            // The dev path must not even reach out to GitHub.
            verifyNoInteractions(github);
        }
    }

    @Test
    void check_newer_release_marks_update_available() {
        try (var ms = mockStatic(ServerVersion.class)) {
            ms.when(ServerVersion::current).thenReturn("0.0.9");
            when(github.latestServerRelease())
                    .thenReturn(
                            new ReleaseInfo("0.0.10", "https://gh/server-v0.0.10", "https://gh/d/0.0.10_amd64.deb"));

            UpdateStatus status = facade.check();

            assertThat(status.currentVersion()).isEqualTo("0.0.9");
            assertThat(status.latestVersion()).isEqualTo("0.0.10");
            assertThat(status.updateAvailable()).isTrue();
            assertThat(status.releaseHtmlUrl()).isEqualTo("https://gh/server-v0.0.10");
            assertThat(status.debAssetUrl()).isEqualTo("https://gh/d/0.0.10_amd64.deb");
        }
    }

    @Test
    void check_equal_or_older_release_is_not_available() {
        try (var ms = mockStatic(ServerVersion.class)) {
            ms.when(ServerVersion::current).thenReturn("0.0.10");
            when(github.latestServerRelease())
                    .thenReturn(
                            new ReleaseInfo("0.0.10", "https://gh/server-v0.0.10", "https://gh/d/0.0.10_amd64.deb"));

            UpdateStatus status = facade.check();

            // Equal remote -> not available (semver ordering, AC5).
            assertThat(status.updateAvailable()).isFalse();
            assertThat(status.latestVersion()).isEqualTo("0.0.10");
        }
    }

    @Test
    void check_no_release_found_is_not_available() {
        try (var ms = mockStatic(ServerVersion.class)) {
            ms.when(ServerVersion::current).thenReturn("0.0.5");
            when(github.latestServerRelease()).thenReturn(null);

            UpdateStatus status = facade.check();

            assertThat(status.updateAvailable()).isFalse();
            assertThat(status.releaseHtmlUrl()).isNull();
        }
    }

    // ── AC14 — failure paths surface as typed exceptions (server stays up) ──

    @Test
    void check_newer_release_without_asset_throws_no_asset() {
        try (var ms = mockStatic(ServerVersion.class)) {
            ms.when(ServerVersion::current).thenReturn("0.0.9");
            // Newer version exists but ships no amd64 .deb (wrong-arch host).
            when(github.latestServerRelease()).thenReturn(new ReleaseInfo("0.0.10", "https://gh/server-v0.0.10", null));

            assertThatThrownBy(facade::check).isInstanceOf(ServerUpdateException.NoAsset.class);
        }
    }

    @Test
    void check_propagates_rate_limited_as_typed_exception() {
        try (var ms = mockStatic(ServerVersion.class)) {
            ms.when(ServerVersion::current).thenReturn("0.0.9");
            when(github.latestServerRelease()).thenThrow(new ServerUpdateException.RateLimited("limited"));

            assertThatThrownBy(facade::check).isInstanceOf(ServerUpdateException.RateLimited.class);
        }
    }

    // ── AC9 — apply writes the trigger first and returns promptly ───────────

    @Test
    void apply_writes_trigger_before_touching_github_and_returns_promptly() {
        try (var ms = mockStatic(ServerVersion.class)) {
            ms.when(ServerVersion::current).thenReturn("0.0.9");
            when(github.latestServerRelease())
                    .thenReturn(
                            new ReleaseInfo("0.0.10", "https://gh/server-v0.0.10", "https://gh/d/0.0.10_amd64.deb"));

            ApplyResult result = facade.apply();

            assertThat(result.accepted()).isTrue();
            assertThat(result.targetVersion()).isEqualTo("0.0.10");

            // The critical privileged action (writing the trigger) runs FIRST, so the
            // response never depends on GitHub reachability (AC9).
            InOrder order = Mockito.inOrder(trigger, github);
            order.verify(trigger).requestUpdate();
            order.verify(github).latestServerRelease();
        }
    }

    @Test
    void apply_succeeds_even_when_github_lookup_fails() {
        try (var ms = mockStatic(ServerVersion.class)) {
            ms.when(ServerVersion::current).thenReturn("0.0.9");
            // The best-effort target lookup blows up — apply must still succeed.
            when(github.latestServerRelease()).thenThrow(new ServerUpdateException.GitHubUnreachable("down", null));

            ApplyResult result = facade.apply();

            assertThat(result.accepted()).isTrue();
            // Target unresolved -> null; the updater self-determines its real target anyway.
            assertThat(result.targetVersion()).isNull();
            verify(trigger).requestUpdate();
        }
    }

    @Test
    void apply_trigger_io_failure_propagates_and_skips_github() {
        try (var ms = mockStatic(ServerVersion.class)) {
            lenient().when(github.latestServerRelease()).thenReturn(null);
            Mockito.doThrow(new ServerUpdateException.TriggerFailed("io", null))
                    .when(trigger)
                    .requestUpdate();

            assertThatThrownBy(facade::apply).isInstanceOf(ServerUpdateException.TriggerFailed.class);

            // Trigger failed before any GitHub call — nothing else ran.
            verify(github, never()).latestServerRelease();
        }
    }
}
