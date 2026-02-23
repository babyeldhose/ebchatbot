package com.chatbot.app.data

import java.util.UUID

enum class Role { USER, ASSISTANT }

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val content: String = "",
    val role: Role = Role.USER,
    val isStreaming: Boolean = false,
    val isLocal: Boolean = false  // true = on-device model, false = Gemini
)
