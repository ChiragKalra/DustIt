package com.bruhascended.dustit

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.bruhascended.dustit.data.DustDatabase
import com.bruhascended.dustit.data.MediaRepository
import com.bruhascended.dustit.reminders.ReminderNotifications
import com.bruhascended.dustit.scan.BackgroundWork
import com.bruhascended.dustit.scan.ScanNotifications
import com.bruhascended.dustit.scan.Scanner
import com.bruhascended.dustit.settings.SettingsRepository
import com.bruhascended.dustit.ui.image.MediaThumbnailFetcher
import com.bruhascended.dustit.ui.image.MediaThumbnailKeyer
import com.bruhascended.dustit.ui.image.VideoFrameFetcher
import com.bruhascended.dustit.ui.image.VideoFrameKeyer
import com.bruhascended.dustit.whatsapp.WhatsAppAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Hand-wired dependencies; the graph is small enough not to need a DI framework yet. */
class AppContainer(context: Context) {
    /** Lives as long as the process; for work that must outlast any one screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val database = DustDatabase.build(context)
    val mediaRepository = MediaRepository(context, database, scope)
    val work = BackgroundWork(context, scope)
    val settings = SettingsRepository(context, work)
    val whatsApp = WhatsAppAccess(context, settings)
    val scanner = Scanner(context, mediaRepository, whatsApp)
}

class DustItApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ScanNotifications.createChannel(this)
        ReminderNotifications.createChannel(this)
        // Idempotent; covers fresh installs and jobs Android dropped.
        container.scope.launch { container.settings.applyToBackgroundWork() }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(MediaThumbnailFetcher.Factory())
                add(MediaThumbnailKeyer())
                add(VideoFrameFetcher.Factory())
                add(VideoFrameKeyer())
            }
            .build()
}

val Context.appContainer: AppContainer
    get() = (applicationContext as DustItApp).container
