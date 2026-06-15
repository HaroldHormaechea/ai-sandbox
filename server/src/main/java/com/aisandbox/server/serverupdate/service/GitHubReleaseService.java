package com.aisandbox.server.serverupdate.service;

import com.aisandbox.server.serverupdate.dto.ReleaseInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * UC-84 — resolves the newest published {@code server-v*} GitHub release for
 * the self-update check.
 *
 * <h2>No credentials, ever (AC13)</h2>
 *
 * Both the release lookup here and the {@code .deb} download performed later by
 * the root updater run fully unauthenticated against the PUBLIC repo. This
 * class attaches NO {@code Authorization} header, reads no token from secrets /
 * config / env, and provisions no credential dependency. The only request
 * header it sets are the GitHub-recommended {@code Accept} +
 * {@code X-GitHub-Api-Version} content negotiators.
 *
 * <h2>Why the JDK HTTP client, not WebClient</h2>
 *
 * The application is reactive (WebFlux on Reactor Netty). The proposal modelled
 * the call chain on the synchronous {@code HealthController → HealthFacade →
 * HealthService} exemplar (which blocks the request thread on {@code docker
 * info}). Reactor's {@code Mono.block()} throws {@code IllegalStateException}
 * when invoked on a {@code reactor-http-nio} event-loop thread, so a blocking
 * {@code WebClient.block()} inside a synchronous facade would fail at runtime.
 * The JDK's {@link HttpClient#send} blocks the calling thread WITHOUT tripping
 * Reactor's non-blocking-thread detector — matching the existing
 * {@code HealthService} blocking pattern exactly while keeping the whole chain
 * synchronous and trivially unit-testable (point {@link #apiBaseUrl} at a mock
 * server). This is a deliberate, surfaced deviation from the proposal's
 * "WebClient" wording; the contract (unauthenticated GET, no token) is
 * unchanged.
 */
@Service
public class GitHubReleaseService {

    private static final Logger LOG = LoggerFactory.getLogger(GitHubReleaseService.class);

    /** Hardcoded target repo (AC11/AC15) — the server never derives this from request input. */
    public static final String REPO = "HaroldHormaechea/ai-sandbox";

    /** Only the {@code server-v*} release track is considered; {@code android-v*} is ignored (AC15). */
    public static final String TRACK_PREFIX = "server-v";

    /** Architecture asset suffix — releases ship {@code ..._amd64.deb} (AC4, AC14 wrong-arch case). */
    public static final String DEB_ASSET_SUFFIX = "_amd64.deb";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String apiBaseUrl;

    @org.springframework.beans.factory.annotation.Autowired
    public GitHubReleaseService(
            @Value("${ai-sandbox.update.github-api-base:https://api.github.com}") String apiBaseUrl) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                new ObjectMapper(),
                apiBaseUrl);
    }

    /** Test seam — inject a client + base URL pointing at a mock server. */
    public GitHubReleaseService(HttpClient http, ObjectMapper mapper, String apiBaseUrl) {
        this.http = http;
        this.mapper = mapper;
        this.apiBaseUrl = stripTrailingSlash(apiBaseUrl);
    }

    /**
     * Fetch the newest published {@code server-v*} release.
     *
     * @return the newest {@link ReleaseInfo}, or {@code null} when the repo has
     *     no {@code server-v*} release at all.
     * @throws ServerUpdateException on transport failure, rate limiting, or an
     *     unusable GitHub response.
     */
    public ReleaseInfo latestServerRelease() {
        String url = apiBaseUrl + "/repos/" + REPO + "/releases?per_page=100";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "ai-sandbox-server")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ServerUpdateException.GitHubUnreachable("GitHub Releases API unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServerUpdateException.GitHubUnreachable("Interrupted contacting GitHub Releases API", e);
        }

        int status = response.statusCode();
        if (status == 403 || status == 429) {
            // Unauthenticated GitHub rate limit. NEVER fall back to a token (AC13).
            throw new ServerUpdateException.RateLimited(
                    "GitHub Releases API rate-limited (HTTP " + status + "); retry later.");
        }
        if (status / 100 != 2) {
            throw new ServerUpdateException.CheckFailed("GitHub Releases API returned HTTP " + status);
        }

        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (IOException e) {
            throw new ServerUpdateException.CheckFailed("Could not parse GitHub Releases response", e);
        }
        if (root == null || !root.isArray()) {
            throw new ServerUpdateException.CheckFailed("Unexpected GitHub Releases response shape (not an array)");
        }

        ReleaseInfo newest = null;
        String newestVersion = null;
        for (JsonNode rel : root) {
            if (rel.path("draft").asBoolean(false)) {
                continue; // unpublished drafts are not installable targets
            }
            String tag = rel.path("tag_name").asText("");
            if (!tag.startsWith(TRACK_PREFIX)) {
                continue; // ignore android-v* and anything off-track (AC15)
            }
            String version = tag.substring(TRACK_PREFIX.length());
            if (version.isBlank()) {
                continue;
            }
            if (newestVersion == null || compareSemver(version, newestVersion) > 0) {
                newestVersion = version;
                newest = new ReleaseInfo(version, rel.path("html_url").asText(null), findAmd64DebUrl(rel));
            }
        }
        if (newest == null) {
            LOG.info("No {}* release found for {}", TRACK_PREFIX, REPO);
        }
        return newest;
    }

    private static String findAmd64DebUrl(JsonNode release) {
        JsonNode assets = release.path("assets");
        if (!assets.isArray()) {
            return null;
        }
        for (JsonNode asset : assets) {
            String name = asset.path("name").asText("");
            if (name.endsWith(DEB_ASSET_SUFFIX)) {
                String url = asset.path("browser_download_url").asText(null);
                if (url != null && !url.isBlank()) {
                    return url;
                }
            }
        }
        return null;
    }

    /**
     * Semantic-version ordering of two dotted numeric versions (AC5). Returns a
     * negative / zero / positive int when {@code a} is older / equal / newer
     * than {@code b}. Each component is compared numerically; missing trailing
     * components are treated as 0 (so {@code 1.2} == {@code 1.2.0}). A
     * non-numeric component (e.g. a pre-release suffix) sorts as 0 for that
     * position — the project ships plain {@code X.Y.Z} tags, so this is a
     * defensive fallback, not a full SemVer pre-release implementation.
     */
    public static int compareSemver(String a, String b) {
        String[] pa = a.trim().split("\\.");
        String[] pb = b.trim().split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int na = i < pa.length ? parseLeadingInt(pa[i]) : 0;
            int nb = i < pb.length ? parseLeadingInt(pb[i]) : 0;
            int cmp = Integer.compare(na, nb);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static int parseLeadingInt(String s) {
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(s.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String stripTrailingSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /** Comparator over {@link ReleaseInfo} by semantic version (oldest → newest). */
    public static final Comparator<ReleaseInfo> BY_VERSION =
            Comparator.comparing(ReleaseInfo::version, GitHubReleaseService::compareSemver);
}
