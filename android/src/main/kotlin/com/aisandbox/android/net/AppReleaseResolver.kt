package com.aisandbox.android.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * UC-87 — PURE (no network, no Android) resolver for the Android client
 * self-update check. Given the decoded list of public GitHub releases for the
 * repo, it picks the newest *stable* {@code android-v*} release and resolves
 * the {@code android-release.apk} asset URL.
 *
 * <p>Extracted from [GitHubReleaseClient] so the version-selection rules
 * (track filter, stable-only filter, semver ordering, asset lookup) are
 * unit-testable on a plain JVM — exactly like the server-side
 * {@code GitHubReleaseService} this is ported from.
 *
 * <p><b>No credentials, ever (AC7).</b> Nothing here reads a token or attaches
 * an {@code Authorization} header; it only operates on already-fetched public
 * JSON.
 */
object AppReleaseResolver {

    /** Only the {@code android-v*} release track is considered; {@code server-v*} is ignored (AC2). */
    const val TRACK_PREFIX = "android-v"

    /** The signed APK published by {@code android-release.yml} as a release asset (AC5). */
    const val APK_ASSET_NAME = "android-release.apk"

    /** Resolved newest stable release: its semver, the release page URL, and the APK asset URL. */
    data class ResolvedRelease(
        val version: String,
        val releaseHtmlUrl: String?,
        val apkAssetUrl: String?,
    )

    /**
     * Pick the newest stable {@code android-v*} release from [releases].
     *
     * <p>Skips drafts, GitHub pre-releases ({@code prerelease == true}), tags
     * off the {@code android-v*} track, and tags whose version carries a
     * non-numeric / {@code -rc} suffix (stable-only, AC2). Returns {@code null}
     * when no stable {@code android-v*} release exists.
     */
    fun resolveLatestStable(releases: List<GitHubRelease>): ResolvedRelease? {
        var newest: ResolvedRelease? = null
        var newestVersion: String? = null
        for (rel in releases) {
            if (rel.draft) continue // unpublished drafts are not installable targets
            if (rel.prerelease) continue // GitHub-flagged pre-release (AC2)
            val tag = rel.tagName
            if (!tag.startsWith(TRACK_PREFIX)) continue // ignore server-v* and anything off-track
            val version = tag.substring(TRACK_PREFIX.length)
            if (version.isBlank()) continue
            if (!isStableVersion(version)) continue // skip -rc / non-numeric-suffix tags (AC2)
            if (newestVersion == null || compareSemver(version, newestVersion) > 0) {
                newestVersion = version
                newest = ResolvedRelease(version, rel.htmlUrl, findApkAssetUrl(rel))
            }
        }
        return newest
    }

    /**
     * True iff every dot-separated component of [version] is purely numeric
     * (e.g. {@code 0.4.15}); a {@code -rc} / pre-release suffix on any segment
     * (e.g. {@code 0.4.15-rc1}) makes it non-stable.
     */
    fun isStableVersion(version: String): Boolean {
        val parts = version.trim().split(".")
        if (parts.isEmpty()) return false
        return parts.all { seg -> seg.isNotEmpty() && seg.all { it.isDigit() } }
    }

    private fun findApkAssetUrl(release: GitHubRelease): String? {
        for (asset in release.assets) {
            if (asset.name == APK_ASSET_NAME) {
                val url = asset.browserDownloadUrl
                if (!url.isNullOrBlank()) return url
            }
        }
        return null
    }

    /**
     * Semantic-version ordering of two dotted numeric versions (AC10 — ordered
     * by tag semver, never by {@code versionCode}). Returns a negative / zero /
     * positive int when {@code a} is older / equal / newer than {@code b}.
     * Ported from the server's {@code GitHubReleaseService.compareSemver}:
     * each component compared numerically; missing trailing components treated
     * as 0 (so {@code 1.2} == {@code 1.2.0}); a non-numeric component sorts as 0
     * for that position — tolerant of differing segment counts and stray
     * suffixes WITHOUT throwing.
     */
    fun compareSemver(a: String, b: String): Int {
        val pa = a.trim().split(".")
        val pb = b.trim().split(".")
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val na = if (i < pa.size) parseLeadingInt(pa[i]) else 0
            val nb = if (i < pb.size) parseLeadingInt(pb[i]) else 0
            val cmp = na.compareTo(nb)
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun parseLeadingInt(s: String): Int {
        var end = 0
        while (end < s.length && s[end].isDigit()) end++
        if (end == 0) return 0
        return try {
            s.substring(0, end).toInt()
        } catch (_: NumberFormatException) {
            0
        }
    }
}

// ── Wire DTOs (subset of the GitHub Releases REST response) ──────────────────

/**
 * Mirrors the fields of a GitHub Releases API entry the resolver needs. Lenient
 * decode (defaults + {@code ignoreUnknownKeys}) tolerates the many fields we do
 * not read.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

/** One release asset; we match on [name] and download from [browserDownloadUrl]. */
@Serializable
data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
)
