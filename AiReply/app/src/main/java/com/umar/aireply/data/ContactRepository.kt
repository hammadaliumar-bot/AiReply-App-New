package com.umar.aireply.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.umar.aireply.data.db.ApprovedContactEntity
import com.umar.aireply.data.db.ContactDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

/** A contact as it exists in the phone's address book. */
data class PhoneContact(
    val lookupKey: String,
    val name: String,
    val numbers: List<String>
)

enum class AddResult { ADDED, ALREADY_ADDED, LIMIT_REACHED, DUPLICATE_NAME }

/** Name helpers shared by the UI and the notification service. */
object NameMatcher {

    private val invisible = Regex("""[\u200B-\u200F\u202A-\u202E\u2066-\u2069\uFEFF]""")
    private val spaces = Regex("""\s+""")

    /** Lower-cased, invisible-character-free, single-spaced version of a name. */
    fun normalize(name: String): String {
        var s = Normalizer.normalize(name, Normalizer.Form.NFKC)
        s = invisible.replace(s, "")
        s = s.trim().trimStart('~').trim()
        s = spaces.replace(s, " ")
        return s.lowercase(Locale.ROOT)
    }

    /**
     * True when a WhatsApp notification title is just a phone number.
     * WhatsApp shows the number only for contacts that are NOT saved, so these are always ignored.
     */
    fun looksLikePhoneNumber(title: String): Boolean {
        val cleaned = invisible.replace(title, "").trim()
        if (cleaned.isEmpty()) return false
        val onlyNumberChars = cleaned.all { it.isDigit() || it in "+-() .\u00A0" }
        val digitCount = cleaned.count { it.isDigit() }
        return onlyNumberChars && digitCount >= 5
    }
}

class ContactRepository(
    private val context: Context,
    private val dao: ContactDao
) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** All saved phone contacts that have at least one number (for the contact picker). */
    suspend fun loadPhoneContacts(): List<PhoneContact> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val names = LinkedHashMap<String, String>()
        val numbers = HashMap<String, LinkedHashSet<String>>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { c ->
            val iKey = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
            val iName = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val iNum = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext()) {
                val key = c.getString(iKey) ?: continue
                val name = c.getString(iName)?.trim().orEmpty()
                if (name.isEmpty()) continue
                names.putIfAbsent(key, name)
                val digits = (c.getString(iNum) ?: "").filter { it.isDigit() }
                if (digits.isNotEmpty()) numbers.getOrPut(key) { LinkedHashSet() }.add(digits)
            }
        }
        names.map { (key, name) -> PhoneContact(key, name, numbers[key]?.toList().orEmpty()) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    suspend fun addContact(contact: PhoneContact): AddResult = withContext(Dispatchers.IO) {
        if (dao.getByLookupKey(contact.lookupKey) != null) return@withContext AddResult.ALREADY_ADDED
        if (dao.count() >= MAX_CONTACTS) return@withContext AddResult.LIMIT_REACHED
        val normalized = NameMatcher.normalize(contact.name)
        // Two approved contacts with the same saved name could not be told apart in a notification.
        if (dao.findByNormalizedName(normalized) != null) return@withContext AddResult.DUPLICATE_NAME
        val id = dao.insert(
            ApprovedContactEntity(
                lookupKey = contact.lookupKey,
                savedName = contact.name,
                normalizedName = normalized,
                phoneNumbers = contact.numbers.joinToString("|"),
                enabled = false
            )
        )
        if (id == -1L) AddResult.ALREADY_ADDED else {
            ActivityLog.info("Added \"${contact.name}\" to approved contacts")
            AddResult.ADDED
        }
    }

    /**
     * Re-reads every approved contact from the phone book. Renamed contacts get their new name,
     * contacts deleted from the phone are switched off. Returns how many rows changed.
     */
    suspend fun refreshApproved(): Int = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext 0
        var changed = 0
        for (contact in dao.getAll()) {
            val resolved = try {
                resolve(contact.lookupKey)
            } catch (e: Exception) {
                ActivityLog.warn("Contact refresh failed: ${e.message}")
                continue
            }

            if (resolved == null) {
                if (contact.enabled) {
                    dao.setEnabled(contact.id, false)
                    ActivityLog.warn("\"${contact.savedName}\" is no longer in phone contacts - auto-reply turned off")
                    changed++
                }
                continue
            }

            val newNormalized = NameMatcher.normalize(resolved.name)
            val newNumbers = resolved.numbers.joinToString("|")
            if (resolved.name != contact.savedName || newNumbers != contact.phoneNumbers) {
                val clash = dao.findByNormalizedName(newNormalized)
                if (clash != null && clash.id != contact.id) {
                    dao.setEnabled(contact.id, false)
                    dao.setEnabled(clash.id, false)
                    ActivityLog.warn("Two approved contacts now share the name \"${resolved.name}\" - both turned off")
                }
                dao.updateSavedInfo(contact.id, resolved.name, newNormalized, newNumbers)
                if (resolved.name != contact.savedName) {
                    ActivityLog.info("Contact renamed in phone: \"${contact.savedName}\" -> \"${resolved.name}\"")
                }
                changed++
            }
        }
        changed
    }

    /** Resolves a (possibly stale) lookup key to the contact's current name and numbers. */
    private fun resolve(lookupKey: String): PhoneContact? {
        val resolver = context.contentResolver
        val lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, Uri.encode(lookupKey))
        val contactUri = ContactsContract.Contacts.lookupContact(resolver, lookupUri) ?: return null

        var contactId = -1L
        var name = ""
        var currentKey = lookupKey
        resolver.query(
            contactUri,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.LOOKUP_KEY
            ),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                contactId = c.getLong(0)
                name = c.getString(1)?.trim().orEmpty()
                currentKey = c.getString(2) ?: lookupKey
            }
        }
        if (contactId < 0 || name.isEmpty()) return null

        val numbers = LinkedHashSet<String>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val digits = (c.getString(0) ?: "").filter { it.isDigit() }
                if (digits.isNotEmpty()) numbers.add(digits)
            }
        }
        return PhoneContact(currentKey, name, numbers.toList())
    }

    companion object {
        const val MAX_CONTACTS = 10
    }
}

/** Watches the phone's address book and refreshes approved contacts shortly after any change. */
class ContactWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: ContactRepository
) {
    private var job: Job? = null
    private var registered = false

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            schedule()
        }
    }

    @Synchronized
    fun start() {
        if (registered || !repository.hasPermission()) return
        context.contentResolver.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI,
            true,
            observer
        )
        registered = true
        schedule()
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        context.contentResolver.unregisterContentObserver(observer)
        registered = false
        job?.cancel()
    }

    private fun schedule() {
        job?.cancel()
        job = scope.launch {
            delay(2_000)
            repository.refreshApproved()
        }
    }
}
