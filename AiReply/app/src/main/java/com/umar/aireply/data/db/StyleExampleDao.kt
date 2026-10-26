package com.umar.aireply.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class StyleExampleDao {

    @Insert
    abstract suspend fun insertAll(examples: List<StyleExampleEntity>)

    @Query("SELECT * FROM style_examples WHERE contactId = :contactId ORDER BY id ASC LIMIT :limit")
    abstract suspend fun forContact(contactId: Long, limit: Int): List<StyleExampleEntity>

    @Query("SELECT COUNT(*) FROM style_examples WHERE contactId = :contactId")
    abstract suspend fun count(contactId: Long): Int

    @Query("DELETE FROM style_examples WHERE contactId = :contactId")
    abstract suspend fun clearForContact(contactId: Long)

    /** Replaces all examples of a contact in one atomic step (used when the chat is re-imported). */
    @Transaction
    open suspend fun replaceAll(contactId: Long, examples: List<StyleExampleEntity>) {
        clearForContact(contactId)
        if (examples.isNotEmpty()) insertAll(examples)
    }
}
