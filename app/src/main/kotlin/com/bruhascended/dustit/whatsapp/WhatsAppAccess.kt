package com.bruhascended.dustit.whatsapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.bruhascended.dustit.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

/**
 * Access to WhatsApp's hidden folders: a persisted grant on the WhatsApp "Media" folder (or a
 * folder above it), picked once by the user in the system folder picker. No broad storage
 * permission is involved, and the user can see exactly which folder they shared.
 */
class WhatsAppAccess(context: Context, private val settings: SettingsRepository) {
    private val resolver = context.contentResolver
    private val packageManager = context.packageManager

    /** The granted folder, or null if never granted or the grant is gone (e.g. app data was cleared). */
    val tree: Flow<Uri?> = settings.whatsAppTree.map { stored -> stored?.toUri()?.takeIf(::isGranted) }

    suspend fun currentTree(): Uri? = tree.first()

    fun isInstalled(): Boolean = WhatsAppLayout.apps.any { isInstalled(it.packageName) }

    /** Where the folder picker opens: the media folder of the installed WhatsApp, so it's one tap. */
    fun pickerStart(): Uri {
        val app = WhatsAppLayout.apps.firstOrNull { isInstalled(it.packageName) } ?: WhatsAppLayout.apps.first()
        return DocumentsContract.buildDocumentUri(WhatsAppLayout.STORAGE_AUTHORITY, app.mediaRootDocumentId)
    }

    /** Keeps the grant for [picked] if it reaches a WhatsApp media folder; false if the user picked something else. */
    suspend fun connect(picked: Uri): Boolean {
        if (picked.authority != WhatsAppLayout.STORAGE_AUTHORITY) return false
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(picked) }.getOrNull() ?: return false
        if (WhatsAppLayout.appsUnder(treeId).isEmpty()) return false
        resolver.takePersistableUriPermission(picked, GRANT_FLAGS)
        val previous = settings.whatsAppTree.first()
        if (previous != null && previous != picked.toString()) release(previous.toUri())
        settings.setWhatsAppTree(picked.toString())
        return true
    }

    suspend fun disconnect() {
        settings.whatsAppTree.first()?.let { release(it.toUri()) }
        settings.setWhatsAppTree(null)
    }

    private fun release(uri: Uri) {
        runCatching { resolver.releasePersistableUriPermission(uri, GRANT_FLAGS) }
    }

    private fun isGranted(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }

    private fun isInstalled(packageName: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            // The flags overload doesn't exist before Android 13; calling it crashed on Android 11.
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
