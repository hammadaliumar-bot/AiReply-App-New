package com.umar.aireply.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A real "incoming message -> my real reply" pair taken from the imported WhatsApp chat.
 * These are given to Gemini as few-shot examples so replies sound like the user.
 */
@Entity(
    tableName = "style_examples",
    foreignKeys = [
        ForeignKey(
            entity = ApprovedContactEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["contactId"])]
)
data class StyleExampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val contactId: Long,
    val incomingText: String,
    val myReply: String,
    val createdAt: Long = System.currentTimeMillis()
)
