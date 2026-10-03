package com.q2.app

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long
) {
    fun toDomain(): ChatMessage {
        return ChatMessage(id, text, isUser, timestamp)
    }

    companion object {
        fun fromDomain(domain: ChatMessage): ChatMessageEntity {
            return ChatMessageEntity(domain.id, domain.text, domain.isUser, domain.timestamp)
        }
    }
}
