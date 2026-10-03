package com.q2.app

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.q2.app.databinding.OverlayReadAloudBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Q2AccessibilityService : AccessibilityService() {

    private val TAG = "Q2AccessibilityService"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private lateinit var windowManager: WindowManager
    private var overlayBinding: OverlayReadAloudBinding? = null
    private var ttsManager: TtsManager? = null
    private var apiRepository: ApiRepository? = null
    
    private var lastSelectedText: String = ""
    private var defaultProvider: ProviderConfig = ProviderConfig.createDefaultGemini()

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        apiRepository = ApiRepository()
        
        // Load default provider configurations from shared preferences
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

        initializeTts()
    }

    private fun initializeTts() {
        ttsManager = TtsManager(this) { success ->
            if (success) {
                Log.d(TAG, "Accessibility speech synthesized loaded.")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            val rawText = event.text?.joinToString(" ") ?: ""
            if (rawText.isNotBlank() && rawText != lastSelectedText) {
                lastSelectedText = rawText
                Log.d(TAG, "Selected text detected: $lastSelectedText")
                showFloatingBubble(lastSelectedText)
            }
        }
    }

    override fun onInterrupt() {
        Log.e(TAG, "Accessibility services interrupted")
        removeFloatingBubble()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingBubble(text: String) {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW permission is missing. Cannot draw bubble.")
            return
        }

        if (overlayBinding != null) {
            overlayBinding?.txtSelectedPreview?.text = text
            overlayBinding?.root?.visibility = View.VISIBLE
            return
        }

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        overlayBinding = OverlayReadAloudBinding.inflate(inflater)

        // Android Overlay layout parameters
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN-SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 100 // Height above bottom menu
        }

        overlayBinding?.apply {
            txtSelectedPreview.text = text
            txtExplanation.text = "Tap 'Read & Explain' for offline TTS and instant 1-sentence explanation."
            progressBar.visibility = View.GONE
            
            // Set drag listener
            var initialY: Int = 0
            var initialTouchY: Float = 0.0f

            root.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialY = params.y
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.y = initialY + (initialTouchY - event.rawY).toInt()
                        windowManager.updateViewLayout(root, params)
                        true
                    }
                    else -> false
                }
            }

            btnReadAloud.setOnClickListener {
                ttsManager?.speak(text)
                txtExplanation.text = "Speaking selected text..."
            }

            btnExplain.setOnClickListener {
                progressBar.visibility = View.VISIBLE
                btnExplain.isEnabled = false
                txtExplanation.text = "Analyzing text context with Q2..."

                serviceScope.launch {
                    val systemPrompt = "You are Q2. Analyze the selected text and provide a concise, maximum 1-sentence contextual explanation."
                    val result = apiRepository?.generateExplanation(defaultProvider, text, systemPrompt)
                    
                    withContext(Dispatchers.Main) {
                        progressBar.visibility = View.GONE
                        btnExplain.isEnabled = true
                        
                        result?.onSuccess { explanation ->
                            txtExplanation.text = explanation
                            ttsManager?.speak("Explanation: $explanation")
                        }?.onFailure { error ->
                            txtExplanation.text = "AI failed: ${error.localizedMessage}"
                        }
                    }
                }
            }

            btnCloseOverlay.setOnClickListener {
                removeFloatingBubble()
            }
        }

        try {
            windowManager.addView(overlayBinding?.root, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding window layout", e)
        }
    }

    private fun removeFloatingBubble() {
        ttsManager?.stop()
        overlayBinding?.let {
            try {
                windowManager.removeView(it.root)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view", e)
            }
            overlayBinding = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeFloatingBubble()
        ttsManager?.release()
    }
}
