package com.aisandbox.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aisandbox.android.R
import com.aisandbox.android.ui.theme.ErrorTone

/**
 * UC04-7 — "Identity revoked" M3 dialog. Surfaced when [StreamClient]
 * receives a close-code 4401 from the server (AC25/AC26) and emits
 * [NetworkEvent.CertRevoked] on the global bus.
 *
 * <p>Dismissal goes back to the OnboardingScreen — UC04 § Screen
 * inventory states "the dialog dismisses to the import screen, not back
 * to the sessions list — there is no functional state left."
 */
@Composable
fun CertRevokedScreen(
    onLater: () -> Unit,
    onScanNewQr: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                tint = ErrorTone,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.cert_revoked_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Text(
                text = stringResource(R.string.cert_revoked_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onLater) {
                    Text(stringResource(R.string.cert_revoked_later))
                }
                TextButton(onClick = onScanNewQr) {
                    Text(
                        text = stringResource(R.string.cert_revoked_scan),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    )
}
