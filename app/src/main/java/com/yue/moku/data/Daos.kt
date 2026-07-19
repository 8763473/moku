package com.yue.moku.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(): ConversationEntity?

    @Insert
    suspend fun insert(value: ConversationEntity): Long

    @Update
    suspend fun update(value: ConversationEntity)

    @Delete
    suspend fun delete(value: ConversationEntity)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt, id")
    fun observeForConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt, id")
    suspend fun listForConversation(conversationId: Long): List<MessageEntity>

    @Insert
    suspend fun insert(value: MessageEntity): Long

    @Update
    suspend fun update(value: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    @Delete
    suspend fun delete(value: MessageEntity)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id > :fromId")
    suspend fun deleteAfter(conversationId: Long, fromId: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id >= :fromId AND id <= :toId")
    suspend fun deleteRange(conversationId: Long, fromId: Long, toId: Long)
}

@Dao
interface KnowledgeDao {
    @Query("SELECT * FROM knowledge ORDER BY isPinned DESC, priority DESC, updatedAt DESC")
    fun observeAll(): Flow<List<KnowledgeEntity>>

    @Query("SELECT * FROM knowledge WHERE isEnabled = 1 ORDER BY isPinned DESC, priority DESC, updatedAt DESC")
    suspend fun listEnabled(): List<KnowledgeEntity>

    @Upsert
    suspend fun upsert(value: KnowledgeEntity): Long

    @Delete
    suspend fun delete(value: KnowledgeEntity)
}
