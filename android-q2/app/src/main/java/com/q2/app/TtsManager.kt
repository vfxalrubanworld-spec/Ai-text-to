package com.q2.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class TtsManager(private val context: Context, private val onInitCompleted: (Boolean) -> Unit) {

    private val TAG = "TtsManager"
    private var textToSpeech: TextToSpeech? = null
    private var isInitialized = false

    private val initListener = TextToSpeech.OnInitListener { status ->
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "English US Language is not supported on this device.")
                isInitialized = false
                onInitCompleted(false)
            } else {
                Log.d(TAG, "TTS Engine successfully loaded.")
                isInitialized = true
                setupProgressListener()
                onInitCompleted(true)
            }
        } else {
            Log.e(TAG, "TTS Initialization failed with code: $status")
            isInitialized = false
            onInitCompleted(false)
        }
    }

    init {
        textToSpeech = TextToSpeech(context.applicationContext, initListener)
    }

    private var speechStatusListener: TtsStatusListener? = null

    interface TtsStatusListener {
        fun onSpeechStarted(utteranceId: String)
        fun onSpeechFinished(utteranceId: String)
        fun onSpeechError(utteranceId: String, errorMsg: String)
    }

    fun setStatusListener(listener: TtsStatusListener) {
        speechStatusListener = listener
    }

    private fun setupProgressListener() {
        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                Log.d(TAG, "TTS Speaking started for ID: $utteranceId")
                speechStatusListener?.onSpeechStarted(utteranceId)
            }

            override fun onDone(utteranceId: String) {
                Log.d(TAG, "TTS Speaking completed for ID: $utteranceId")
                speechStatusListener?.onSpeechFinished(utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                Log.e(TAG, "TTS Speaking error occurred for ID: $utteranceId")
                speechStatusListener?.onSpeechError(utteranceId, "Unknown engine failure")
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                val description = when (errorCode) {
                    TextToSpeech.ERROR_SYNTHESIS -> "Synthesis error"
                    TextToSpeech.ERROR_AUDIO_TRACK_INIT -> "Audio Track initialization error"
                    TextToSpeech.ERROR_NETWORK -> "Network connection failure"
                    TextToSpeech.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    TextToSpeech.ERROR_NOT_INSTALLED_YET -> "Language data is not fully installed"
                    else -> "TTS System error code: $errorCode"
                }
                Log.e(TAG, "TTS error: $description")
                speechStatusListener?.onSpeechError(utteranceId, description)
            }
        })
    }

    fun speak(text: String, pitch: Float = 1.0f, speed: Float = 1.0f, utteranceId: String = "q2_speak_id") {
        if (!isInitialized) {
            Log.e(TAG, "TTS is not initialized yet.")
            return
        }

        // Apply adjustable rates
        textToSpeech?.setPitch(pitch)
        textToSpeech?.setSpeechRate(speed)

        Log.d(TAG, "Synthesizing text: '$text'")
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        if (textToSpeech?.isSpeaking == true) {
            textToSpeech?.stop()
        }
    }

    fun release() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isInitialized = false
    }

    fun isSpeaking(): Boolean {
        return textToSpeech?.isSpeaking == true
    }
}
