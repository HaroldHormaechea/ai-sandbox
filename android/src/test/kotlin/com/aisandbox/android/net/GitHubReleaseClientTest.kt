package com.aisandbox.android.net

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * UC-87 — [GitHubReleaseClient] exercised over a PLAIN (unauthenticated, no-TLS)
 * [MockWebServer]. Unlike the pinned-HTTPS server harness used for the user's
 * own ai-sandbox server, this is a stock OkHttp client hitting a PUBLIC
 * endpoint, so a plain `http://` MockWebServer reproduces the wire contract
 * exactly — and lets us assert the CREDENTIAL-FREE invariant (no `Authorization`
 * header on any request) directly off the recorded requests.
 *
 * <h2>AC → test map</h2>
 *
 * <ul>
 *   <li>AC5/AC7 (no auth) — {@link #latest_release_sends_no_authorization_header()},
 *       {@link #download_sends_no_authorization_header_across_a_redirect()}.</li>
 *   <li>AC2 (resolve newest stable) — {@link #latest_release_resolves_newest_stable_android_track()}.</li>
 *   <li>AC8 (graceful, never crashes) — {@link #http_403_maps_to_rate_limited()},
 *       {@link #http_429_maps_to_rate_limited()},
 *       {@link #transport_failure_maps_to_unreachable()},
 *       {@link #non_2xx_maps_to_check_failed()},
 *       {@link #malformed_body_maps_to_check_failed()}.</li>
 *   <li>AC2 (no android release) — {@link #only_server_track_maps_to_no_release()}.</li>
 *   <li>AC5 (download) — {@link #download_streams_bytes_and_reports_progress()},
 *       {@link #download_http_failure_returns_failed_without_throwing()}.</li>
 * </ul>
 */
class GitHubReleaseClientTest {

    private lateinit var server: MockWebServer
    private lateinit var tmpDir: File

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        tmpDir = Files.createTempDirectory("uc87-dl").toFile()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        tmpDir.deleteRecursively()
    }

    /** Client pointed at the plain-HTTP mock; the REST base URL is overridden, the download URL is passed in full. */
    private fun client(): GitHubReleaseClient =
        GitHubReleaseClient(apiBaseUrl = server.url("/").toString().trimEnd('/'))

    private fun releasesJson(): String = """
        [
          {"tag_name":"server-v9.9.9","draft":false,"prerelease":false,"html_url":"https://gh/server-v9.9.9","assets":[]},
          {"tag_name":"android-v0.5.0-rc1","draft":false,"prerelease":true,"html_url":"https://gh/rc","assets":[]},
          {"tag_name":"android-v0.10.0","draft":false,"prerelease":false,"html_url":"https://gh/android-v0.10.0",
           "assets":[{"name":"android-release.apk","browser_download_url":"https://gh/dl/android-v0.10.0/android-release.apk"}]},
          {"tag_name":"android-v0.9.0","draft":false,"prerelease":false,"html_url":"https://gh/android-v0.9.0",
           "assets":[{"name":"android-release.apk","browser_download_url":"https://gh/dl/android-v0.9.0/android-release.apk"}]}
        ]
    """.trimIndent()

    // ── AC2 — resolve newest stable android-v* ──────────────────────────────

    @Test
    fun latest_release_resolves_newest_stable_android_track() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(releasesJson()))
        val result = client().latestStableRelease()
        assertThat(result).isInstanceOf(GitHubReleaseClient.ReleaseCheckResult.Available::class.java)
        val avail = result as GitHubReleaseClient.ReleaseCheckResult.Available
        // 0.10.0 beats 0.9.0 by semver; the -rc1 and server-v* entries are excluded.
        assertThat(avail.latestVersion).isEqualTo("0.10.0")
        assertThat(avail.apkAssetUrl).isEqualTo("https://gh/dl/android-v0.10.0/android-release.apk")
        assertThat(avail.releaseHtmlUrl).isEqualTo("https://gh/android-v0.10.0")
    }

    // ── AC5/AC7 — credential-free on every request ──────────────────────────

    @Test
    fun latest_release_sends_no_authorization_header() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(releasesJson()))
        client().latestStableRelease()
        val recorded = server.takeRequest()
        // AC7 — the public version check carries ZERO credentials.
        assertThat(recorded.getHeader("Authorization")).isNull()
        assertThat(recorded.getHeader("authorization")).isNull()
        // It does hit the public Releases API path for the hardcoded repo.
        assertThat(recorded.path).contains("/repos/${GitHubReleaseClient.REPO}/releases")
    }

    // ── AC8 — graceful failure mapping (never crashes) ──────────────────────

    @Test
    fun http_403_maps_to_rate_limited() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("rate limit exceeded"))
        assertThat(client().latestStableRelease())
            .isEqualTo(GitHubReleaseClient.ReleaseCheckResult.RateLimited)
    }

    @Test
    fun http_429_maps_to_rate_limited() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("too many requests"))
        assertThat(client().latestStableRelease())
            .isEqualTo(GitHubReleaseClient.ReleaseCheckResult.RateLimited)
    }

    @Test
    fun transport_failure_maps_to_unreachable() = runBlocking {
        // Shut the server down so the connection is refused → offline / unreachable (AC8).
        val c = client()
        server.shutdown()
        val result = c.latestStableRelease()
        assertThat(result).isInstanceOf(GitHubReleaseClient.ReleaseCheckResult.Unreachable::class.java)
    }

    @Test
    fun non_2xx_maps_to_check_failed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val result = client().latestStableRelease()
        assertThat(result).isInstanceOf(GitHubReleaseClient.ReleaseCheckResult.CheckFailed::class.java)
        assertThat((result as GitHubReleaseClient.ReleaseCheckResult.CheckFailed).detail).contains("500")
    }

    @Test
    fun malformed_body_maps_to_check_failed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{not json"))
        val result = client().latestStableRelease()
        assertThat(result).isInstanceOf(GitHubReleaseClient.ReleaseCheckResult.CheckFailed::class.java)
    }

    @Test
    fun only_server_track_maps_to_no_release() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """[{"tag_name":"server-v1.2.3","draft":false,"prerelease":false,"assets":[]}]""",
            ),
        )
        assertThat(client().latestStableRelease())
            .isEqualTo(GitHubReleaseClient.ReleaseCheckResult.NoRelease)
    }

    // ── AC5 — APK download (stream + progress + clean failure) ──────────────

    @Test
    fun download_streams_bytes_and_reports_progress() = runBlocking {
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() } // > one 64 KiB buffer
        val body = Buffer().write(payload)
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val dest = File(tmpDir, "android-release.apk")
        val pcts = mutableListOf<Int>()
        val result = client().download(server.url("/dl/android-release.apk").toString(), dest) { pcts.add(it) }

        assertThat(result).isInstanceOf(GitHubReleaseClient.DownloadResult.Success::class.java)
        assertThat(dest.exists()).isTrue
        assertThat(dest.readBytes()).isEqualTo(payload)
        // AC5 — progress is reported and ends at 100%.
        assertThat(pcts).isNotEmpty
        assertThat(pcts.last()).isEqualTo(100)
        assertThat(pcts).isSorted // monotonic non-decreasing
    }

    @Test
    fun download_http_failure_returns_failed_without_throwing() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val dest = File(tmpDir, "android-release.apk")
        val result = client().download(server.url("/dl/missing.apk").toString(), dest) { }
        // AC5/AC8 — a download failure is a typed result, never a thrown crash.
        assertThat(result).isInstanceOf(GitHubReleaseClient.DownloadResult.Failed::class.java)
        assertThat((result as GitHubReleaseClient.DownloadResult.Failed).detail).contains("404")
    }

    @Test
    fun download_sends_no_authorization_header_across_a_redirect() = runBlocking {
        // 302 to a second path on the same mock; OkHttp follows it (followRedirects).
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/objects/android-release.apk").toString()),
        )
        val payload = ByteArray(4096) { 7 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))

        val dest = File(tmpDir, "android-release.apk")
        val result = client().download(server.url("/dl/android-release.apk").toString(), dest) { }

        assertThat(result).isInstanceOf(GitHubReleaseClient.DownloadResult.Success::class.java)
        assertThat(dest.readBytes()).isEqualTo(payload)
        // AC5/AC7 — neither the original request nor the redirected one carries auth.
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertThat(first.getHeader("Authorization")).isNull()
        assertThat(second.getHeader("Authorization")).isNull()
        assertThat(second.path).contains("/objects/android-release.apk")
    }
}
