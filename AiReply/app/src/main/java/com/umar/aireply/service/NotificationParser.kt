package com.umar.aireply.service

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification
import java.util.Locale

/**
 * One incoming message. [exact] is true when [time] is the real message time from the
 * notification's message list, false when it is only the notification's post time.
 */
data class IncomingText(val time: Long, val text: String, val exact: Boolean)

data class ParsedNotification(
    val packageName: String,
    val key: String,
    val title: String,
    val messages: List<IncomingText>,
    val replyAction: Notification.Action?,
    val isGroup: Boolean,
    val isSummary: Boolean
)

/** Reads a WhatsApp notification: who wrote, what they wrote, and where the Direct Reply action is. */
object NotificationParser {

    val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    private val invisible = Regex("""[\u200e\u200f\u202a-\u202e\ufeff]""")
    private val newMessagesRegex = Regex("""^\d+ new messages?$""")
    private val mediaWord = Regex(
        """^(photo|image|video|gif|sticker|audio|voice message(?: \(\d+:\d+\))?|document|contact|location|live location|voice call|video call)$"""
    )
    private val systemNoise = listOf(
        "checking for new messages",
        "whatsapp web is currently active",
        "backup in progress"
    )

    fun parse(sbn: StatusBarNotification): ParsedNotification? {
        if (sbn.packageName !in PACKAGES) return null
        val n = sbn.notification ?: return null
        val extras = n.extras ?: return null

        val title = clean(extras.getCharSequence(Notification.EXTRA_TITLE))
        val isSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        val conversationTitle = clean(extras.getCharSequence("android.conversationTitle"))
        val isGroup = extras.getBoolean("android.isGroupConversation", false) || conversationTitle.isNotEmpty()

        return ParsedNotification(
            packageName = sbn.packageName,
            key = sbn.key,
            title = title,
            messages = readMessages(sbn, extras),
            replyAction = findReplyAction(n),
            isGroup = isGroup,
            isSummary = isSummary
        )
    }

    private fun readMessages(sbn: StatusBarNotification, extras: android.os.Bundle): List<IncomingText> {
        val list = ArrayList<IncomingText>()

        @Suppress("DEPRECATION")
        val raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (raw != null && raw.isNotEmpty()) {
            val self = clean(extras.getCharSequence("android.selfDisplayName"))
            val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(raw)
            for (m in messages) {
                val text = clean(m.text)
                if (text.isEmpty()) continue
                if (isSelf(senderOf(m), self)) continue
                list.add(IncomingText(m.timestamp, text, exact = true))
            }
        }

        if (list.isEmpty()) {
            val text = clean(
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )
            if (text.isNotEmpty()) list.add(IncomingText(sbn.postTime, text, exact = false))
        }
        return list
    }

    @Suppress("DEPRECATION")
    private fun senderOf(m: Notification.MessagingStyle.Message): String? {
        val person = if (Build.VERSION.SDK_INT >= 28) m.senderPerson?.name?.toString() else null
        return person ?: m.sender?.toString()
    }

    private fun isSelf(sender: String?, selfDisplayName: String): Boolean {
        if (sender == null) return false
        val s = clean(sender)
        return s.equals("You", ignoreCase = true) ||
            (selfDisplayName.isNotEmpty() && s.equals(selfDisplayName, ignoreCase = true))
    }

    /** Finds the "Reply" action that accepts typed text (RemoteInput). */
    private fun findReplyAction(n: Notification): Notification.Action? {
        val candidates = ArrayList<Notification.Action>()
        n.actions?.let { candidates.addAll(it) }
        try {
            candidates.addAll(Notification.WearableExtender(n).actions)
        } catch (e: Exception) {
            // Some notifications have no wearable extras; that is fine.
        }
        val withInput = candidates.filter { a ->
            a.actionIntent != null && a.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        if (withInput.isEmpty()) return null
        if (Build.VERSION.SDK_INT >= 28) {
            val semantic = withInput.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY }
            if (semantic != null) return semantic
        }
        return withInput.first()
    }

    /** True for text that must never get an automatic reply (media-only, reactions, system lines). */
    fun isIgnorableText(text: String): Boolean {
        val t = clean(text)
        if (t.isEmpty()) return true
        val lower = t.lowercase(Locale.ROOT)
        if (newMessagesRegex.matches(lower)) return true
        if (systemNoise.any { lower.contains(it) }) return true
        if (lower.startsWith("reacted ")) return true
        if (lower == "this message was deleted" || lower == "you deleted this message") return true
        if (lower.length <= 40) {
            // Media alerts look like "Photo" or "<camera emoji> Photo".
            val stripped = lower.dropWhile { !it.isLetter() }.trim()
            if (mediaWord.matches(stripped)) return true
        }
        return false
    }

    private fun clean(cs: CharSequence?): String =
        if (cs == null) "" else invisible.replace(cs.toString(), "").trim()
}
