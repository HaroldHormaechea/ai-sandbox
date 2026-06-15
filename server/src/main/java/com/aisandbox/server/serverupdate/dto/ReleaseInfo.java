package com.aisandbox.server.serverupdate.dto;

/**
 * UC-84 — internal DTO describing the newest published {@code server-v*}
 * GitHub release, as resolved by
 * {@link com.aisandbox.server.serverupdate.service.GitHubReleaseService}.
 *
 * <p>This is an INTERNAL type (profile-java-server-architecture rule 5): it
 * never crosses the API boundary. The controller maps it (together with the
 * running {@link com.aisandbox.server.cli.secrets.ServerVersion}) into the
 * API response record {@code ApiDtos.UpdateCheckResponse} via
 * {@code ApiMappers}.
 *
 * @param version       the release version with the {@code server-v} prefix
 *                      stripped (e.g. {@code "0.0.52"}).
 * @param htmlUrl       the release's GitHub HTML page (the "Changelog" link the
 *                      client opens in an external browser — AC6).
 * @param debAssetUrl   the {@code browser_download_url} of the matching
 *                      {@code *_amd64.deb} asset, or {@code null} when the
 *                      release ships no amd64 {@code .deb}. INFORMATIONAL ONLY:
 *                      the parameter-free updater self-determines and downloads
 *                      its own target — the server forwards this to nothing
 *                      (AC8/AC11).
 */
public record ReleaseInfo(String version, String htmlUrl, String debAssetUrl) {}
