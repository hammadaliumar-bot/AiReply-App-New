# Fix: Model not found
Replace the whole content of each of these 6 files in your GitHub repo with the code below.

=== FILE: app/src/main/java/com/umar/aireply/net/GeminiApiService.kt ===
```kotlin
package com.umar.aireply.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---------- JSON models ----------

@Serializable
data class GeminiPart(val text: String = "")

@Serializable
data class GeminiContent(val role: String? = null, val parts: List<GeminiPart> = emptyList())

@Serializable
data class ThinkingConfig(val thinkingBudget: Int)

@Serializable
data class GenerationConfig(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val thinkingConfig: ThinkingConfig? = null
)

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val generationConfig: GenerationConfig? = null
)

@Serializable
data class GeminiCandidate(val content: GeminiContent? = null, val finishReason: String? = null)

@Serializable
data class GeminiPromptFeedback(val blockReason: String? = null)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val promptFeedback: GeminiPromptFeedback? = null
)

@Serializable
data class GeminiErrorDetail(val code: Int? = null, val message: String? = null, val status: String? = null)

@Serializable
data class GeminiErrorBody(val error: GeminiErrorDetail? = null)

@Serializable
data class GeminiModelInfo(
    val name: String = "",
    val supportedGenerationMethods: List<String> = emptyList()
)

@Serializable
data class GeminiModelList(
    val models: List<GeminiModelInfo> = emptyList(),
    val nextPageToken: String? = null
)

// ---------- Public types ----------

sealed class ModelListResult {
    data class Success(val models: List<String>) : ModelListResult()
    data class Failure(val message: String) : ModelListResult()
}

/** One conversation turn. [role] is "user" (the contact) or "model" (the user's own replies). */
data class GeminiTurn(val role: String, val text: String)

sealed class GeminiResult {
    data class Success(val text: String) : GeminiResult()
    data class Failure(
        val message: String,
        val retryable: Boolean,
        val httpCode: Int? = null,
        val retryAfterSec: Long? = null
    ) : GeminiResult()
}

/**
 * Minimal Gemini REST client with timeouts, retries with backoff, rate-limit spacing
 * and friendly error messages.
 */
class GeminiApiService {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val rateMutex = Mutex()
    private var lastCallAt = 0L

    suspend fun generate(
        apiKey: String,
        model: String,
        systemPrompt: String,
        turns: List<GeminiTurn>,
        temperature: Double = 0.9,
        maxOutputTokens: Int = 300
    ): GeminiResult {
        val key = apiKey.trim()
        if (key.isEmpty()) return GeminiResult.Failure("Gemini API key is missing", retryable = false)
        val cleanModel = model.trim().removePrefix("models/").ifBlank { "gemini-2.5-flash" }
        val contents = mergeTurns(turns)
        if (contents.isEmpty()) return GeminiResult.Failure("Nothing to send to Gemini", retryable = false)

        val request = GeminiRequest(
            contents = contents,
            systemInstruction = if (systemPrompt.isBlank()) null
            else GeminiContent(parts = listOf(GeminiPart(systemPrompt))),
            generationConfig = GenerationConfig(
                temperature = temperature,
                maxOutputTokens = maxOutputTokens,
                // 2.5 Flash can "think" first, which eats the token budget. Short chat replies don't need it.
                thinkingConfig = if (cleanModel.contains("2.5-flash")) ThinkingConfig(0) else null
            )
        )
        val bodyJson = try {
            json.encodeToString(GeminiRequest.serializer(), request)
        } catch (e: SerializationException) {
            return GeminiResult.Failure("Could not build request: ${e.message}", retryable = false)
        }

        var last: GeminiResult.Failure = GeminiResult.Failure("Unknown error", retryable = false)
        for (attempt in 1..MAX_ATTEMPTS) {
            awaitRateSlot()
            val result = callOnce(key, cleanModel, bodyJson)
            if (result is GeminiResult.Success) return result
            last = result as GeminiResult.Failure
            if (!last.retryable || attempt == MAX_ATTEMPTS) break
            delay(backoffMillis(attempt, last))
        }
        return last
    }

    /** Quick check used by the "Test key" button. */
    suspend fun ping(apiKey: String, model: String): GeminiResult =
        generate(
            apiKey = apiKey,
            model = model,
            systemPrompt = "Reply with the single word OK.",
            turns = listOf(GeminiTurn("user", "ping")),
            temperature = 0.0,
            // Newer Gemini models "think" first and that counts against this limit, so keep it generous.
            maxOutputTokens = 1024
        )

    /**
     * Asks Google which models this API key can use right now, so the app never depends on a
     * hard-coded model name that Google may retire.
     */
    suspend fun listModels(apiKey: String): ModelListResult {
        val key = apiKey.trim()
        if (key.isEmpty()) return ModelListResult.Failure("Gemini API key is missing")
        val found = LinkedHashSet<String>()
        var pageToken: String? = null
        try {
            for (page in 1..5) {
                val url = StringBuilder("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100")
                if (!pageToken.isNullOrEmpty()) {
                    url.append("&pageToken=").append(java.net.URLEncoder.encode(pageToken, "UTF-8"))
                }
                val request = Request.Builder()
                    .url(url.toString())
                    .header("x-goog-api-key", key)
                    .get()
                    .build()
                val parsed = client.newCall(request).await().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return ModelListResult.Failure(parseError(response.code, raw, null).message)
                    }
                    json.decodeFromString(GeminiModelList.serializer(), raw)
                }
                for (m in parsed.models) {
                    val id = m.name.removePrefix("models/")
                    if (isChatModel(id, m)) found.add(id)
                }
                pageToken = parsed.nextPageToken
                if (pageToken.isNullOrEmpty()) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return ModelListResult.Failure("Network problem: ${e.message ?: "no connection"}")
        } catch (e: Exception) {
            return ModelListResult.Failure("Could not read the model list: ${e.message}")
        }
        return ModelListResult.Success(found.sortedDescending())
    }

    private fun isChatModel(id: String, info: GeminiModelInfo): Boolean {
        if (!id.startsWith("gemini")) return false
        if ("generateContent" !in info.supportedGenerationMethods) return false
        val excluded = listOf("image", "tts", "live", "audio", "embedding", "robotics", "computer-use", "native", "aqa")
        return excluded.none { id.contains(it) }
    }

    private suspend fun callOnce(apiKey: String, model: String, bodyJson: String): GeminiResult {
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            client.newCall(request).await().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.isSuccessful) parseSuccess(raw)
                else parseError(response.code, raw, response.header("Retry-After"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            GeminiResult.Failure("Network problem: ${e.message ?: "no connection"}", retryable = true)
        } catch (e: Exception) {
            GeminiResult.Failure("Unexpected error: ${e.message}", retryable = false)
        }
    }

    private fun parseSuccess(raw: String): GeminiResult {
        val parsed = try {
            json.decodeFromString(GeminiResponse.serializer(), raw)
        } catch (e: SerializationException) {
            return GeminiResult.Failure("Could not read Gemini response", retryable = true)
        }
        parsed.promptFeedback?.blockReason?.let {
            return GeminiResult.Failure("Gemini blocked the request ($it)", retryable = false)
        }
        val candidate = parsed.candidates.firstOrNull()
        val text = candidate?.content?.parts?.joinToString("") { it.text }?.trim().orEmpty()
        if (text.isEmpty()) {
            val reason = candidate?.finishReason ?: "no candidates"
            return GeminiResult.Failure("Gemini returned an empty answer ($reason)", retryable = true)
        }
        return GeminiResult.Success(text)
    }

    private fun parseError(code: Int, raw: String, retryAfter: String?): GeminiResult.Failure {
        val apiMessage = try {
            json.decodeFromString(GeminiErrorBody.serializer(), raw).error?.message
        } catch (e: Exception) {
            null
        }
        val retryAfterSec = retryAfter?.trim()?.toLongOrNull()
        val friendly = when (code) {
            400 -> if (apiMessage?.contains("API key", ignoreCase = true) == true) "Gemini API key is not valid"
            else "Gemini rejected the request: ${apiMessage ?: "bad request"}"
            401, 403 -> "Gemini API key is not allowed (HTTP $code)"
            404 -> "Model not found or retired. Open Settings and tap \"Find available models\". Google says: ${apiMessage ?: "no details"}"
            429 -> "Gemini rate limit reached (HTTP 429)"
            in 500..599 -> "Gemini server problem (HTTP $code)"
            else -> "Gemini error HTTP $code: ${apiMessage ?: "unknown"}"
        }
        val retryable = code == 429 || code in 500..599
        return GeminiResult.Failure(
            message = friendly,
            retryable = retryable,
            httpCode = code,
            retryAfterSec = retryAfterSec
        )
    }

    private fun backoffMillis(attempt: Int, failure: GeminiResult.Failure): Long {
        val hint = failure.retryAfterSec
        if (hint != null && hint > 0) return (hint * 1000L).coerceAtMost(20_000L)
        return (2_000L * attempt * attempt).coerceAtMost(15_000L)
    }

    /** Keeps the minimum spacing between API calls so bursts don't trip rate limits. */
    private suspend fun awaitRateSlot() {
        rateMutex.withLock {
            val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastCallAt)
            if (wait > 0) delay(wait)
            lastCallAt = System.currentTimeMillis()
        }
    }

    /** Gemini expects alternating roles; merge neighbours with the same role. */
    private fun mergeTurns(turns: List<GeminiTurn>): List<GeminiContent> {
        val merged = ArrayList<GeminiTurn>()
        for (turn in turns) {
            val text = turn.text.trim()
            if (text.isEmpty()) continue
            val role = if (turn.role == "model") "model" else "user"
            val lastTurn = merged.lastOrNull()
            if (lastTurn != null && lastTurn.role == role) {
                merged[merged.size - 1] = GeminiTurn(role, lastTurn.text + "\n" + text)
            } else {
                merged.add(GeminiTurn(role, text))
            }
        }
        // The conversation must start with a user turn.
        while (merged.isNotEmpty() && merged.first().role == "model") merged.removeAt(0)
        return merged.map { GeminiContent(role = it.role, parts = listOf(GeminiPart(it.text))) }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { response.close() }
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val MIN_GAP_MS = 1_200L
    }
}
```

=== FILE: app/src/main/java/com/umar/aireply/data/SettingsManager.kt ===
```kotlin
package com.umar.aireply.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything configurable from the dashboard. Nothing is hardcoded in the source. */
data class AppSettings(
    val masterEnabled: Boolean = false,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    /** Human-like reply delay range, in seconds. */
    val minDelaySec: Int = 15,
    val maxDelaySec: Int = 45,
    /** Wait this long after the last message before answering (batches rapid messages). */
    val debounceSec: Int = 9,
    /** Safety cap on automatic replies per contact per hour. */
    val maxRepliesPerHour: Int = 20,
    /** Optional line that tells the contact an AI assistant is replying. */
    val disclosureEnabled: Boolean = false,
    val disclosureText: String = "(This is my AI assistant replying. I'll talk to you myself soon.)",
    /** The disclosure line is added to the first reply and then to every Nth reply. */
    val disclosureEveryN: Int = 10,
    /** The user's own name, used inside prompts. */
    val userName: String = "محمد عمر",
    /** Text shown in the "Edition" badge. */
    val brandingName: String = "Muhammad Umar"
) {
    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash"
    }
}

class SettingsManager(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context.applicationContext)
    private val lock = Any()
    private val _settings = MutableStateFlow(load())

    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val current: AppSettings get() = _settings.value

    fun update(transform: (AppSettings) -> AppSettings) {
        synchronized(lock) {
            val updated = sanitize(transform(_settings.value))
            _settings.value = updated
            save(updated)
        }
    }

    private fun sanitize(s: AppSettings): AppSettings {
        val minDelay = s.minDelaySec.coerceIn(0, 600)
        val maxDelay = s.maxDelaySec.coerceIn(minDelay, 900)
        return s.copy(
            apiKey = s.apiKey.trim(),
            model = s.model.trim().removePrefix("models/").ifBlank { AppSettings.DEFAULT_MODEL },
            minDelaySec = minDelay,
            maxDelaySec = maxDelay,
            debounceSec = s.debounceSec.coerceIn(3, 60),
            maxRepliesPerHour = s.maxRepliesPerHour.coerceIn(1, 120),
            disclosureEveryN = s.disclosureEveryN.coerceIn(1, 100),
            userName = s.userName.trim().ifBlank { "محمد عمر" },
            brandingName = s.brandingName.trim().ifBlank { "Muhammad Umar" }
        )
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return sanitize(
            AppSettings(
                masterEnabled = prefs.getBoolean(K_MASTER, d.masterEnabled),
                apiKey = prefs.getString(K_API_KEY, d.apiKey) ?: d.apiKey,
                model = migrateModel(prefs.getString(K_MODEL, d.model) ?: d.model),
                minDelaySec = prefs.getInt(K_MIN_DELAY, d.minDelaySec),
                maxDelaySec = prefs.getInt(K_MAX_DELAY, d.maxDelaySec),
                debounceSec = prefs.getInt(K_DEBOUNCE, d.debounceSec),
                maxRepliesPerHour = prefs.getInt(K_MAX_PER_HOUR, d.maxRepliesPerHour),
                disclosureEnabled = prefs.getBoolean(K_DISCLOSURE_ON, d.disclosureEnabled),
                disclosureText = prefs.getString(K_DISCLOSURE_TEXT, d.disclosureText) ?: d.disclosureText,
                disclosureEveryN = prefs.getInt(K_DISCLOSURE_N, d.disclosureEveryN),
                userName = prefs.getString(K_USER_NAME, d.userName) ?: d.userName,
                brandingName = prefs.getString(K_BRANDING, d.brandingName) ?: d.brandingName
            )
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putBoolean(K_MASTER, s.masterEnabled)
            .putString(K_API_KEY, s.apiKey)
            .putString(K_MODEL, s.model)
            .putInt(K_MIN_DELAY, s.minDelaySec)
            .putInt(K_MAX_DELAY, s.maxDelaySec)
            .putInt(K_DEBOUNCE, s.debounceSec)
            .putInt(K_MAX_PER_HOUR, s.maxRepliesPerHour)
            .putBoolean(K_DISCLOSURE_ON, s.disclosureEnabled)
            .putString(K_DISCLOSURE_TEXT, s.disclosureText)
            .putInt(K_DISCLOSURE_N, s.disclosureEveryN)
            .putString(K_USER_NAME, s.userName)
            .putString(K_BRANDING, s.brandingName)
            .apply()
    }

    private companion object {
        /** Models Google has already switched off; saved settings that still use them are upgraded. */
        val RETIRED_MODELS = setOf("gemini-2.5-flash", "gemini-2.5-flash-lite")

        fun migrateModel(model: String): String =
            if (model in RETIRED_MODELS || model.startsWith("gemini-2.0")) AppSettings.DEFAULT_MODEL else model

        const val K_MASTER = "master_enabled"
        const val K_API_KEY = "api_key"
        const val K_MODEL = "model"
        const val K_MIN_DELAY = "min_delay"
        const val K_MAX_DELAY = "max_delay"
        const val K_DEBOUNCE = "debounce"
        const val K_MAX_PER_HOUR = "max_per_hour"
        const val K_DISCLOSURE_ON = "disclosure_on"
        const val K_DISCLOSURE_TEXT = "disclosure_text"
        const val K_DISCLOSURE_N = "disclosure_n"
        const val K_USER_NAME = "user_name"
        const val K_BRANDING = "branding"

        /** Encrypted storage (API key is sensitive). Falls back to normal prefs if the Keystore misbehaves. */
        fun createPrefs(context: Context): SharedPreferences = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "ai_reply_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences("ai_reply_prefs_fallback", Context.MODE_PRIVATE)
        }
    }
}
```

=== FILE: app/src/main/java/com/umar/aireply/service/ReplyEngine.kt ===
```kotlin
package com.umar.aireply.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import com.umar.aireply.AiReplyApp
import com.umar.aireply.data.ActivityLog
import com.umar.aireply.data.AppSettings
import com.umar.aireply.data.NameMatcher
import com.umar.aireply.data.db.ApprovedContactEntity
import com.umar.aireply.data.db.ChatMessageEntity
import com.umar.aireply.net.GeminiResult
import com.umar.aireply.net.GeminiTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Where the Direct Reply must be sent: the newest notification's reply action. */
data class ReplyTarget(
    val action: Notification.Action,
    val notificationKey: String,
    val packageName: String
)

/**
 * The brain of the app. For every approved contact it:
 *  1. collects rapid consecutive messages (debounce),
 *  2. asks Gemini for a reply in the user's style,
 *  3. waits a human-like delay,
 *  4. fires the WhatsApp Direct Reply action,
 *  5. stores everything in the 15-message memory.
 */
class ReplyEngine private constructor(private val app: AiReplyApp) {

    private class ContactState {
        val pending = ArrayList<IncomingText>()
        var target: ReplyTarget? = null
        var job: Job? = null

        /** True while a reply is being sent; such a cycle must never be cancelled half-way. */
        var sending = false
        var lastSeenTime = 0L
        var lastIncomingText = ""
        var lastIncomingAt = 0L
        var repliesSent = 0
        val sentTimes = ArrayList<Long>()
        val recentOutgoing = ArrayList<Pair<String, Long>>()
    }

    private val db get() = app.database
    private val states = ConcurrentHashMap<Long, ContactState>()
    private val ignoreLogTimes = ConcurrentHashMap<String, Long>()
    private val activeCycles = AtomicInteger(0)

    private val wakeLock: PowerManager.WakeLock by lazy {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiReply:reply").apply { setReferenceCounted(false) }
    }

    init {
        // Turning the master switch off immediately cancels everything that is waiting.
        app.appScope.launch {
            app.settings.settings.map { it.masterEnabled }.distinctUntilChanged().collect { enabled ->
                if (!enabled) cancelAll("Auto-reply turned off - pending replies cancelled")
            }
        }
    }

    // ------------------------------------------------------------------ incoming notifications

    suspend fun onNotification(p: ParsedNotification) {
        val settings = app.settings.current
        if (!settings.masterEnabled) return
        if (p.isSummary || p.isGroup) return          // groups and summaries are always ignored
        if (p.title.isBlank()) return

        // Unsaved numbers show up in WhatsApp as a bare phone number. They are never answered.
        if (NameMatcher.looksLikePhoneNumber(p.title)) {
            logIgnoredOnce("unsaved:${p.title}", "Ignored: unsaved number ${p.title}")
            return
        }

        val contact = db.contactDao().findByNormalizedName(NameMatcher.normalize(p.title))
        if (contact == null) {
            if (p.replyAction != null && p.messages.isNotEmpty()) {
                logIgnoredOnce("unknown:${p.title}", "Ignored: \"${p.title}\" is not an approved contact")
            }
            return
        }
        if (!contact.enabled) {
            logIgnoredOnce("off:${contact.id}", "Ignored: ${contact.addressName} is switched off")
            return
        }
        if (!contact.isReady) {
            logIgnoredOnce("noprofile:${contact.id}", "Ignored: ${contact.addressName} has no style profile yet")
            return
        }

        val action = p.replyAction
        if (action == null) {
            logIgnoredOnce("noaction:${contact.id}", "No reply button found in the notification from ${contact.addressName}")
            return
        }

        val texts = p.messages.filter { !NotificationParser.isIgnorableText(it.text) }
        if (texts.isEmpty()) return

        enqueue(contact, texts, ReplyTarget(action, p.key, p.packageName))
    }

    /** The user opened or swiped away the chat notification: they are handling it themselves. */
    fun onNotificationRemoved(key: String, reason: Int) {
        val userAction = reason == NotificationListenerService.REASON_CLICK ||
            reason == NotificationListenerService.REASON_CANCEL ||
            reason == NotificationListenerService.REASON_CANCEL_ALL
        if (!userAction) return
        for (state in states.values) {
            synchronized(state) {
                if (!state.sending && state.pending.isNotEmpty() && state.target?.notificationKey == key) {
                    state.job?.cancel()
                    state.pending.clear()
                    ActivityLog.info("Skipped: you opened or dismissed that chat yourself")
                }
            }
        }
    }

    fun cancelAll(reason: String) {
        var cancelled = false
        for (state in states.values) {
            synchronized(state) {
                if (!state.sending && (state.pending.isNotEmpty() || state.job?.isActive == true)) {
                    state.job?.cancel()
                    state.pending.clear()
                    cancelled = true
                }
            }
        }
        if (cancelled) ActivityLog.warn(reason)
    }

    // ------------------------------------------------------------------ queue + debounce

    private suspend fun stateFor(contactId: Long): ContactState {
        states[contactId]?.let { return it }
        // After an app restart, remember which incoming messages were already handled.
        val latestIncoming = db.chatDao().recent(contactId, 15)
            .filter { it.role == ChatMessageEntity.ROLE_CONTACT }
            .maxOfOrNull { it.timestamp } ?: 0L
        val created = ContactState().also { it.lastSeenTime = latestIncoming }
        return states.putIfAbsent(contactId, created) ?: created
    }

    private suspend fun enqueue(contact: ApprovedContactEntity, texts: List<IncomingText>, target: ReplyTarget) {
        val state = stateFor(contact.id)
        val now = System.currentTimeMillis()
        var accepted = 0

        synchronized(state) {
            val firstSeenFloor = if (state.lastSeenTime == 0L) now - FIRST_SEEN_WINDOW_MS else 0L
            for (t in texts.sortedBy { it.time }) {
                if (t.time <= state.lastSeenTime) continue                 // already handled
                if (t.time < firstSeenFloor) continue                      // old unread message from before we started
                if (isEcho(state, t.text)) {                               // our own reply echoed back
                    state.lastSeenTime = maxOf(state.lastSeenTime, t.time)
                    continue
                }
                if (!t.exact && t.text == state.lastIncomingText && now - state.lastIncomingAt < DUPLICATE_WINDOW_MS) {
                    continue                                               // notification re-posted without changes
                }
                state.pending.add(t)
                state.lastSeenTime = maxOf(state.lastSeenTime, t.time)
                state.lastIncomingText = t.text
                state.lastIncomingAt = now
                accepted++
            }
            if (accepted == 0) return
            state.target = target
            if (!state.sending) {
                state.job?.cancel()
                state.job = app.appScope.launch { runCycle(contact.id, state) }
            }
        }

        val s = app.settings.current
        ActivityLog.info("Message received from ${contact.addressName}")
        ActivityLog.info("Waiting ${s.debounceSec}s for more messages from ${contact.addressName}...")
    }

    // ------------------------------------------------------------------ one reply cycle

    private suspend fun runCycle(contactId: Long, state: ContactState) {
        acquireWakeLock()
        try {
            delay(app.settings.current.debounceSec * 1000L)

            val snapshot: List<IncomingText>
            val hasTarget: Boolean
            synchronized(state) {
                snapshot = state.pending.toList()
                hasTarget = state.target != null
            }
            if (snapshot.isEmpty() || !hasTarget) return

            val contact = db.contactDao().getById(contactId)
            val blocked = gate(contact, app.settings.current, state)
            if (blocked != null || contact == null) {
                ActivityLog.warn(blocked ?: "Skipped: contact no longer exists")
                dropPending(state, snapshot.size)
                return
            }

            // ---- ask Gemini ----
            val settings = app.settings.current
            ActivityLog.info("Thinking about a reply for ${contact.addressName}...")
            val examples = db.styleExampleDao().forContact(contact.id, 30)
            val history = db.chatDao().history(contact.id)
            val turns = ArrayList<GeminiTurn>()
            for (m in history) {
                turns.add(GeminiTurn(if (m.role == ChatMessageEntity.ROLE_ME) "model" else "user", m.text))
            }
            for (m in snapshot) turns.add(GeminiTurn("user", m.text))

            val started = SystemClock.elapsedRealtime()
            val result = app.gemini.generate(
                apiKey = settings.apiKey,
                model = settings.model,
                systemPrompt = PromptBuilder.systemPrompt(settings, contact, examples),
                turns = turns,
                temperature = 0.9,
                maxOutputTokens = 2048
            )
            val reply = when (result) {
                is GeminiResult.Success -> PromptBuilder.cleanReply(result.text)
                is GeminiResult.Failure -> {
                    ActivityLog.error("Gemini error: ${result.message}")
                    withContext(NonCancellable) { saveIncoming(contactId, snapshot) }
                    dropPending(state, snapshot.size)
                    return
                }
            }
            if (reply.isBlank()) {
                ActivityLog.error("Gemini gave an empty reply for ${contact.addressName}")
                withContext(NonCancellable) { saveIncoming(contactId, snapshot) }
                dropPending(state, snapshot.size)
                return
            }

            // ---- human-like delay (time already spent on Gemini counts towards it) ----
            val s2 = app.settings.current
            val wantedMs = Random.nextLong(s2.minDelaySec * 1000L, s2.maxDelaySec * 1000L + 1)
            val waitMs = (wantedMs - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(0L)
            if (waitMs > 0) {
                ActivityLog.info("Waiting ${waitMs / 1000}s before replying to ${contact.addressName}...")
                delay(waitMs)
            }

            // ---- point of no return: a newer message may have cancelled this cycle ----
            val ctx = currentCoroutineContext()
            val mayProceed = synchronized(state) {
                if (!ctx.isActive) false else {
                    state.sending = true
                    true
                }
            }
            if (!mayProceed) return

            withContext(NonCancellable) { sendAndCommit(contactId, state, snapshot, reply) }
        } finally {
            releaseWakeLock()
        }
    }

    private suspend fun sendAndCommit(
        contactId: Long,
        state: ContactState,
        snapshot: List<IncomingText>,
        reply: String
    ) {
        try {
            val contact = db.contactDao().getById(contactId)
            val s = app.settings.current
            val blocked = gate(contact, s, state)
            if (blocked != null || contact == null) {
                ActivityLog.warn(blocked ?: "Skipped: contact no longer exists")
                saveIncoming(contactId, snapshot)
                return
            }
            val target = synchronized(state) { state.target }
            if (target == null) {
                ActivityLog.error("Cannot reply to ${contact.addressName}: no reply button available")
                saveIncoming(contactId, snapshot)
                return
            }

            val withDisclosure = s.disclosureEnabled &&
                s.disclosureText.isNotBlank() &&
                state.repliesSent % s.disclosureEveryN == 0
            val finalText = if (withDisclosure) reply + "\n" + s.disclosureText.trim() else reply

            val delivered = sendReply(target, finalText)
            saveIncoming(contactId, snapshot)
            if (delivered) {
                db.chatDao().insertAndTrim(
                    ChatMessageEntity(contactId = contactId, role = ChatMessageEntity.ROLE_ME, text = reply)
                )
                val now = System.currentTimeMillis()
                synchronized(state) {
                    state.sentTimes.add(now)
                    state.repliesSent++
                    state.recentOutgoing.add(normalizeText(reply) to now)
                    state.recentOutgoing.add(normalizeText(finalText) to now)
                }
                ActivityLog.success("Reply sent to ${contact.addressName}")
            } else {
                ActivityLog.error("Could not send to ${contact.addressName}: the WhatsApp notification is gone")
            }
        } catch (e: Exception) {
            ActivityLog.error("Sending failed: ${e.message}")
        } finally {
            synchronized(state) {
                repeat(minOf(snapshot.size, state.pending.size)) { state.pending.removeAt(0) }
                state.sending = false
                // Messages that arrived while we were sending start a fresh cycle.
                if (state.pending.isNotEmpty()) {
                    state.job = app.appScope.launch { runCycle(contactId, state) }
                }
            }
        }
    }

    /** Fires the notification's Direct Reply action with [text] as the typed answer. */
    private fun sendReply(target: ReplyTarget, text: String): Boolean {
        val inputs = target.action.remoteInputs ?: return false
        if (inputs.isEmpty()) return false
        val intent = Intent()
        val results = Bundle()
        for (input in inputs) results.putCharSequence(input.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        if (Build.VERSION.SDK_INT >= 28) {
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }
        return try {
            target.action.actionIntent.send(app, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Returns a reason when this contact must NOT be answered right now, otherwise null. */
    private fun gate(contact: ApprovedContactEntity?, s: AppSettings, state: ContactState): String? {
        if (contact == null) return "Skipped: contact no longer exists"
        val name = contact.addressName
        if (!s.masterEnabled) return "Skipped: auto-reply is switched off"
        if (!contact.enabled) return "Skipped: $name is switched off"
        if (!contact.isReady) return "Skipped: $name has no style profile yet"
        if (s.apiKey.isBlank()) return "Skipped: Gemini API key is missing"
        val now = System.currentTimeMillis()
        val sentLastHour = synchronized(state) {
            state.sentTimes.removeAll { now - it > HOUR_MS }
            state.sentTimes.size
        }
        if (sentLastHour >= s.maxRepliesPerHour) return "Skipped: hourly reply limit reached for $name"
        return null
    }

    private suspend fun saveIncoming(contactId: Long, texts: List<IncomingText>) {
        for (t in texts) {
            db.chatDao().insertAndTrim(
                ChatMessageEntity(
                    contactId = contactId,
                    role = ChatMessageEntity.ROLE_CONTACT,
                    text = t.text.take(1000),
                    timestamp = t.time
                )
            )
        }
    }

    private fun dropPending(state: ContactState, count: Int) {
        synchronized(state) {
            repeat(minOf(count, state.pending.size)) { state.pending.removeAt(0) }
        }
    }

    /** True when [text] is just WhatsApp echoing a reply we sent a moment ago. */
    private fun isEcho(state: ContactState, text: String): Boolean {
        val n = normalizeText(text).removePrefix("you: ").trim()
        val now = System.currentTimeMillis()
        state.recentOutgoing.removeAll { now - it.second > ECHO_WINDOW_MS }
        return state.recentOutgoing.any { (out, _) ->
            out == n || (n.length >= 8 && out.length >= 8 && (out.contains(n) || n.contains(out)))
        }
    }

    private fun normalizeText(text: String): String =
        text.trim().lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ")

    private fun logIgnoredOnce(key: String, message: String) {
        val now = System.currentTimeMillis()
        val last = ignoreLogTimes[key]
        if (last == null || now - last > IGNORE_LOG_INTERVAL_MS) {
            ignoreLogTimes[key] = now
            ActivityLog.info(message)
        }
    }

    private fun acquireWakeLock() {
        if (activeCycles.getAndIncrement() == 0) {
            try {
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            } catch (e: Exception) {
                ActivityLog.warn("Wake lock unavailable: ${e.message}")
            }
        }
    }

    private fun releaseWakeLock() {
        if (activeCycles.decrementAndGet() <= 0) {
            activeCycles.set(0)
            try {
                if (wakeLock.isHeld) wakeLock.release()
            } catch (e: Exception) {
                // Already released by the timeout.
            }
        }
    }

    companion object {
        private const val HOUR_MS = 60 * 60 * 1000L
        private const val ECHO_WINDOW_MS = 10 * 60 * 1000L
        private const val DUPLICATE_WINDOW_MS = 90 * 1000L
        private const val FIRST_SEEN_WINDOW_MS = 30 * 60 * 1000L
        private const val IGNORE_LOG_INTERVAL_MS = 10 * 60 * 1000L
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        @Volatile
        private var instance: ReplyEngine? = null

        fun get(app: AiReplyApp): ReplyEngine =
            instance ?: synchronized(this) {
                instance ?: ReplyEngine(app).also { instance = it }
            }
    }
}
```

=== FILE: app/src/main/java/com/umar/aireply/data/ChatImport.kt ===
```kotlin
package com.umar.aireply.data

import androidx.room.withTransaction
import com.umar.aireply.data.db.AppDatabase
import com.umar.aireply.data.db.ApprovedContactEntity
import com.umar.aireply.data.db.ChatMessageEntity
import com.umar.aireply.data.db.StyleExampleEntity
import com.umar.aireply.net.GeminiApiService
import com.umar.aireply.net.GeminiResult
import com.umar.aireply.net.GeminiTurn
import java.util.Locale

data class ParsedMessage(val sender: String, val text: String)

data class ParseResult(
    val messages: List<ParsedMessage>,
    /** sender name -> number of messages */
    val senderCounts: Map<String, Int>
)

/** Reads the .txt file produced by WhatsApp's "Export chat (without media)". */
object WhatsAppChatParser {

    private const val SP = """[\s\u202f\u00a0]"""

    /** Matches the date/time prefix of Android ("12/31/23, 10:45 PM - ") and iOS ("[31/12/23, 22:45:10] ") lines. */
    private val linePrefix = Regex(
        """^[\u200e\u200f\u202a-\u202e\ufeff]*\[?\d{1,4}[/.\-]\d{1,2}[/.\-]\d{1,4},?""" + SP +
            """+\d{1,2}[:.]\d{2}(?::\d{2})?""" + SP + """*(?:[AaPp]\.?[Mm]\.?)?\]?""" + SP + """*-?""" + SP + "*"
    )

    private val invisible = Regex("""[\u200e\u200f\u202a-\u202e\ufeff]""")

    private val noiseExact = setOf(
        "<media omitted>",
        "null",
        "this message was deleted",
        "you deleted this message",
        "missing video note",
        "waiting for this message. this may take a while."
    )

    fun parse(raw: String): ParseResult {
        val out = ArrayList<ParsedMessage>()
        var sender: String? = null
        val buffer = StringBuilder()

        fun flush() {
            val s = sender
            if (s != null) {
                val text = invisible.replace(buffer.toString(), "").trim()
                if (text.isNotEmpty() && !isNoise(text)) out.add(ParsedMessage(s, text))
            }
            sender = null
            buffer.setLength(0)
        }

        for (line in raw.lineSequence()) {
            val m = linePrefix.find(line)
            if (m != null) {
                flush()
                val rest = line.substring(m.range.last + 1)
                val idx = rest.indexOf(": ")
                if (idx in 1..50) {
                    val name = invisible.replace(rest.substring(0, idx), "").trim()
                    if (name.isNotEmpty()) {
                        sender = name
                        buffer.append(rest.substring(idx + 2))
                    }
                }
                // Lines without "Name: " are system notices (encryption banner, "X joined", ...) and are skipped.
            } else if (sender != null) {
                buffer.append('\n').append(line)
            }
        }
        flush()

        return ParseResult(out, out.groupingBy { it.sender }.eachCount())
    }

    private fun isNoise(text: String): Boolean {
        val l = text.trim().lowercase(Locale.ROOT)
        if (l in noiseExact) return true
        if (l.length < 60 && (l.endsWith(" omitted") || l.startsWith("<attached:") || l.contains("(file attached)"))) {
            return true
        }
        return false
    }
}

/** Everything extracted from an imported chat that is needed to teach the AI the user's style. */
data class ImportData(
    /** (incoming message, the user's real reply) */
    val pairs: List<Pair<String, String>>,
    /** Recent conversation as "ME:" / "THEM:" lines for the style analysis. */
    val transcript: String,
    /** Last messages, used to seed the live memory. */
    val seed: List<Pair<Boolean, String>>,
    val myCount: Int,
    val theirCount: Int,
    val stats: String
)

object ChatAnalyzer {

    private const val MAX_PAIRS_POOL = 200
    private const val MAX_EXAMPLES = 30
    private const val MAX_TRANSCRIPT_CHARS = 12_000

    private data class Turn(val fromMe: Boolean, val text: String)

    fun build(messages: List<ParsedMessage>, mySender: String): ImportData {
        val mine = messages.filter { it.sender == mySender }
        val theirs = messages.filter { it.sender != mySender }

        // Merge consecutive messages of the same person into one turn.
        val turns = ArrayList<Turn>()
        for (msg in messages) {
            val fromMe = msg.sender == mySender
            val last = turns.lastOrNull()
            if (last != null && last.fromMe == fromMe) {
                turns[turns.size - 1] = Turn(fromMe, last.text + "\n" + msg.text)
            } else {
                turns.add(Turn(fromMe, msg.text))
            }
        }

        // "their message -> my reply" pairs.
        val allPairs = ArrayList<Pair<String, String>>()
        for (i in 1 until turns.size) {
            val prev = turns[i - 1]
            val cur = turns[i]
            if (cur.fromMe && !prev.fromMe && cur.text.length >= 2) {
                allPairs.add(prev.text.take(400) to cur.text.take(300))
            }
        }
        val pool = allPairs.takeLast(MAX_PAIRS_POOL)
        val pairs = if (pool.size <= MAX_EXAMPLES) pool else {
            (0 until MAX_EXAMPLES)
                .map { pool[it * (pool.size - 1) / (MAX_EXAMPLES - 1)] }
                .distinct()
        }

        // Transcript from the newest turns backwards, within the size cap.
        val lines = ArrayList<String>()
        var size = 0
        for (turn in turns.asReversed()) {
            val line = (if (turn.fromMe) "ME: " else "THEM: ") + turn.text.take(300).replace('\n', ' ')
            if (size + line.length > MAX_TRANSCRIPT_CHARS) break
            lines.add(line)
            size += line.length + 1
        }
        val transcript = lines.asReversed().joinToString("\n")

        val seed = messages.takeLast(15).map { (it.sender == mySender) to it.text.take(500) }

        return ImportData(
            pairs = pairs,
            transcript = transcript,
            seed = seed,
            myCount = mine.size,
            theirCount = theirs.size,
            stats = summarizeStyle(mine.map { it.text })
        )
    }

    private fun summarizeStyle(myTexts: List<String>): String {
        if (myTexts.isEmpty()) return "no data"
        val avgWords = myTexts.map { it.trim().split(Regex("""\s+""")).size }.average()
        var emoji = 0
        var urdu = 0
        var latin = 0
        for (t in myTexts) {
            var i = 0
            while (i < t.length) {
                val cp = t.codePointAt(i)
                i += Character.charCount(cp)
                when {
                    cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF -> emoji++
                    cp in 0x0600..0x06FF -> urdu++
                    cp in 65..90 || cp in 97..122 -> latin++
                }
            }
        }
        val letters = urdu + latin
        val script = when {
            letters == 0 -> "no letters"
            urdu * 4 > letters * 3 -> "mostly Urdu script"
            latin * 4 > letters * 3 -> "mostly Latin letters (Roman Urdu and/or English)"
            else -> "a mix of Urdu script and Latin letters"
        }
        val emojiPerMessage = emoji.toDouble() / myTexts.size
        return String.format(
            Locale.US,
            "%d messages from ME; average %.1f words per message; %.2f emojis per message; writes in %s.",
            myTexts.size, avgWords, emojiPerMessage, script
        )
    }
}

sealed class ImportOutcome {
    data class Success(val examples: Int, val messages: Int) : ImportOutcome()
    data class Failure(val message: String) : ImportOutcome()
}

/** Imports an exported WhatsApp chat for one approved contact and builds the style profile with Gemini. */
class ChatImportRepository(
    private val db: AppDatabase,
    private val gemini: GeminiApiService,
    private val settings: SettingsManager
) {

    fun scan(rawText: String): ParseResult = WhatsAppChatParser.parse(rawText)

    /**
     * Best guess of which sender is the user: the other person is the one whose name matches the
     * saved contact name. Returns null when it cannot tell (the UI then asks the user to choose).
     */
    fun guessMySender(senderCounts: Map<String, Int>, contactSavedName: String): String? {
        val senders = senderCounts.keys.toList()
        if (senders.size != 2) return null
        val target = NameMatcher.normalize(contactSavedName)
        val matches = senders.filter {
            val n = NameMatcher.normalize(it)
            n == target || n.contains(target) || target.contains(n)
        }
        return if (matches.size == 1) senders.first { it != matches.first() } else null
    }

    suspend fun importAndBuildProfile(
        contact: ApprovedContactEntity,
        rawText: String,
        mySender: String
    ): ImportOutcome {
        if (rawText.length > MAX_CHARS) {
            return ImportOutcome.Failure("Chat file is too large. Export a shorter chat or paste the last part only")
        }
        val parsed = WhatsAppChatParser.parse(rawText)
        if (parsed.messages.isEmpty()) {
            return ImportOutcome.Failure("No WhatsApp messages found. Use 'Export chat > Without media' and paste the text")
        }
        val data = ChatAnalyzer.build(parsed.messages, mySender)
        if (data.myCount < MIN_MESSAGES_EACH || data.theirCount < MIN_MESSAGES_EACH) {
            return ImportOutcome.Failure(
                "Need at least $MIN_MESSAGES_EACH messages from each side (found ${data.myCount} from you, ${data.theirCount} from them)"
            )
        }

        val s = settings.current
        if (s.apiKey.isBlank()) return ImportOutcome.Failure("Add your Gemini API key first")

        ActivityLog.info("Analysing chat style for \"${contact.savedName}\"...")
        val result = gemini.generate(
            apiKey = s.apiKey,
            model = s.model,
            systemPrompt = ANALYSIS_SYSTEM,
            turns = listOf(GeminiTurn("user", buildAnalysisPrompt(data))),
            temperature = 0.3,
            maxOutputTokens = 4096
        )
        val profile = when (result) {
            is GeminiResult.Success -> result.text.trim()
            is GeminiResult.Failure -> {
                ActivityLog.error("Style analysis failed: ${result.message}")
                return ImportOutcome.Failure(result.message)
            }
        }

        val examples = data.pairs.map {
            StyleExampleEntity(contactId = contact.id, incomingText = it.first, myReply = it.second)
        }
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.styleExampleDao().replaceAll(contact.id, examples)
            db.chatDao().clearForContact(contact.id)
            data.seed.forEachIndexed { index, (fromMe, text) ->
                db.chatDao().insert(
                    ChatMessageEntity(
                        contactId = contact.id,
                        role = if (fromMe) ChatMessageEntity.ROLE_ME else ChatMessageEntity.ROLE_CONTACT,
                        text = text,
                        timestamp = now - (data.seed.size - index) * 1_000L
                    )
                )
            }
            db.contactDao().setStyleProfile(contact.id, profile, now, parsed.messages.size)
        }
        ActivityLog.success("Style profile saved for \"${contact.savedName}\" (${examples.size} examples)")
        return ImportOutcome.Success(examples.size, parsed.messages.size)
    }

    private fun buildAnalysisPrompt(data: ImportData): String = buildString {
        appendLine("STATS ABOUT ME: ${data.stats}")
        appendLine()
        appendLine("RECENT CONVERSATION (oldest first). ME = the person whose style I want to learn, THEM = the other person:")
        appendLine(data.transcript)
    }

    private companion object {
        const val MAX_CHARS = 3_000_000
        const val MIN_MESSAGES_EACH = 5

        val ANALYSIS_SYSTEM = """
            You are a writing-style analyst. You receive part of a private WhatsApp conversation between "ME" and "THEM".
            Describe how ME writes so that another AI can imitate ME's way of texting THEM.
            Cover, as short bullet points:
            - Language and script (Urdu script, Roman Urdu, English, or the mix) and typical spelling habits.
            - Tone and mood toward THEM (warm, teasing, formal, caring, short-tempered, etc.).
            - Typical message length and how often ME splits one thought into several short messages.
            - Emoji and punctuation habits.
            - How ME greets, how ME ends a chat, and how ME reacts when THEM is upset or complains.
            - Up to 8 short, real phrases or words ME often uses, copied exactly in their original script.
            Rules: write the guide in English, at most 250 words, bullet points only.
            Do not include phone numbers, addresses, money amounts, passwords or other private details.
            Do not invent anything that is not visible in the conversation. Output only the guide.
        """.trimIndent()
    }
}
```

=== FILE: app/src/main/java/com/umar/aireply/ui/MainViewModel.kt ===
```kotlin
package com.umar.aireply.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.umar.aireply.AiReplyApp
import com.umar.aireply.data.ActivityLog
import com.umar.aireply.data.AddResult
import com.umar.aireply.data.AppSettings
import com.umar.aireply.data.ContactRepository
import com.umar.aireply.data.ImportOutcome
import com.umar.aireply.data.LogEntry
import com.umar.aireply.data.PhoneContact
import com.umar.aireply.data.db.ApprovedContactEntity
import com.umar.aireply.net.GeminiResult
import com.umar.aireply.net.ModelListResult
import com.umar.aireply.service.ServiceController
import com.umar.aireply.service.ServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PermissionState(
    val notificationAccess: Boolean = false,
    val batteryExempt: Boolean = false,
    val contacts: Boolean = false,
    val postNotifications: Boolean = true
)

/** State of the "import chat" screen for one contact. */
data class ImportUiState(
    val contactId: Long,
    val contactName: String,
    val rawText: String = "",
    val scanned: Boolean = false,
    val senderCounts: Map<String, Int> = emptyMap(),
    val mySender: String? = null,
    val busy: Boolean = false,
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as AiReplyApp
    private val db get() = app.database

    val settings: StateFlow<AppSettings> = app.settings.settings

    val contacts: StateFlow<List<ApprovedContactEntity>> =
        db.contactDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activityLog: StateFlow<List<LogEntry>> = ActivityLog.entries
    val listenerConnected: StateFlow<Boolean> = ServiceState.listenerConnected

    private val _permissions = MutableStateFlow(readPermissions())
    val permissions: StateFlow<PermissionState> = _permissions.asStateFlow()

    private val _phoneContacts = MutableStateFlow<List<PhoneContact>>(emptyList())
    val phoneContacts: StateFlow<List<PhoneContact>> = _phoneContacts.asStateFlow()

    private val _loadingContacts = MutableStateFlow(false)
    val loadingContacts: StateFlow<Boolean> = _loadingContacts.asStateFlow()

    private val _testingKey = MutableStateFlow(false)
    val testingKey: StateFlow<Boolean> = _testingKey.asStateFlow()

    private val _models = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _models.asStateFlow()

    private val _loadingModels = MutableStateFlow(false)
    val loadingModels: StateFlow<Boolean> = _loadingModels.asStateFlow()

    private val _import = MutableStateFlow<ImportUiState?>(null)
    val importState: StateFlow<ImportUiState?> = _import.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private fun toast(message: String) {
        _messages.tryEmit(message)
    }

    // ------------------------------------------------------------------ permissions

    private fun readPermissions(): PermissionState {
        val ctx = getApplication<Application>()
        return PermissionState(
            notificationAccess = ServiceController.isNotificationAccessGranted(ctx),
            batteryExempt = ServiceController.isIgnoringBatteryOptimizations(ctx),
            contacts = app.contacts.hasPermission(),
            postNotifications = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    /** Call whenever the app comes back to the foreground. */
    fun refreshPermissions() {
        val state = readPermissions()
        _permissions.value = state
        if (state.contacts) {
            app.contactWatcher.start()
            viewModelScope.launch { app.contacts.refreshApproved() }
        }
    }

    fun reconnectListener() {
        val ctx = getApplication<Application>()
        ServiceController.requestListenerRebind(ctx)
        ServiceController.nudgeListener(ctx)
        ActivityLog.info("Asked Android to reconnect the notification listener")
    }

    // ------------------------------------------------------------------ settings

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        app.settings.update(transform)
    }

    fun setMaster(enabled: Boolean) {
        if (enabled) {
            refreshPermissions()
            val s = app.settings.current
            if (s.apiKey.isBlank()) {
                toast("Add your Gemini API key in Settings first")
                return
            }
            if (!_permissions.value.notificationAccess) {
                toast("Turn on Notification Access first")
                return
            }
            if (contacts.value.none { it.enabled && it.isReady }) {
                toast("Auto-reply is on, but no contact is switched on yet")
            }
        }
        app.settings.update { it.copy(masterEnabled = enabled) }
        ServiceController.onMasterChanged(getApplication(), enabled)
        ActivityLog.info(if (enabled) "Auto-reply turned on" else "Auto-reply turned off")
    }

    fun testApiKey() {
        val s = app.settings.current
        viewModelScope.launch {
            _testingKey.value = true
            val result = app.gemini.ping(s.apiKey, s.model)
            _testingKey.value = false
            toast(
                when (result) {
                    is GeminiResult.Success -> "Gemini key works"
                    is GeminiResult.Failure -> result.message
                }
            )
        }
    }

    /** Asks Google which models this key can use right now. */
    fun loadModels() {
        val key = app.settings.current.apiKey
        viewModelScope.launch {
            _loadingModels.value = true
            val result = app.gemini.listModels(key)
            _loadingModels.value = false
            when (result) {
                is ModelListResult.Success -> {
                    _models.value = result.models
                    toast(
                        if (result.models.isEmpty()) "No usable models found for this key"
                        else "Found ${result.models.size} models. Tap one to use it"
                    )
                }
                is ModelListResult.Failure -> toast(result.message)
            }
        }
    }

    fun clearLog() = ActivityLog.clear()

    // ------------------------------------------------------------------ contacts

    fun loadPhoneContacts() {
        viewModelScope.launch {
            _loadingContacts.value = true
            _phoneContacts.value = app.contacts.loadPhoneContacts()
            _loadingContacts.value = false
        }
    }

    fun addContact(contact: PhoneContact) {
        viewModelScope.launch {
            when (app.contacts.addContact(contact)) {
                AddResult.ADDED -> toast("Added ${contact.name}. Import a chat so the AI learns your style")
                AddResult.ALREADY_ADDED -> toast("${contact.name} is already in the list")
                AddResult.LIMIT_REACHED -> toast("You can approve up to ${ContactRepository.MAX_CONTACTS} contacts")
                AddResult.DUPLICATE_NAME -> toast("Another approved contact has the same saved name")
            }
        }
    }

    fun removeContact(contact: ApprovedContactEntity) {
        viewModelScope.launch {
            db.contactDao().deleteById(contact.id)
            ActivityLog.info("Removed ${contact.savedName} from approved contacts")
        }
    }

    fun setContactEnabled(contact: ApprovedContactEntity, enabled: Boolean) {
        if (enabled && !contact.isReady) {
            toast("Import a chat first so the AI can learn your style")
            return
        }
        viewModelScope.launch { db.contactDao().setEnabled(contact.id, enabled) }
    }

    fun setPersona(contact: ApprovedContactEntity, text: String) {
        viewModelScope.launch { db.contactDao().setPersona(contact.id, text) }
    }

    fun setCallName(contact: ApprovedContactEntity, text: String) {
        viewModelScope.launch { db.contactDao().setCallName(contact.id, text.trim()) }
    }

    fun clearMemory(contact: ApprovedContactEntity) {
        viewModelScope.launch {
            db.chatDao().clearForContact(contact.id)
            ActivityLog.info("Memory cleared for ${contact.savedName}")
            toast("Memory cleared for ${contact.savedName}")
        }
    }

    // ------------------------------------------------------------------ chat import

    fun openImport(contact: ApprovedContactEntity) {
        _import.value = ImportUiState(contactId = contact.id, contactName = contact.savedName)
    }

    fun closeImport() {
        if (_import.value?.busy == true) return
        _import.value = null
    }

    fun setImportText(text: String) {
        _import.update {
            it?.copy(rawText = text, scanned = false, senderCounts = emptyMap(), mySender = null, error = null)
        }
    }

    fun loadImportFile(uri: Uri) {
        if (_import.value == null) return
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    app.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                } catch (e: Exception) {
                    null
                }
            }
            when {
                text == null -> _import.update { it?.copy(error = "Could not read that file") }
                text.length > 3_000_000 -> _import.update {
                    it?.copy(error = "That file is too large. Export a shorter chat")
                }
                else -> setImportText(text)
            }
        }
    }

    fun scanImport() {
        val st = _import.value ?: return
        viewModelScope.launch {
            _import.update { it?.copy(busy = true, error = null) }
            val parsed = withContext(Dispatchers.Default) { app.chatImport.scan(st.rawText) }
            if (parsed.messages.isEmpty()) {
                _import.update {
                    it?.copy(
                        busy = false,
                        error = "No WhatsApp messages found. In WhatsApp use Export chat, Without media, and import that text"
                    )
                }
                return@launch
            }
            val contact = db.contactDao().getById(st.contactId)
            val guess = app.chatImport.guessMySender(parsed.senderCounts, contact?.savedName.orEmpty())
            _import.update {
                it?.copy(busy = false, scanned = true, senderCounts = parsed.senderCounts, mySender = guess)
            }
        }
    }

    fun setMySender(name: String) {
        _import.update { it?.copy(mySender = name, error = null) }
    }

    fun runImport() {
        val st = _import.value ?: return
        val mySender = st.mySender ?: return
        viewModelScope.launch {
            val contact = db.contactDao().getById(st.contactId)
            if (contact == null) {
                _import.value = null
                return@launch
            }
            _import.update { it?.copy(busy = true, error = null) }
            when (val outcome = app.chatImport.importAndBuildProfile(contact, st.rawText, mySender)) {
                is ImportOutcome.Success -> {
                    _import.value = null
                    toast("Style profile ready. Learned from ${outcome.examples} real replies")
                }
                is ImportOutcome.Failure -> _import.update { it?.copy(busy = false, error = outcome.message) }
            }
        }
    }
}
```

=== FILE: app/src/main/java/com/umar/aireply/ui/SettingsScreen.kt ===
```kotlin
package com.umar.aireply.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.umar.aireply.ui.theme.AppColors
import kotlin.math.roundToInt

private val FALLBACK_MODELS = listOf("gemini-3.5-flash", "gemini-3.1-flash-lite")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val testing by vm.testingKey.collectAsStateWithLifecycle()
    val available by vm.availableModels.collectAsStateWithLifecycle()
    val loadingModels by vm.loadingModels.collectAsStateWithLifecycle()
    val modelChoices = if (available.isEmpty()) FALLBACK_MODELS else available.take(12)
    var modelFieldKey by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, color = AppColors.TextPrimary)

        // ---- Gemini ----
        GlassCard {
            SectionTitle("Gemini", "Your key is stored encrypted on this phone only.")
            SettingTextField(
                label = "API key",
                initial = settings.apiKey,
                onCommit = { v -> vm.updateSettings { it.copy(apiKey = v) } },
                password = true
            )
            key(modelFieldKey) {
                SettingTextField(
                    label = "Model",
                    initial = settings.model,
                    onCommit = { v -> vm.updateSettings { it.copy(model = v) } }
                )
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                modelChoices.forEach { name ->
                    FilterChip(
                        selected = settings.model == name,
                        onClick = {
                            vm.updateSettings { it.copy(model = name) }
                            modelFieldKey++
                        },
                        label = { Text(name.removePrefix("gemini-")) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.Mint.copy(alpha = 0.2f),
                            selectedLabelColor = AppColors.Mint
                        )
                    )
                }
            }
            OutlinedButton(
                onClick = { vm.loadModels() },
                enabled = !loadingModels && settings.apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (loadingModels) "Looking..." else "Find available models") }
            Button(
                onClick = { vm.testApiKey() },
                enabled = !testing && settings.apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (testing) "Testing..." else "Test key") }
        }

        // ---- timing ----
        GlassCard {
            SectionTitle("Reply timing", "Replies wait a random time in this range so they feel natural.")
            Text(
                "Delay before replying: ${settings.minDelaySec}s to ${settings.maxDelaySec}s",
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextPrimary
            )
            RangeSlider(
                value = settings.minDelaySec.toFloat()..settings.maxDelaySec.toFloat(),
                onValueChange = { range ->
                    vm.updateSettings {
                        it.copy(
                            minDelaySec = range.start.roundToInt(),
                            maxDelaySec = range.endInclusive.roundToInt()
                        )
                    }
                },
                valueRange = 0f..180f,
                steps = 35,
                colors = SliderDefaults.colors(
                    thumbColor = AppColors.Mint,
                    activeTrackColor = AppColors.Mint,
                    inactiveTrackColor = AppColors.Outline
                )
            )
            LabeledSlider(
                title = "Wait for more messages",
                valueText = "${settings.debounceSec}s",
                value = settings.debounceSec.toFloat(),
                range = 3f..30f,
                steps = 26,
                onChange = { v -> vm.updateSettings { it.copy(debounceSec = v.roundToInt()) } }
            )
            LabeledSlider(
                title = "Max replies per contact per hour",
                valueText = "${settings.maxRepliesPerHour}",
                value = settings.maxRepliesPerHour.toFloat(),
                range = 5f..60f,
                steps = 10,
                onChange = { v -> vm.updateSettings { it.copy(maxRepliesPerHour = v.roundToInt()) } }
            )
        }

        // ---- transparency ----
        GlassCard {
            SwitchRow(
                title = "Tell them it is an AI assistant",
                subtitle = "Adds a short line to the first reply and then to every few replies.",
                checked = settings.disclosureEnabled,
                onChange = { v -> vm.updateSettings { it.copy(disclosureEnabled = v) } }
            )
            if (settings.disclosureEnabled) {
                SettingTextField(
                    label = "Line to add",
                    initial = settings.disclosureText,
                    onCommit = { v -> vm.updateSettings { it.copy(disclosureText = v) } },
                    singleLine = false,
                    minLines = 2
                )
                LabeledSlider(
                    title = "Add it every",
                    valueText = if (settings.disclosureEveryN == 1) "reply" else "${settings.disclosureEveryN} replies",
                    value = settings.disclosureEveryN.toFloat(),
                    range = 1f..30f,
                    steps = 28,
                    onChange = { v -> vm.updateSettings { it.copy(disclosureEveryN = v.roundToInt()) } }
                )
            }
        }

        // ---- identity ----
        GlassCard {
            SectionTitle("Identity", "Used inside prompts and on the badge.")
            SettingTextField(
                label = "Your name",
                initial = settings.userName,
                onCommit = { v -> vm.updateSettings { it.copy(userName = v) } }
            )
            SettingTextField(
                label = "Badge name",
                initial = settings.brandingName,
                onCommit = { v -> vm.updateSettings { it.copy(brandingName = v) } },
                supporting = "Shown as \"<name> Edition\" at the top and bottom."
            )
        }

        BrandFooter(settings.brandingName)
    }
}
```
