package com.q2.app

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val TAG = "ApiRepository"

    suspend fun generateExplanation(
        provider: ProviderConfig,
        prompt: String,
        systemInstruction: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (provider.isDefault) {
                callGeminiApi(provider, prompt, systemInstruction)
            } else {
                callOpenAiCompatibleApi(provider, prompt, systemInstruction)
            }
        } catch (e: Exception) {
            Log.e(TAG, "API execution error", e)
            Result.failure(e)
        }
    }

    private fun callGeminiApi(
        provider: ProviderConfig,
        prompt: String,
        systemInstruction: String
    ): Result<String> {
        val apiKey = provider.apiKey.ifEmpty { "MOCK_KEY" }
        // Clean URL building
        val cleanBaseUrl = provider.baseUrl.trimEnd('/')
        val url = "$cleanBaseUrl/models/${provider.modelId}:generateContent?key=$apiKey"

        // Build Gemini payload structure
        val requestBodyJson = JsonObject().apply {
            val contentsArray = JsonArray().apply {
                val contentObj = JsonObject().apply {
                    val partsArray = JsonArray().apply {
                        val partObj = JsonObject().apply {
                            addProperty("text", prompt)
                        }
                        add(partObj)
                    }
                    add("parts", partsArray)
                }
                add(contentObj)
            }
            add("contents", contentsArray)

            if (systemInstruction.isNotEmpty()) {
                val systemInstructionObj = JsonObject().apply {
                    val partsArray = JsonArray().apply {
                        val partObj = JsonObject().apply {
                            addProperty("text", systemInstruction)
                        }
                        add(partObj)
                    }
                    add("parts", partsArray)
                }
                add("systemInstruction", systemInstructionObj)
            }
        }

        val requestBodyStr = gson.toJson(requestBodyJson)
        val body = requestBodyStr.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Content-Type", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                return Result.failure(IOException("Gemini API Error: ${response.code} $errorBody"))
            }

            val responseBody = response.body?.string() ?: return Result.failure(IOException("Empty response"))
            
            // Extract text from Gemini response JSON structure:
            // response.candidates[0].content.parts[0].text
            return try {
                val jsonObject = gson.fromJson(responseBody, JsonObject::class.java)
                val candidates = jsonObject.getAsJsonArray("candidates")
                val firstCandidate = candidates.get(0).asJsonObject
                val content = firstCandidate.getAsJsonObject("content")
                val parts = content.getAsJsonArray("parts")
                val firstPart = parts.get(0).asJsonObject
                val text = firstPart.get("text").asString
                Result.success(text)
            } catch (e: Exception) {
                Log.e(TAG, "Failed parsing Gemini JSON. Raw: $responseBody", e)
                Result.success(responseBody) // Fallback to raw text
            }
        }
    }

    private fun callOpenAiCompatibleApi(
        provider: ProviderConfig,
        prompt: String,
        systemInstruction: String
    ): Result<String> {
        val cleanBaseUrl = provider.baseUrl.trimEnd('/')
        val url = "$cleanBaseUrl/chat/completions"

        // Build OpenAI request structure
        val requestBodyJson = JsonObject().apply {
            addProperty("model", provider.modelId)
            
            val messagesArray = JsonArray().apply {
                if (systemInstruction.isNotEmpty()) {
                    val systemMessage = JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", systemInstruction)
                    }
                    add(systemMessage)
                }
                val userMessage = JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", prompt)
                }
                add(userMessage)
            }
            add("messages", messagesArray)
            addProperty("temperature", 0.7)
        }

        val requestBodyStr = gson.toJson(requestBodyJson)
        val body = requestBodyStr.toRequestBody("application/json".toMediaType())

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)
            .header("Content-Type", "application/json")

        if (provider.apiKey.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer ${provider.apiKey}")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                return Result.failure(IOException("Custom Provider Error: ${response.code} $errorBody"))
            }

            val responseBody = response.body?.string() ?: return Result.failure(IOException("Empty response"))

            // Extract content from OpenAI response JSON structure:
            // response.choices[0].message.content
            return try {
                val jsonObject = gson.fromJson(responseBody, JsonObject::class.java)
                val choices = jsonObject.getAsJsonArray("choices")
                val firstChoice = choices.get(0).asJsonObject
                val message = firstChoice.getAsJsonObject("message")
                val content = message.get("content").asString
                Result.success(content)
            } catch (e: Exception) {
                Log.e(TAG, "Failed parsing OpenAI JSON. Raw: $responseBody", e)
                Result.success(responseBody)
            }
        }
    }
}
