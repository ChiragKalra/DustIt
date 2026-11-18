package com.bruhascended.dustit.ui.whatsapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bruhascended.dustit.R
import com.bruhascended.dustit.appContainer
import com.bruhascended.dustit.scan.ScanReason
import kotlinx.coroutines.launch

/**
 * Returns an action that opens the system folder picker on WhatsApp's media folder. A good pick
 * is kept and scanned right away; anything else gets a dialog explaining what to pick.
 */
@Composable
fun rememberWhatsAppConnect(): () -> Unit {
    val container = LocalContext.current.appContainer
    val scope = rememberCoroutineScope()
    var wrongFolder by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { picked ->
        if (picked == null) return@rememberLauncherForActivityResult
        scope.launch {
            if (container.whatsApp.connect(picked)) {
                // After any scan already running, so the new folders are picked up straight away.
                container.work.scan(ScanReason.Changed)
            } else {
                wrongFolder = true
            }
        }
    }
    val open = remember(launcher) { { launcher.launch(container.whatsApp.pickerStart()) } }

    if (wrongFolder) {
        AlertDialog(
            onDismissRequest = { wrongFolder = false },
            title = { Text(stringResource(R.string.wa_wrong_folder_title)) },
            text = { Text(stringResource(R.string.wa_wrong_folder_body)) },
            confirmButton = {
                TextButton(onClick = { wrongFolder = false; open() }) { Text(stringResource(R.string.wa_try_again)) }
            },
            dismissButton = {
                TextButton(onClick = { wrongFolder = false }) { Text(stringResource(R.string.limited_access_not_now)) }
            },
        )
    }
    return open
}
