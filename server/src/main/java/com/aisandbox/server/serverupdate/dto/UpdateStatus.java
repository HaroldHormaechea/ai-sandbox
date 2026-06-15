package com.aisandbox.server.serverupdate.dto;

/**
 * UC-84 — internal result of a self-update check (profile-java-server-architecture
 * rule 5: internal DTO, never a REST body). {@code ApiMappers} maps it to
 * {@code ApiDtos.UpdateCheckResponse}.
 *
 * @param currentVersion  the server's running version (jar manifest, or
 *                        {@code "dev"} outside a packaged jar).
 * @param latestVersion   the newest {@code server-v*} release version, or
 *                        {@code null} when none was found / running in dev.
 * @param updateAvailable true iff {@code latestVersion} is strictly newer than
 *                        {@code currentVersion} by semantic-version ordering.
 * @param releaseHtmlUrl  the latest release's GitHub HTML page (Changelog), or null.
 * @param debAssetUrl     the matching {@code *_amd64.deb} download URL, or null.
 *                        Informational only — the apply path forwards nothing.
 */
public record UpdateStatus(
        String currentVersion,
        String latestVersion,
        boolean updateAvailable,
        String releaseHtmlUrl,
        String debAssetUrl) {}
