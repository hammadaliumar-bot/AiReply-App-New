package com.umar.aireply.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {

    @Query("SELECT * FROM approved_contacts ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ApprovedContactEntity>>

    @Query("SELECT * FROM approved_contacts ORDER BY createdAt ASC")
    suspend fun getAll(): List<ApprovedContactEntity>

    @Query("SELECT * FROM approved_contacts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ApprovedContactEntity?

    @Query("SELECT * FROM approved_contacts WHERE lookupKey = :lookupKey LIMIT 1")
    suspend fun getByLookupKey(lookupKey: String): ApprovedContactEntity?

    /** Used by the notification service: exact match on the normalized saved name. */
    @Query("SELECT * FROM approved_contacts WHERE normalizedName = :normalizedName LIMIT 1")
    suspend fun findByNormalizedName(normalizedName: String): ApprovedContactEntity?

    @Query("SELECT COUNT(*) FROM approved_contacts")
    suspend fun count(): Int

    /** Returns the new row id, or -1 if this contact is already in the list. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(contact: ApprovedContactEntity): Long

    @Update
    suspend fun update(contact: ApprovedContactEntity)

    @Delete
    suspend fun delete(contact: ApprovedContactEntity)

    @Query("DELETE FROM approved_contacts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE approved_contacts SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE approved_contacts SET persona = :persona WHERE id = :id")
    suspend fun setPersona(id: Long, persona: String)

    @Query("UPDATE approved_contacts SET callName = :callName WHERE id = :id")
    suspend fun setCallName(id: Long, callName: String)

    @Query(
        "UPDATE approved_contacts SET styleProfile = :profile, styleUpdatedAt = :updatedAt, " +
            "importedMessageCount = :importedCount WHERE id = :id"
    )
    suspend fun setStyleProfile(id: Long, profile: String, updatedAt: Long, importedCount: Int)

    /** Called by the contact-sync logic when the phone's saved name or numbers change. */
    @Query(
        "UPDATE approved_contacts SET savedName = :savedName, normalizedName = :normalizedName, " +
            "phoneNumbers = :phoneNumbers WHERE id = :id"
    )
    suspend fun updateSavedInfo(id: Long, savedName: String, normalizedName: String, phoneNumbers: String)

    /** Safety switch: turns every contact off at once. */
    @Query("UPDATE approved_contacts SET enabled = 0")
    suspend fun disableAll()
}
