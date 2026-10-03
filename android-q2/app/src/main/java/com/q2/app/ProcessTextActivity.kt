package com.q2.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.q2.app.databinding.OverlayReadAloudBinding
import android.view.LayoutInflater
import android.view.WindowManager
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProcessTextActivity : AppCompatActivity() {

    private val TAG = "ProcessTextActivity"
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var ttsManager: TtsManager? = null
    private var apiRepository: ApiRepository? = null
    private var defaultProvider: ProviderConfig = ProviderConfig.createDefaultGemini()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Translucent theme configuration
        window.setBackgroundDrawableResource(android.R.color.transparent)
        
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) ?: ""
        val textString = text.toString()

        if (textString.isBlank()) {
            Toast.makeText(this, "No text selected for Q2", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        apiRepository = ApiRepository()
        
        // Read configuration preferences
        val sharedPrefs = getSharedPreferences("q2_prefs", Context.MODE_PRIVATE)
        val apiKey = sharedPrefs.getString("api_key", "") ?: ""
        val baseUrl = sharedPrefs.getString("base_url", "https://generativelanguage.googleapis.com/v1beta/") ?: ""
        val modelId = sharedPrefs.getString("model_id", "gemini-1.5-flash") ?: ""
        val isCustom = sharedPrefs.getBoolean("is_custom", false)

        defaultProvider = if (isCustom) {
            ProviderConfig.createCustomOpenAI("Custom", baseUrl, apiKey, modelId)
        } else {
            ProviderConfig.createDefaultGemini(apiKey)
        }

        ttsManager = TtsManager(this) { success ->
            if (success) {
                processSelectedText(textString)
            } else {
                Toast.makeText(this, "TTS initialization failed", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun processSelectedText(text: String) {
        Toast.makeText(this, "Processing selection with Q2...", Toast.LENGTH_LONG).show()
        
        ttsManager?.speak("Processing: $text")

        activityScope.launch {
            val systemPrompt = "Explain this text selected by the user in exactly 1 simple sentence."
            val result = apiRepository?.generateExplanation(defaultProvider, text, systemPrompt)
            
            withContext(Dispatchers.Main) {
                result?.onSuccess { explanation ->
                    Log.d(TAG, "Explanation retrieved: $explanation")
                    // Show custom Toast or floating message
                    Toast.makeText(this@ProcessTextActivity, "Q2 Explains: $explanation", Toast.LENGTH_LONG).show()
                    ttsManager?.speak("Q2 explains: $explanation")
                    
                    // Allow TTS to complete speaking before closing the activity
                    Thread {
                        try {
                            Thread.sleep(5000)
                        } catch (e: Exception) {}
                        runOnUiThread {
                            finish()
                        }
                    }.start()
                }?.onFailure { error ->
                    Toast.makeText(this@ProcessTextActivity, "Q2 Error: ${error.localizedMessage}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager?.release()
    }
}
