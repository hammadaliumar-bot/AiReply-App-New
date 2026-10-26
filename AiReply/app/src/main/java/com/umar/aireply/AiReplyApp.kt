package com.umar.aireply

import android.app.Application
import com.umar.aireply.data.ChatImportRepository
import com.umar.aireply.data.ContactRepository
import com.umar.aireply.data.ContactWatcher
import com.umar.aireply.data.SettingsManager
import com.umar.aireply.data.db.AppDatabase
import com.umar.aireply.net.GeminiApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Application object: one shared place for the database, settings and network client. */
class AiReplyApp : Application() {

    /** Long-lived scope for background work. A failure in one child never cancels the others. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val settings: SettingsManager by lazy { SettingsManager(this) }
    val gemini: GeminiApiService by lazy { GeminiApiService() }
    val contacts: ContactRepository by lazy { ContactRepository(this, database.contactDao()) }
    val contactWatcher: ContactWatcher by lazy { ContactWatcher(this, appScope, contacts) }
    val chatImport: ChatImportRepository by lazy { ChatImportRepository(database, gemini, settings) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: AiReplyApp
            private set
    }
}
