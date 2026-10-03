package com.q2.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.q2.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Microphone permission granted.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Microphone permission is required for local STT.", Toast.LENGTH_LONG).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission configured.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeViewModel()
        checkPermissions()
    }

    private fun setupUI() {
        // Setup Chat Recycler
        val chatAdapter = ChatAdapter()
        binding.recyclerChat.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }

        // Dropdown Provider selection
        val providers = listOf("Google Gemini API", "Custom OpenAI-Compatible API")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers)
        binding.spinnerProvider.adapter = spinnerAdapter

        binding.spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isCustomSelected = (position == 1)
                binding.layoutCustomApiConfig.visibility = if (isCustomSelected) View.VISIBLE else View.GONE
                
                // Read from memory and bind to config inputs
                val sharedPrefs = getSharedPreferences("q2_prefs", Context.MODE_PRIVATE)
                binding.edtApiKey.setText(sharedPrefs.getString("api_key", ""))
                binding.edtBaseUrl.setText(sharedPrefs.getString("base_url", "https://generativelanguage.googleapis.com/v1beta/"))
                binding.edtModelId.setText(sharedPrefs.getString("model_id", "gemini-1.5-flash"))
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Save custom provider config click listener
        binding.btnSaveConfig.setOnClickListener {
            val isCustom = binding.spinnerProvider.selectedItemPosition == 1
            val apiKey = binding.edtApiKey.text.toString().trim()
            val baseUrl = binding.edtBaseUrl.text.toString().trim()
            val modelId = binding.edtModelId.text.toString().trim()

            viewModel.updateProvider(baseUrl, apiKey, modelId, isCustom)
            Toast.makeText(this, "Q2 Provider Config saved successfully.", Toast.LENGTH_SHORT).show()
        }

        // Start local Whisper Download
        binding.btnDownloadWhisper.setOnClickListener {
            viewModel.downloadWhisperModel()
        }

        // Main voice button interaction trigger
        binding.btnVoiceTrigger.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                viewModel.toggleVoiceInteraction()
            } else {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        // Text query trigger
        binding.edtTextQuery.setOnEditorActionListener { v, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                val text = binding.edtTextQuery.text.toString().trim()
                if (text.isNotEmpty()) {
                    viewModel.processAssistantInput(text)
                    binding.edtTextQuery.text.clear()
                }
                true
            } else false
        }

        // Top 5 crazy features buttons bindings
        binding.btnScanScreen.setOnClickListener {
            viewModel.scanScreenContextAndAnalyze()
        }

        binding.btnSummarizeChats.setOnClickListener {
            viewModel.summarizeLongConversation()
        }

        binding.btnTranslateEsp.setOnClickListener {
            viewModel.translateVoiceInput("Spanish")
        }

        binding.btnOfflineQuery.setOnClickListener {
            viewModel.runOfflineKnowledgeRetrieval("Explain what is an API")
        }

        binding.btnOverlayPermission.setOnClickListener {
            requestOverlayPermission()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe Whisper Download State
                launch {
                    viewModel.whisperDownloadState.collect { state ->
                        updateDownloadStatusUI(state)
                    }
                }

                // Observe Assistant Interaction State
                launch {
                    viewModel.assistantState.collect { state ->
                        updateAssistantStateUI(state)
                    }
                }

                // Observe Chat Feed
                launch {
                    viewModel.chatMessages.collect { messages ->
                        (binding.recyclerChat.adapter as ChatAdapter).submitList(messages)
                        if (messages.isNotEmpty()) {
                            binding.recyclerChat.smoothScrollToPosition(messages.size - 1)
                        }
                    }
                }
            }
        }
    }

    private fun updateDownloadStatusUI(state: DownloadState) {
        when (state) {
            is DownloadState.Idle -> {
                binding.layoutWhisperStatus.visibility = View.VISIBLE
                binding.progressBarWhisper.visibility = View.GONE
                binding.txtWhisperStatus.text = "Local Speech-to-Text Weights (Required for Offline Input)"
                binding.btnDownloadWhisper.visibility = View.VISIBLE
            }
            is DownloadState.Downloading -> {
                binding.layoutWhisperStatus.visibility = View.VISIBLE
                binding.progressBarWhisper.visibility = View.VISIBLE
                binding.progressBarWhisper.progress = state.progress
                binding.txtWhisperStatus.text = "Downloading Whisper Model Weights... ${state.progress}% (${String.format("%.1f", state.speedKb)} KB/s)"
                binding.btnDownloadWhisper.visibility = View.GONE
            }
            is DownloadState.Verifying -> {
                binding.layoutWhisperStatus.visibility = View.VISIBLE
                binding.progressBarWhisper.visibility = View.VISIBLE
                binding.progressBarWhisper.isIndeterminate = true
                binding.txtWhisperStatus.text = "Verifying downloaded files..."
                binding.btnDownloadWhisper.visibility = View.GONE
            }
            is DownloadState.Success -> {
                binding.layoutWhisperStatus.visibility = View.GONE // Hide download card once successfully ready
                binding.btnVoiceTrigger.isEnabled = true
            }
            is DownloadState.Error -> {
                binding.layoutWhisperStatus.visibility = View.VISIBLE
                binding.progressBarWhisper.visibility = View.GONE
                binding.txtWhisperStatus.text = "Error: ${state.message}"
                binding.btnDownloadWhisper.visibility = View.VISIBLE
                binding.btnDownloadWhisper.text = "Retry"
            }
        }
    }

    private fun updateAssistantStateUI(state: AssistantState) {
        when (state) {
            is AssistantState.Idle -> {
                binding.txtAssistantPrompt.text = "Tap to speak with Q2"
                binding.btnVoiceTrigger.setImageResource(android.R.drawable.presence_audio_online)
                binding.waveVisualizer.visibility = View.GONE
                binding.progressBarGeneral.visibility = View.GONE
            }
            is AssistantState.Listening -> {
                binding.txtAssistantPrompt.text = "Listening... Tap to finish"
                binding.btnVoiceTrigger.setImageResource(android.R.drawable.presence_audio_busy)
                binding.waveVisualizer.visibility = View.VISIBLE
                binding.progressBarGeneral.visibility = View.GONE
            }
            is AssistantState.Processing -> {
                binding.txtAssistantPrompt.text = "Processing query..."
                binding.progressBarGeneral.visibility = View.VISIBLE
                binding.waveVisualizer.visibility = View.GONE
            }
            is AssistantState.Speaking -> {
                binding.txtAssistantPrompt.text = "Speaking response..."
                binding.progressBarGeneral.visibility = View.GONE
                binding.waveVisualizer.visibility = View.VISIBLE
            }
            is AssistantState.Error -> {
                binding.txtAssistantPrompt.text = "Error: " + state.message
                binding.progressBarGeneral.visibility = View.GONE
                binding.waveVisualizer.visibility = View.GONE
            }
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            Toast.makeText(this, "Overlay permission is already granted.", Toast.LENGTH_SHORT).show()
        }
    }
}
