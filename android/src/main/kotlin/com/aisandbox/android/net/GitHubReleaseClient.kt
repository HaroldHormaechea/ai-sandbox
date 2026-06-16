package com.aisandbox.android.net

import android.util.Log
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * UC-87 — plain, fully unauthenticated GitHub Releases client for the Android
 * app self-update flow (the client analogue of the server's
 * {@code GitHubReleaseService}).
 *
 * <h2>No GitHub account, ever (AC7)</h2>
 *
 * <p>This deliberately does NOT reuse [AiSandboxHttpClient] (which carries the
 * mTLS client cert + SPKI pin for the user's own server). It is a stock
 * [OkHttpClient] with the platform's default trust store, <b>no</b> client
 * certificate, and <b>no</b> {@code Authorization} header on any request — the
 * version check and the APK download both run against the PUBLIC repo with zero
 * credentials. Redirects are followed with OkHttp defaults so a
 * {@code browser_download_url} 302 to {@code objects.githubusercontent.com}
 * resolves; since no auth header is ever attached, none can leak across the
 * redirect.
 *
 * <p>All failures are returned as typed results — this never throws to the UI.
 */
class GitHubReleaseClient(
    private val client: OkHttpClient = defaultClient(),
    private val apiBaseUrl: String = "https://api.github.com",
) {

    /** Outcome of the latest-release lookup. */
    sealed interface ReleaseCheckResult {
        /** A newest stable {@code android-v*} release exists. */
        data class Available(
            val latestVersion: String,
            val releaseHtmlUrl: String?,
            val apkAssetUrl: String?,
        ) : ReleaseCheckResult

        /** The repo has no stable {@code android-v*} release at all. */
        data object NoRelease : ReleaseCheckResult

        /** GitHub's unauthenticated REST API rate-limited us (HTTP 403/429) — never fall back to a token (AC7/AC8). */
        data object RateLimited : ReleaseCheckResult

        /** Transport failure — offline or GitHub unreachable (AC8). */
        data class Unreachable(val detail: String) : ReleaseCheckResult

        /** GitHub answered but the response was unusable (non-2xx, unparseable, wrong shape). */
        data class CheckFailed(val detail: String) : ReleaseCheckResult
    }

    /** Outcome of an APK download. */
    sealed interface DownloadResult {
        data class Success(val file: File) : DownloadResult

        data class Failed(val detail: String) : DownloadResult
    }

    /**
     * Fetch the newest stable {@code android-v*} release via the public,
     * unauthenticated Releases API and resolve its APK asset (AC2). Pure-result;
     * never throws.
     */
    suspend fun latestStableRelease(): ReleaseCheckResult = withContext(Dispatchers.IO) {
        val url = "$apiBaseUrl/repos/$REPO/releases?per_page=100"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val code = resp.code
                if (code == 403 || code == 429) return@use ReleaseCheckResult.RateLimited
                if (code !in 200..299) {
                    return@use ReleaseCheckResult.CheckFailed("GitHub returned HTTP $code")
                }
                val body = resp.body?.string().orEmpty()
                val releases = try {
                    JSON.decodeFromString(ListSerializer(GitHubRelease.serializer()), body)
                } catch (t: Throwable) {
                    return@use ReleaseCheckResult.CheckFailed("Could not parse GitHub response: ${t.message}")
                }
                val resolved = AppReleaseResolver.resolveLatestStable(releases)
                    ?: return@use ReleaseCheckResult.NoRelease
                ReleaseCheckResult.Available(resolved.version, resolved.releaseHtmlUrl, resolved.apkAssetUrl)
            }
        } catch (t: IOException) {
            Log.w(TAG, "release check transport error: ${t.message}", t)
            ReleaseCheckResult.Unreachable(t.message ?: "GitHub unreachable")
        } catch (t: Throwable) {
            Log.w(TAG, "release check failed: ${t.message}", t)
            ReleaseCheckResult.CheckFailed(t.message ?: "Update check failed")
        }
    }

    /**
     * Stream the APK asset at [url] to [destFile], reporting integer percent via
     * [onProgress] (AC5). Public URL, no auth header. Pure-result; on any
     * failure returns [DownloadResult.Failed] and never throws to the UI. The
     * caller owns deleting a partial [destFile] on failure.
     */
    suspend fun download(
        url: String,
        destFile: File,
        onProgress: (Int) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        try {
            destFile.parentFile?.mkdirs()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@use DownloadResult.Failed("Download failed (HTTP ${resp.code})")
                }
                val body = resp.body ?: return@use DownloadResult.Failed("Empty response body")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    destFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastPct = -1
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                        output.flush()
                    }
                }
                DownloadResult.Success(destFile)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "apk download failed: ${t.message}", t)
            DownloadResult.Failed(t.message ?: "Download failed")
        }
    }

    companion object {
        /** Hardcoded target repo (AC7 — never derived from user input / account). */
        const val REPO = "HaroldHormaechea/ai-sandbox"
        const val USER_AGENT = "ai-sandbox-android"
        private const val TAG = "GitHubReleaseClient"
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Stock client: default trust store, NO client cert, NO auth header,
         * redirects on (so the asset 302 resolves). Generous read timeout for
         * the ~67 MB APK.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }
}
