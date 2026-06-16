package com.aisandbox.android.install

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.aisandbox.android.ui.screens.InstallOutcome
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UC-87 — launches the Android system package-installer for a downloaded
 * release APK (AC6).
 *
 * <p>Two gates, in order:
 *
 * <ol>
 *   <li><b>"Install unknown apps" grant</b> — Android 8+ requires the per-app
 *       {@code REQUEST_INSTALL_PACKAGES} runtime grant. If
 *       {@link android.content.pm.PackageManager#canRequestPackageInstalls()}
 *       is false, this opens
 *       {@link Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES} scoped to our package
 *       and returns [InstallOutcome.NeedsPermission] so the screen can say
 *       "grant, then retry" — it never dead-ends (AC6).</li>
 *   <li><b>Install intent</b> — once granted, it hands the APK to the installer
 *       via a {@link FileProvider} content URI ({@code ACTION_VIEW},
 *       {@code application/vnd.android.package-archive}, with
 *       {@code FLAG_GRANT_READ_URI_PERMISSION}).</li>
 * </ol>
 *
 * <p>No GitHub credential is involved on any path (AC7) — this only operates on
 * the already-downloaded local file.
 */
class ApkInstaller(private val context: Context) {

    /**
     * Launch the installer for [apk]. Returns a pure [InstallOutcome]; never
     * throws — a failure to launch surfaces as [InstallOutcome.Failed].
     */
    suspend fun install(apk: File): InstallOutcome = withContext(Dispatchers.Main) {
        try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                requestUnknownSourcesPermission()
                return@withContext InstallOutcome.NeedsPermission
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallOutcome.Launched
        } catch (t: Throwable) {
            Log.w(TAG, "could not launch installer: ${t.message}", t)
            InstallOutcome.Failed(t.message ?: "Could not launch the installer.")
        }
    }

    /** AC6 — open the per-app "install unknown apps" settings toggle for our package. */
    private fun requestUnknownSourcesPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Some OEM ROMs lack the per-app screen; the retry path still shows the
            // guidance copy, and a later install attempt re-checks the grant.
            Log.w(TAG, "unknown-sources settings screen unavailable: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "ApkInstaller"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
