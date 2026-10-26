package com.umar.aireply.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A single remembered message in the live conversation with an approved contact. */
@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ApprovedContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["contactId", "timestamp"])]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    /** [ROLE_CONTACT] for incoming messages, [ROLE_ME] for AI replies sent on the user's behalf. */
    val role: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        const val ROLE_CONTACT = "contact"
        const val ROLE_ME = "me"
    }
}
