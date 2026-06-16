package com.aisandbox.android.net

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * UC-87 — PURE (no network, no Android) unit coverage for [AppReleaseResolver],
 * the version-selection core of the Android client self-update check. Runs on a
 * plain JVM via JUnit 5 (mirrors the server's {@code GitHubReleaseService}
 * tests).
 *
 * <h2>AC → test map</h2>
 *
 * <ul>
 *   <li>AC2 (track filter) — {@link #ignores_the_server_track_entirely()},
 *       {@link #returns_null_when_no_android_release_exists()}.</li>
 *   <li>AC2 (stable-only) — {@link #skips_github_prerelease_and_draft_flags()},
 *       {@link #skips_rc_and_non_numeric_suffix_tags()},
 *       {@link #isStableVersion_distinguishes_stable_from_prerelease()}.</li>
 *   <li>AC10 (semver ordering) — {@link #picks_the_newest_stable_by_semver_not_lexically()},
 *       {@link #compareSemver_orders_older_equal_newer()},
 *       {@link #compareSemver_treats_missing_trailing_components_as_zero()},
 *       {@link #compareSemver_is_tolerant_and_never_throws()}.</li>
 *   <li>AC5 (asset lookup) — {@link #resolves_the_android_release_apk_asset_url()},
 *       {@link #null_apk_url_when_the_release_has_no_android_release_apk_asset()}.</li>
 *   <li>AC3/AC4 (current vs latest decision uses these primitives) — exercised
 *       transitively by the ordering tests above and by AppUpdateCoordinatorTest.</li>
 * </ul>
 */
class AppReleaseResolverTest {

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        assets: List<GitHubAsset> = listOf(apkAsset("https://gh/dl/$tag/android-release.apk")),
        htmlUrl: String? = "https://gh/$tag",
    ) = GitHubRelease(
        tagName = tag,
        draft = draft,
        prerelease = prerelease,
        htmlUrl = htmlUrl,
        assets = assets,
    )

    private fun apkAsset(url: String) = GitHubAsset(name = "android-release.apk", browserDownloadUrl = url)

    // ── AC2 — track filter ──────────────────────────────────────────────────

    @Test
    fun ignores_the_server_track_entirely() {
        // server-v* is newer numerically but off the android-v* track (AC2 pitfall).
        val resolved = AppReleaseResolver.resolveLatestStable(
            listOf(
                release("server-v9.9.9"),
                release("android-v0.4.15"),
            ),
        )
        assertThat(resolved).isNotNull
        assertThat(resolved!!.version).isEqualTo("0.4.15")
    }

    @Test
    fun returns_null_when_no_android_release_exists() {
        val resolved = AppReleaseResolver.resolveLatestStable(
            listOf(release("server-v1.2.3"), release("v0.0.1"), release("random-tag")),
        )
        assertThat(resolved).isNull()
    }

    @Test
    fun returns_null_for_an_empty_release_list() {
        assertThat(AppReleaseResolver.resolveLatestStable(emptyList())).isNull()
    }

    // ── AC2 — stable-only filter ────────────────────────────────────────────

    @Test
    fun skips_github_prerelease_and_draft_flags() {
        val resolved = AppReleaseResolver.resolveLatestStable(
            listOf(
                release("android-v0.5.0", prerelease = true), // GitHub-flagged pre-release
                release("android-v0.4.99", draft = true), // unpublished draft
                release("android-v0.4.15"), // the only installable target
            ),
        )
        assertThat(resolved!!.version).isEqualTo("0.4.15")
    }

    @Test
    fun skips_rc_and_non_numeric_suffix_tags() {
        // android-v0.5.0-rc1 is the highest numerically but is a pre-release tag (AC2).
        val resolved = AppReleaseResolver.resolveLatestStable(
            listOf(
                release("android-v0.5.0-rc1"),
                release("android-v0.4.20-beta"),
                release("android-v0.4.15"),
            ),
        )
        assertThat(resolved!!.version).isEqualTo("0.4.15")
    }

    @Test
    fun isStableVersion_distinguishes_stable_from_prerelease() {
        assertThat(AppReleaseResolver.isStableVersion("0.4.15")).isTrue
        assertThat(AppReleaseResolver.isStableVersion("1.0.0")).isTrue
        assertThat(AppReleaseResolver.isStableVersion("12")).isTrue
        assertThat(AppReleaseResolver.isStableVersion("0.4.15-rc1")).isFalse
        assertThat(AppReleaseResolver.isStableVersion("0.4.15-beta")).isFalse
        assertThat(AppReleaseResolver.isStableVersion("0.4.x")).isFalse
        assertThat(AppReleaseResolver.isStableVersion("")).isFalse
        assertThat(AppReleaseResolver.isStableVersion("0..1")).isFalse // empty middle segment
    }

    // ── AC10 — semver ordering ──────────────────────────────────────────────

    @Test
    fun picks_the_newest_stable_by_semver_not_lexically() {
        // 0.10.0 > 0.9.0 by semver, but a string sort would pick "0.9.0" (AC10).
        val resolved = AppReleaseResolver.resolveLatestStable(
            listOf(
                release("android-v0.9.0"),
                release("android-v0.10.0"),
                release("android-v0.4.15"),
            ),
        )
        assertThat(resolved!!.version).isEqualTo("0.10.0")
    }

    @Test
    fun compareSemver_orders_older_equal_newer() {
        assertThat(AppReleaseResolver.compareSemver("0.4.14", "0.4.15")).isNegative // older
        assertThat(AppReleaseResolver.compareSemver("0.4.15", "0.4.15")).isZero // equal
        assertThat(AppReleaseResolver.compareSemver("0.4.16", "0.4.15")).isPositive // newer
        // AC10 — semantic, not lexical: 0.10.0 is newer than 0.9.0.
        assertThat(AppReleaseResolver.compareSemver("0.10.0", "0.9.0")).isPositive
        assertThat(AppReleaseResolver.compareSemver("1.0.0", "0.99.99")).isPositive
    }

    @Test
    fun compareSemver_treats_missing_trailing_components_as_zero() {
        assertThat(AppReleaseResolver.compareSemver("1.2", "1.2.0")).isZero
        assertThat(AppReleaseResolver.compareSemver("1.2.0", "1.2")).isZero
        assertThat(AppReleaseResolver.compareSemver("1.2.1", "1.2")).isPositive
    }

    @Test
    fun compareSemver_is_tolerant_and_never_throws() {
        // Short, long, and stray-suffix segments must compare WITHOUT throwing.
        assertThat(AppReleaseResolver.compareSemver("1", "1.0.0.0")).isZero
        assertThat(AppReleaseResolver.compareSemver("1.2.3-rc1", "1.2.3")).isZero // suffix parses as leading int
        assertThat(AppReleaseResolver.compareSemver("abc", "1.0.0")).isNegative // non-numeric → 0
        assertThat(AppReleaseResolver.compareSemver("", "")).isZero
        assertThat(AppReleaseResolver.compareSemver("1.2.3.4.5", "1.2.3")).isPositive
    }

    // ── AC5 — APK asset lookup ──────────────────────────────────────────────

    @Test
    fun resolves_the_android_release_apk_asset_url() {
        val resolved = AppReleaseResolver.resolveLatestStable(
            listOf(
                release(
                    "android-v0.4.15",
                    assets = listOf(
                        GitHubAsset("mapping.txt", "https://gh/dl/mapping.txt"),
                        apkAsset("https://gh/dl/android-v0.4.15/android-release.apk"),
                        GitHubAsset("checksums.sha256", "https://gh/dl/checksums.sha256"),
                    ),
                ),
            ),
        )
        assertThat(resolved!!.apkAssetUrl).isEqualTo("https://gh/dl/android-v0.4.15/android-release.apk")
        assertThat(resolved.releaseHtmlUrl).isEqualTo("https://gh/android-v0.4.15")
    }

    @Test
    fun null_apk_url_when_the_release_has_no_android_release_apk_asset() {
        // Newest stable still resolves, but there is no installable APK asset.
        val resolved = AppReleaseResolver.resolveLatestStable(
            listOf(
                release(
                    "android-v0.4.15",
                    assets = listOf(GitHubAsset("app-release.aab", "https://gh/dl/app-release.aab")),
                ),
            ),
        )
        assertThat(resolved).isNotNull
        assertThat(resolved!!.version).isEqualTo("0.4.15")
        assertThat(resolved.apkAssetUrl).isNull()
    }
}
