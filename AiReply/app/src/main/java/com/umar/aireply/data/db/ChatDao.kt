package com.umar.aireply.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ChatDao {

    @Insert
    abstract suspend fun insert(message: ChatMessageEntity): Long

    /** Newest first. Callers reverse the list to get chronological order. */
    @Query("SELECT * FROM chat_messages WHERE contactId = :contactId ORDER BY timestamp DESC, id DESC LIMIT :limit")
    abstract suspend fun recent(contactId: Long, limit: Int): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE contactId = :contactId ORDER BY timestamp ASC, id ASC")
    abstract fun observeForContact(contactId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE contactId = :contactId")
    abstract suspend fun count(contactId: Long): Int

    @Query(
        "DELETE FROM chat_messages WHERE contactId = :contactId AND id NOT IN (" +
            "SELECT id FROM chat_messages WHERE contactId = :contactId " +
            "ORDER BY timestamp DESC, id DESC LIMIT :keep)"
    )
    abstract suspend fun trimToLast(contactId: Long, keep: Int)

    @Query("DELETE FROM chat_messages WHERE contactId = :contactId")
    abstract suspend fun clearForContact(contactId: Long)

    /** Inserts a message and keeps only the newest [keep] messages for that contact. */
    @Transaction
    open suspend fun insertAndTrim(message: ChatMessageEntity, keep: Int = MEMORY_LIMIT) {
        insert(message)
        trimToLast(message.contactId, keep)
    }

    /** Returns the last [limit] messages in chronological order (oldest first). */
    @Transaction
    open suspend fun history(contactId: Long, limit: Int = MEMORY_LIMIT): List<ChatMessageEntity> =
        recent(contactId, limit).reversed()

    companion object {
        const val MEMORY_LIMIT = 15
    }
}
