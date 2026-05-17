package com.aisandbox.android.ui.screens

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisandbox.android.AiSandboxApplication
import com.aisandbox.android.identity.KeyStoreIdentityManager
import com.aisandbox.android.net.EnrollmentClient
import com.aisandbox.android.net.QrPayload
import com.aisandbox.android.net.ServerProfile
import com.aisandbox.android.net.ServerProfileStore
import java.security.cert.X509Certificate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State machine for the UC04-1 onboarding flow.
 *
 * <p>States (per the design's three-stage sequence + the AC6 replace-
 * confirmation branch):
 *
 * <pre>
 *   Permission ──┐
 *                │
 *   Scanning ◀───┘
 *      │
 *      ▼ (QR decoded)
 *   ┌───────────┐    ConfirmReplace ◀─── (existing profile present)
 *   │ payload   │           │
 *   │ in hand   │           ▼ (operator confirms)
 *   └─────┬─────┘    Enrolling (POST /v1/enrollment)
 *         │                  │
 *         ▼                  ▼
 *      Enrolling ───────► Imported ─── (operator taps Continue) ──► onContinue
 *                            │
 *                            └─── Failure (network / server / parse) ──► Scanning
 * </pre>
 *
 * <p>The ViewModel owns the [ServerProfileStore] write at
 * {@code transition: Enrolling → Imported}; the screen only renders.
 */
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as AiSandboxApplication).container
    private val profileStore: ServerProfileStore = container.profileStore
    private val identity: KeyStoreIdentityManager = container.identity

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.NeedsCameraPermission)
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    /** Called after the system camera permission dialog resolves. */
    fun onCameraPermissionGranted() {
        _state.value = OnboardingState.Scanning
    }

    fun onCameraPermissionDenied() {
        _state.value = OnboardingState.NeedsCameraPermission
    }

    /** Called when the QR scanner emits a payload. */
    fun onQrPayload(raw: String) {
        viewModelScope.launch {
            val parsed = QrPayload.parse(raw).getOrElse {
                _state.value = OnboardingState.Failure(
                    code = "bad_qr",
                    message = "QR payload is not a valid ai-sandbox invite: ${it.message}",
                )
                return@launch
            }
            val existing = profileStore.current()
            _state.value = if (existing != null) {
                OnboardingState.ConfirmReplace(parsed)
            } else {
                enroll(parsed)
                return@launch
            }
        }
    }

    /** Operator confirmed the replace-existing-identity prompt (AC6). */
    fun onConfirmReplace() {
        val s = _state.value
        if (s !is OnboardingState.ConfirmReplace) return
        viewModelScope.launch {
            // AC6 — clear old KeyStore entry + profile BEFORE the
            // network call so a half-failed redemption can't leave a
            // chimera "old profile + new cert" state behind.
            identity.wipe()
            profileStore.clear()
            enroll(s.payload)
        }
    }

    /** Operator declined the replace; bounce back to the scanning state. */
    fun onCancelReplace() {
        _state.value = OnboardingState.Scanning
    }

    private suspend fun enroll(payload: QrPayload): OnboardingState {
        _state.value = OnboardingState.Enrolling(payload)
        val outcome = EnrollmentClient(payload).redeem()
        return when (outcome) {
            is EnrollmentClient.Outcome.Success -> {
                val cert = try {
                    val result = identity.importPkcs12(outcome.pkcs12)
                    result.leaf
                } catch (t: Throwable) {
                    Log.w(TAG, "Cert import failed: ${t.message}", t)
                    _state.value = OnboardingState.Failure(
                        code = "import_failed",
                        message = "Cannot import client cert: ${t.message ?: "unknown"}",
                    )
                    return _state.value
                }
                val profile = ServerProfile(
                    serverUrl = payload.serverUrl,
                    pinSha256Hex = payload.pinSha256Hex,
                    clientCertCn = cert.subjectX500Principal.name,
                    clientCertExpiresAtMs = cert.notAfter.time,
                )
                profileStore.save(profile)
                val imported = OnboardingState.Imported(profile = profile, cert = cert)
                _state.value = imported
                imported
            }
            is EnrollmentClient.Outcome.Failure -> {
                val failure = OnboardingState.Failure(code = outcome.code, message = outcome.message)
                _state.value = failure
                failure
            }
        }
    }

    /** Restart the scanning state after a Failure (operator taps "Try again"). */
    fun onRestartScan() {
        _state.value = OnboardingState.Scanning
    }

    companion object {
        private const val TAG = "OnboardingVM"
    }
}

/**
 * UI state for the onboarding screen. Modelled as a sealed type so
 * Compose recomposition is exhaustive and the state machine is easy to
 * audit.
 */
sealed interface OnboardingState {
    /** Pre-permission state; the screen shows the rationale + grant button. */
    data object NeedsCameraPermission : OnboardingState

    /** Live camera preview; the [QrScanner] emits on the first valid QR. */
    data object Scanning : OnboardingState

    /**
     * AC6 — replacing an existing identity. Operator must explicitly
     * confirm before we clear the old cert.
     */
    data class ConfirmReplace(val payload: QrPayload) : OnboardingState

    /** Network call in flight — disable the FAB so the operator doesn't double-tap. */
    data class Enrolling(val payload: QrPayload) : OnboardingState

    /**
     * Success — show the AC4 "Identity imported" panel with server URL,
     * pin, cert CN + expiry, KeyStore badge, Continue button.
     */
    data class Imported(val profile: ServerProfile, val cert: X509Certificate) : OnboardingState

    /** Any failure path. Operator taps "Try again" → restart scanning. */
    data class Failure(val code: String, val message: String) : OnboardingState
}
