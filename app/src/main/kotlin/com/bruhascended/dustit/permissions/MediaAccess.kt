package com.bruhascended.dustit.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

enum class MediaAccess {
    /** Every photo on the device is visible. The only state DustIt works in. */
    Full,

    /**
     * Android 14+: the user chose "Allow limited access" and shared only some photos. Not
     * supported: DustIt can't find clutter it can't see, and syncing in this state would shrink
     * the index to the handful of picked photos. Treated like no access, with a pointer to Settings.
     */
    Limited,
    None,
}

fun Context.mediaAccess(): MediaAccess {
    fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val sdk = Build.VERSION.SDK_INT
    return when {
        sdk >= Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_MEDIA_IMAGES) -> MediaAccess.Full
        sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.Limited
        sdk < Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> MediaAccess.Full
        else -> MediaAccess.None
    }
}

/** The main permission that decides access; used to tell "denied once" from "denied for good". */
val primaryMediaPermission: String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

/**
 * Everything to request in one go. Photos and videos share one system dialog. Notifications are
 * optional: they show scan progress and weekly reminders.
 */
val mediaPermissionRequest: Array<String> = buildList {
    add(primaryMediaPermission)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.READ_MEDIA_VIDEO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

/**
 * Opens this app's page in system Settings, the closest an app can get to its own permission
 * screen. From there: Permissions, then Photos and videos, then "Always allow all".
 */
fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}
