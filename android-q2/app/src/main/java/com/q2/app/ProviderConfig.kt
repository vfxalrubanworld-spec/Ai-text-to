package com.q2.app

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val modelId: String,
    val isDefault: Boolean = false
) : Parcelable {
    companion object {
        fun createDefaultGemini(apiKey: String = ""): ProviderConfig {
            return ProviderConfig(
                id = "gemini_default",
                name = "Google Gemini",
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/",
                apiKey = apiKey,
                modelId = "gemini-1.5-flash",
                isDefault = true
            )
        }

        fun createGroqPreset(apiKey: String = ""): ProviderConfig {
            return ProviderConfig(
                id = "groq_preset",
                name = "Groq Cloud API",
                baseUrl = "https://api.groq.com/openai/v1/",
                apiKey = apiKey,
                modelId = "llama3-8b-8192",
                isDefault = false
            )
        }

        fun createOpenAiPreset(apiKey: String = ""): ProviderConfig {
            return ProviderConfig(
                id = "openai_preset",
                name = "OpenAI Platform",
                baseUrl = "https://api.openai.com/v1/",
                apiKey = apiKey,
                modelId = "gpt-4o-mini",
                isDefault = false
            )
        }

        fun createClaudePreset(apiKey: String = ""): ProviderConfig {
            return ProviderConfig(
                id = "claude_preset",
                name = "Anthropic Claude API",
                baseUrl = "https://api.anthropic.com/v1/",
                apiKey = apiKey,
                modelId = "claude-3-5-haiku",
                isDefault = false
            )
        }

        fun createCustomOpenAI(name: String, baseUrl: String, apiKey: String, modelId: String): ProviderConfig {
            return ProviderConfig(
                id = "custom_${System.currentTimeMillis()}",
                name = name,
                baseUrl = baseUrl,
                apiKey = apiKey,
                modelId = modelId,
                isDefault = false
            )
        }
    }
}
