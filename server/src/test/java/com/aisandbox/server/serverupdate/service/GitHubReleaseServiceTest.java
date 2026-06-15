package com.aisandbox.server.serverupdate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aisandbox.server.serverupdate.dto.ReleaseInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * UC-84 — unit coverage for {@link GitHubReleaseService}, the unauthenticated
 * {@code server-v*} release resolver behind the self-update check.
 *
 * <p>Hermetic: the service's injectable {@code (HttpClient, ObjectMapper,
 * baseUrl)} seam is pointed at a throwaway in-process {@link HttpServer} stub
 * (JDK built-in — no MockWebServer dependency needed on the server module),
 * so every GitHub-shaped response is canned and asserted deterministically.
 *
 * <h2>AC → method map</h2>
 *
 * <ul>
 *   <li>AC5 — {@link #semver_orders_numerically_so_0_0_10_beats_0_0_9()},
 *       {@link #latest_release_is_chosen_by_semver_not_listing_order()}.</li>
 *   <li>AC13 — {@link #check_request_carries_no_authorization_header_or_token()}.</li>
 *   <li>AC14 — {@link #github_403_is_rate_limited()},
 *       {@link #github_429_is_rate_limited()},
 *       {@link #github_500_is_check_failed()},
 *       {@link #connection_refused_is_github_unreachable()}.</li>
 *   <li>AC15 — {@link #android_v_releases_are_ignored()}.</li>
 *   <li>AC4 — {@link #amd64_deb_asset_url_is_located()},
 *       {@link #newer_release_without_amd64_asset_yields_null_asset_url()}.</li>
 * </ul>
 */
class GitHubReleaseServiceTest {

    private HttpServer server;

    /** Headers seen on the LAST request the stub served — drives the AC13 no-auth assertion. */
    private final List<String> lastRequestHeaderKeys = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /** Start an in-process stub returning {@code status}/{@code body} for any path; record request header keys. */
    private GitHubReleaseService serviceReturning(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastRequestHeaderKeys.clear();
            lastRequestHeaderKeys.addAll(exchange.getRequestHeaders().keySet());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new GitHubReleaseService(HttpClient.newHttpClient(), new ObjectMapper(), baseUrl);
    }

    // ── AC5 — semantic-version ordering ─────────────────────────────────────

    @Test
    void semver_orders_numerically_so_0_0_10_beats_0_0_9() {
        assertThat(GitHubReleaseService.compareSemver("0.0.10", "0.0.9")).isPositive();
        assertThat(GitHubReleaseService.compareSemver("0.0.9", "0.0.10")).isNegative();
        assertThat(GitHubReleaseService.compareSemver("1.0.0", "0.9.9")).isPositive();
        assertThat(GitHubReleaseService.compareSemver("0.0.5", "0.0.5")).isZero();
        // missing trailing components are zero-extended: 1.2 == 1.2.0
        assertThat(GitHubReleaseService.compareSemver("1.2", "1.2.0")).isZero();
        assertThat(GitHubReleaseService.compareSemver("2.0", "1.9.9")).isPositive();
    }

    // ── AC15 / AC5 — newest server-v* by semver, android-v* ignored ─────────

    @Test
    void latest_release_is_chosen_by_semver_not_listing_order() throws IOException {
        // Deliberately out-of-order, with a draft and an android entry interleaved.
        String json =
                """
                [
                  {"tag_name":"server-v0.0.9","draft":false,"html_url":"https://gh/server-v0.0.9",
                   "assets":[{"name":"ai-sandbox-server_0.0.9_amd64.deb","browser_download_url":"https://gh/d/0.0.9_amd64.deb"}]},
                  {"tag_name":"server-v0.0.11","draft":true,"html_url":"https://gh/server-v0.0.11","assets":[]},
                  {"tag_name":"server-v0.0.10","draft":false,"html_url":"https://gh/server-v0.0.10",
                   "assets":[{"name":"ai-sandbox-server_0.0.10_amd64.deb","browser_download_url":"https://gh/d/0.0.10_amd64.deb"}]}
                ]
                """;
        ReleaseInfo latest = serviceReturning(200, json).latestServerRelease();

        assertThat(latest).isNotNull();
        // 0.0.10 wins over 0.0.9; the 0.0.11 DRAFT is excluded (not installable).
        assertThat(latest.version()).isEqualTo("0.0.10");
        assertThat(latest.htmlUrl()).isEqualTo("https://gh/server-v0.0.10");
        assertThat(latest.debAssetUrl()).isEqualTo("https://gh/d/0.0.10_amd64.deb");
    }

    @Test
    void android_v_releases_are_ignored() throws IOException {
        String json =
                """
                [
                  {"tag_name":"android-v9.9.9","draft":false,"html_url":"https://gh/android-v9.9.9",
                   "assets":[{"name":"ai-sandbox-android_9.9.9_amd64.deb","browser_download_url":"https://gh/d/android.deb"}]},
                  {"tag_name":"server-v0.0.3","draft":false,"html_url":"https://gh/server-v0.0.3",
                   "assets":[{"name":"ai-sandbox-server_0.0.3_amd64.deb","browser_download_url":"https://gh/d/0.0.3_amd64.deb"}]}
                ]
                """;
        ReleaseInfo latest = serviceReturning(200, json).latestServerRelease();

        // The android-v9.9.9 entry must NOT win despite its higher version number.
        assertThat(latest).isNotNull();
        assertThat(latest.version()).isEqualTo("0.0.3");
        assertThat(latest.debAssetUrl()).isEqualTo("https://gh/d/0.0.3_amd64.deb");
    }

    @Test
    void no_server_release_present_returns_null() throws IOException {
        String json =
                """
                [ {"tag_name":"android-v1.0.0","draft":false,"html_url":"h","assets":[]} ]
                """;
        assertThat(serviceReturning(200, json).latestServerRelease()).isNull();
    }

    // ── AC4 / AC14 — amd64 asset location + wrong-arch surfacing ────────────

    @Test
    void amd64_deb_asset_url_is_located() throws IOException {
        String json =
                """
                [ {"tag_name":"server-v1.2.3","draft":false,"html_url":"https://gh/server-v1.2.3",
                   "assets":[
                     {"name":"ai-sandbox-server_1.2.3_arm64.deb","browser_download_url":"https://gh/d/arm64.deb"},
                     {"name":"ai-sandbox-server_1.2.3_amd64.deb","browser_download_url":"https://gh/d/amd64.deb"},
                     {"name":"checksums.txt","browser_download_url":"https://gh/d/checksums.txt"}
                   ]} ]
                """;
        ReleaseInfo latest = serviceReturning(200, json).latestServerRelease();

        assertThat(latest).isNotNull();
        // Only the *_amd64.deb asset is selected; arm64 / checksums are skipped.
        assertThat(latest.debAssetUrl()).isEqualTo("https://gh/d/amd64.deb");
    }

    @Test
    void newer_release_without_amd64_asset_yields_null_asset_url() throws IOException {
        String json =
                """
                [ {"tag_name":"server-v2.0.0","draft":false,"html_url":"https://gh/server-v2.0.0",
                   "assets":[{"name":"ai-sandbox-server_2.0.0_arm64.deb","browser_download_url":"https://gh/d/arm64.deb"}]} ]
                """;
        ReleaseInfo latest = serviceReturning(200, json).latestServerRelease();

        assertThat(latest).isNotNull();
        assertThat(latest.version()).isEqualTo("2.0.0");
        // No amd64 asset on a wrong-arch host -> null; the facade maps this to NoAsset (AC14).
        assertThat(latest.debAssetUrl()).isNull();
    }

    // ── AC13 — no credentials, ever ─────────────────────────────────────────

    @Test
    void check_request_carries_no_authorization_header_or_token() throws IOException {
        String json =
                """
                [ {"tag_name":"server-v0.0.1","draft":false,"html_url":"h",
                   "assets":[{"name":"ai-sandbox-server_0.0.1_amd64.deb","browser_download_url":"u"}]} ]
                """;
        serviceReturning(200, json).latestServerRelease();

        // The release lookup MUST be unauthenticated: no Authorization / token header.
        assertThat(lastRequestHeaderKeys).isNotEmpty();
        assertThat(lastRequestHeaderKeys)
                .as("AC13 — the unauthenticated check must attach NO Authorization/token header")
                .noneMatch(k -> {
                    String lower = k.toLowerCase();
                    return lower.contains("authorization") || lower.contains("token");
                });
    }

    // ── AC14 — failure mapping ──────────────────────────────────────────────

    @Test
    void github_403_is_rate_limited() throws IOException {
        assertThatThrownBy(() -> serviceReturning(403, "{}").latestServerRelease())
                .isInstanceOf(ServerUpdateException.RateLimited.class);
    }

    @Test
    void github_429_is_rate_limited() throws IOException {
        assertThatThrownBy(() -> serviceReturning(429, "{}").latestServerRelease())
                .isInstanceOf(ServerUpdateException.RateLimited.class);
    }

    @Test
    void github_500_is_check_failed() throws IOException {
        assertThatThrownBy(() -> serviceReturning(500, "oops").latestServerRelease())
                .isInstanceOf(ServerUpdateException.CheckFailed.class);
    }

    @Test
    void non_array_body_is_check_failed() throws IOException {
        assertThatThrownBy(() ->
                        serviceReturning(200, "{\"message\":\"not an array\"}").latestServerRelease())
                .isInstanceOf(ServerUpdateException.CheckFailed.class);
    }

    @Test
    void connection_refused_is_github_unreachable() throws IOException {
        // Grab a port, then immediately release it so the connect is refused.
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        GitHubReleaseService svc = new GitHubReleaseService(
                HttpClient.newHttpClient(), new ObjectMapper(), "http://127.0.0.1:" + deadPort);

        assertThatThrownBy(svc::latestServerRelease).isInstanceOf(ServerUpdateException.GitHubUnreachable.class);
    }
}
