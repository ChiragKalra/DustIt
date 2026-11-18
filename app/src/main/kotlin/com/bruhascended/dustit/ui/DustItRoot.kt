package com.bruhascended.dustit.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bruhascended.dustit.appContainer
import com.bruhascended.dustit.cleanup.Bucket
import com.bruhascended.dustit.permissions.MediaAccess
import com.bruhascended.dustit.permissions.mediaAccess
import com.bruhascended.dustit.permissions.mediaPermissionRequest
import com.bruhascended.dustit.permissions.openAppSettings
import com.bruhascended.dustit.ui.home.HomeScreen
import com.bruhascended.dustit.ui.permission.LimitedAccessDialog
import com.bruhascended.dustit.ui.permission.PermissionScreen
import com.bruhascended.dustit.ui.review.ReviewScreen
import com.bruhascended.dustit.ui.settings.SettingsScreen

private const val ROUTE_HOME = "home"
private const val ROUTE_SETTINGS = "settings"
const val ARG_BUCKET = "bucket"
private const val ROUTE_REVIEW = "review/{$ARG_BUCKET}"

private fun reviewRoute(bucketKey: String) = "review/$bucketKey"

@Composable
fun DustItRoot(bucketToOpen: String?, onBucketOpened: () -> Unit) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(context.mediaAccess()) }
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    var limitedWarningDismissed by rememberSaveable { mutableStateOf(false) }

    // Re-check on every resume: access can change in Settings, and new photos may have arrived.
    // Does nothing while a scan is running; with nothing new it's a quick MediaStore diff.
    LifecycleResumeEffect(Unit) {
        access = context.mediaAccess()
        if (access == MediaAccess.Full) context.appContainer.work.scan()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        askedOnce = true
        access = context.mediaAccess()
        limitedWarningDismissed = false // a fresh answer gets a fresh warning
        if (access == MediaAccess.Full) context.appContainer.work.scan()
    }

    // Full access or nothing: limited access ("only the photos I pick") is not supported.
    if (access != MediaAccess.Full) {
        PermissionScreen(
            access = access,
            askedOnce = askedOnce,
            onRequestAccess = { permissionLauncher.launch(mediaPermissionRequest) },
        )
        if (access == MediaAccess.Limited && !limitedWarningDismissed) {
            LimitedAccessDialog(
                // Stays up while they're in Settings; it goes away by itself once access is full.
                onOpenSettings = { context.openAppSettings() },
                onDismiss = { limitedWarningDismissed = true },
            )
        }
        return
    }

    val navController = rememberNavController()

    LaunchedEffect(bucketToOpen) {
        val bucket = Bucket.fromKey(bucketToOpen) ?: return@LaunchedEffect
        navController.navigate(reviewRoute(bucket.key)) {
            popUpTo(ROUTE_HOME)
            launchSingleTop = true
        }
        onBucketOpened()
    }

    NavHost(navController, startDestination = ROUTE_HOME) {
        composable(ROUTE_HOME) {
            HomeScreen(
                onOpenBucket = { navController.navigate(reviewRoute(it.key)) },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
            )
        }
        composable(
            ROUTE_REVIEW,
            arguments = listOf(navArgument(ARG_BUCKET) { type = NavType.StringType }),
        ) {
            ReviewScreen(onBack = { navController.popBackStack() })
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
