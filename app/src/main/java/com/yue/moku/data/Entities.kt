package com.yue.moku.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 分支来源：如果此对话是从另一个对话的某条消息 regenerate 而来，则指向来源对话 ID */
    val parentBranchId: Long? = null,
    /** 分叉点消息 ID：来源对话中分叉发生位置的最后一条保留的消息（即那条 user 消息的 id） */
    val forkMessageId: Long? = null,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val reasoning: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val isError: Boolean = false,
    val generationMs: Long? = null,
    val stopReason: String? = null,
)

@Entity(tableName = "knowledge")
data class KnowledgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val tags: String = "",
    val category: String = "资料",
    val priority: Int = 3,
    val isPinned: Boolean = false,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

