package com.bruhascended.dustit.ui.permission

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bruhascended.dustit.R
import com.bruhascended.dustit.permissions.MediaAccess
import com.bruhascended.dustit.permissions.openAppSettings
import com.bruhascended.dustit.permissions.primaryMediaPermission

/** Shown whenever DustIt lacks full photo access: never asked, denied, or limited to picked photos. */
@Composable
fun PermissionScreen(access: MediaAccess, askedOnce: Boolean, onRequestAccess: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val limited = access == MediaAccess.Limited
    // After a denial Android stops showing the prompt once rationale is no longer offered,
    // so the only way forward is the app's Settings page.
    val blocked = askedOnce && activity?.shouldShowRequestPermissionRationale(primaryMediaPermission) == false
    // With limited access, asking again only re-opens the photo picker; full access is granted in Settings.
    val viaSettings = limited || blocked

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer),
                    modifier = Modifier.size(112.dp),
                )
            }
            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(if (limited) R.string.permission_limited_title else R.string.permission_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    when {
                        limited -> R.string.permission_limited_body
                        blocked -> R.string.permission_denied
                        else -> R.string.permission_body
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = { if (viaSettings) context.openAppSettings() else onRequestAccess() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (viaSettings) R.string.permission_open_settings else R.string.permission_grant))
            }
        }
    }
}

/** Popup for the moment someone picks "Allow limited access": says why that won't work and links to Settings. */
@Composable
fun LimitedAccessDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.limited_access_title)) },
        text = { Text(stringResource(R.string.limited_access_body)) },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.permission_open_settings)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.limited_access_not_now)) }
        },
    )
}
