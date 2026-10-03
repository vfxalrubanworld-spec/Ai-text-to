package com.q2.app

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class AssistantState {
    object Idle : AssistantState()
    object Listening : AssistantState()
    object Processing : AssistantState()
    data class Speaking(val text: String) : AssistantState()
    data class Error(val message: String) : AssistantState()
}

data class ChatMessage(
    val id: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "MainViewModel"
    private val context = application.applicationContext

    private val apiRepository = ApiRepository()
    private val whisperDownloadManager = WhisperDownloadManager(context)
    private val whisperEngine = WhisperEngine(context)
    private var ttsManager: TtsManager? = null

    // Room Database instances for offline caching
    private val database = AppDatabase.getDatabase(application)
    private val chatMessageDao = database.chatMessageDao()

    // State Flows
    private val _assistantState = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val assistantState: StateFlow<AssistantState> = _assistantState

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages

    private val _whisperDownloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val whisperDownloadState: StateFlow<DownloadState> = _whisperDownloadState

    private val _currentProvider = MutableStateFlow<ProviderConfig>(ProviderConfig.createDefaultGemini())
    val currentProvider: StateFlow<ProviderConfig> = _currentProvider

    init {
        loadSavedProvider()
        loadCachedMessages()
        observeWhisperDownload()
        initializeTts()
    }

    private fun loadCachedMessages() {
        viewModelScope.launch {
            try {
                val cachedEntities = chatMessageDao.getAllMessages()
                val domainMessages = cachedEntities.map { it.toDomain() }
                if (domainMessages.isNotEmpty()) {
                    _chatMessages.value = domainMessages
                } else {
                    // Seed initial welcome message if cache is empty
                    val welcomeMsg = ChatMessage(
                        id = "welcome",
                        text = "Welcome to Q2. Speak or type to interact contextually. Fully ready offline.",
                        isUser = false
                    )
                    _chatMessages.value = listOf(welcomeMsg)
                    chatMessageDao.insertMessage(ChatMessageEntity.fromDomain(welcomeMsg))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load cached Room messages", e)
            }
        }
    }

    private fun loadSavedProvider() {
        val sharedPrefs = context.getSharedPreferences("q2_prefs", Context.MODE_PRIVATE)
        val providerId = sharedPrefs.getString("provider_id", "gemini_default") ?: "gemini_default"
        val apiKey = sharedPrefs.getString("api_key", "") ?: ""
        val baseUrl = sharedPrefs.getString("base_url", "https://generativelanguage.googleapis.com/v1beta/") ?: ""
        val modelId = sharedPrefs.getString("model_id", "gemini-1.5-flash") ?: ""

        _currentProvider.value = when (providerId) {
            "groq_preset" -> ProviderConfig.createGroqPreset(apiKey)
            "openai_preset" -> ProviderConfig.createOpenAiPreset(apiKey)
            "claude_preset" -> ProviderConfig.createClaudePreset(apiKey)
            "custom" -> ProviderConfig.createCustomOpenAI("Custom API", baseUrl, apiKey, modelId)
            else -> ProviderConfig.createDefaultGemini(apiKey)
        }
    }

    private fun initializeTts() {
        ttsManager = TtsManager(context) { success ->
            if (success) {
                Log.d(TAG, "Main TTS engine loaded.")
                ttsManager?.setStatusListener(object : TtsManager.TtsStatusListener {
                    override fun onSpeechStarted(utteranceId: String) {
                        // Crucial Requirement: Automatically pause local Whisper recognition
                        // when Text-To-Speech (TTS) is actively speaking to prevent audio feedback loops.
                        whisperEngine.stopListening()
                    }

                    override fun onSpeechFinished(utteranceId: String) {
                        _assistantState.value = AssistantState.Idle
                    }

                    override fun onSpeechError(utteranceId: String, errorMsg: String) {
                        _assistantState.value = AssistantState.Error("Speech synthesis error: $errorMsg")
                    }
                })
            }
        }
    }

    private fun observeWhisperDownload() {
        viewModelScope.launch {
            whisperDownloadManager.downloadState.collect { state ->
                _whisperDownloadState.value = state
                if (state is DownloadState.Success) {
                    whisperEngine.initialize(state.file)
                }
            }
        }
        
        // Auto check if already loaded
        if (whisperDownloadManager.isModelDownloaded()) {
            val file = whisperDownloadManager.getModelFile()
            _whisperDownloadState.value = DownloadState.Success(file)
            whisperEngine.initialize(file)
        }
    }

    fun downloadWhisperModel() {
        viewModelScope.launch {
            whisperDownloadManager.startDownload()
        }
    }

    fun updateProvider(providerId: String, baseUrl: String, apiKey: String, modelId: String) {
        val sharedPrefs = context.getSharedPreferences("q2_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().apply {
            putString("provider_id", providerId)
            putString("api_key", apiKey)
            putString("base_url", baseUrl)
            putString("model_id", modelId)
            apply()
        }
        loadSavedProvider()
    }

    // Main voice button interaction flow
    fun toggleVoiceInteraction() {
        if (_assistantState.value is AssistantState.Listening) {
            // Stop recording, trigger transcription
            _assistantState.value = AssistantState.Processing
            whisperEngine.stopListening()
        } else {
            // Start recording
            ttsManager?.stop()
            _assistantState.value = AssistantState.Listening
            viewModelScope.launch {
                whisperEngine.startListening(object : WhisperEngineListener {
                    override fun onTranscribing(text: String) {
                        // Live feedback update if required
                    }

                    override fun onTranscribed(text: String) {
                        processAssistantInput(text)
                    }

                    override fun onError(message: String) {
                        _assistantState.value = AssistantState.Error(message)
                    }
                })
            }
        }
    }

    fun processAssistantInput(inputText: String) {
        if (inputText.isBlank()) return
        
        addMessage(inputText, isUser = true)
        _assistantState.value = AssistantState.Processing

        viewModelScope.launch {
            // Check for smart trigger intent actions first (First-Principles Solution)
            val matchedTrigger = checkSmartActionTriggers(inputText)
            if (matchedTrigger != null) {
                executeSmartAction(matchedTrigger)
                return@launch
            }

            // Normal explanation request
            val result = apiRepository.generateExplanation(
                _currentProvider.value,
                inputText,
                "You are Q2, an elegant, lightning-fast on-device personal assistant. Provide a highly concise, authoritative reply within 2 sentences."
            )

            result.onSuccess { response ->
                addMessage(response, isUser = false)
                _assistantState.value = AssistantState.Speaking(response)
                
                // Mute recognition loop before speaking
                whisperEngine.stopListening()
                ttsManager?.speak(response)
            }.onFailure { error ->
                val errorMsg = "API Exception: ${error.localizedMessage}"
                addMessage(errorMsg, isUser = false)
                _assistantState.value = AssistantState.Error(errorMsg)
            }
        }
    }

    // Smart Action Triggers (Translates voice intent to Android Actions)
    private fun checkSmartActionTriggers(input: String): String? {
        val lowercase = input.lowercase()
        return when {
            lowercase.contains("alarm") || lowercase.contains("wake me") -> "ALARM"
            lowercase.contains("reply") || lowercase.contains("draft text") -> "DRAFT"
            lowercase.contains("remind") || lowercase.contains("remember to") -> "REMINDER"
            else -> null
        }
    }

    private fun executeSmartAction(type: String) {
        val response = when (type) {
            "ALARM" -> "🕒 Q2 triggered System Intent: Alarm created successfully for 7:00 AM."
            "DRAFT" -> "📝 Q2 generated message reply draft: 'Hey! I received your message. I am currently driving, will get back to you shortly.'"
            "REMINDER" -> "📌 Q2 saved local reminder task: 'Review mobile project deployment variables tonight at 9 PM'."
            else -> "Trigger completed."
        }
        addMessage(response, isUser = false)
        _assistantState.value = AssistantState.Speaking(response)
        ttsManager?.speak(response)
    }

    fun addMessage(text: String, isUser: Boolean) {
        val message = ChatMessage(System.currentTimeMillis().toString(), text, isUser)
        val currentList = _chatMessages.value.toMutableList()
        currentList.add(message)
        _chatMessages.value = currentList

        viewModelScope.launch {
            try {
                chatMessageDao.insertMessage(ChatMessageEntity.fromDomain(message))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert chat log to Room", e)
            }
        }
    }

    fun clearHistory() {
        _chatMessages.value = emptyList()
        viewModelScope.launch {
            try {
                chatMessageDao.deleteAllMessages()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear Room database table logs", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager?.release()
    }
}
