package com.bruhascended.dustit.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bruhascended.dustit.scan.BackgroundWork
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

private val AUTO_SCAN = booleanPreferencesKey("auto_scan")
private val REMINDER_BUCKETS = stringSetPreferencesKey("reminder_buckets")
private val WHATSAPP_TREE = stringPreferencesKey("whatsapp_tree")
private val WHATSAPP_PROMO_DISMISSED = booleanPreferencesKey("whatsapp_promo_dismissed")

/** User preferences. Every setter also brings the scheduled background work in line. */
class SettingsRepository(context: Context, private val work: BackgroundWork) {
    private val store = context.dataStore

    /** Analyse new photos in the background as they arrive. On by default. */
    val autoScan: Flow<Boolean> = store.data.map { it[AUTO_SCAN] ?: true }

    /** Keys of buckets the user wants a weekly reminder for. */
    val reminderBuckets: Flow<Set<String>> = store.data.map { it[REMINDER_BUCKETS].orEmpty() }

    /** URI of the folder granted for WhatsApp's hidden folders; check the grant still exists before use. */
    val whatsAppTree: Flow<String?> = store.data.map { it[WHATSAPP_TREE] }

    /** The user said "Not now" to the WhatsApp card on the home screen; Settings still offers it. */
    val whatsAppPromoDismissed: Flow<Boolean> = store.data.map { it[WHATSAPP_PROMO_DISMISSED] ?: false }

    suspend fun setWhatsAppTree(uri: String?) {
        store.edit { if (uri == null) it.remove(WHATSAPP_TREE) else it[WHATSAPP_TREE] = uri }
    }

    suspend fun dismissWhatsAppPromo() {
        store.edit { it[WHATSAPP_PROMO_DISMISSED] = true }
    }

    suspend fun setAutoScan(enabled: Boolean) {
        store.edit { it[AUTO_SCAN] = enabled }
        work.watchForNewMedia(enabled)
    }

    suspend fun setReminder(bucketKey: String, enabled: Boolean) {
        val prefs = store.edit {
            val current = it[REMINDER_BUCKETS].orEmpty()
            it[REMINDER_BUCKETS] = if (enabled) current + bucketKey else current - bucketKey
        }
        work.scheduleReminders(prefs[REMINDER_BUCKETS].orEmpty().isNotEmpty())
    }

    /** Re-applies stored settings to WorkManager, e.g. after app data or scheduled jobs were reset. */
    suspend fun applyToBackgroundWork() {
        work.watchForNewMedia(autoScan.first())
        work.scheduleReminders(reminderBuckets.first().isNotEmpty())
    }
}
