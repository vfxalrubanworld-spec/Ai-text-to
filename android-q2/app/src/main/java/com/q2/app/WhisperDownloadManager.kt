package com.q2.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Int, val speedKb: Double, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
    object Verifying : DownloadState()
    data class Success(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class WhisperDownloadManager(private val context: Context) {

    private val TAG = "WhisperDownload"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState

    // URL to a lightweight 140MB Whisper base-en.onnx or whisper-small quantized file
    private val MODEL_DOWNLOAD_URL = "https://huggingface.co/onnx-community/whisper-base/resolve/main/onnx/encoder_model.onnx"
    private val MODEL_FILE_NAME = "whisper_base_en.onnx"

    fun getModelFile(): File {
        return File(context.filesDir, MODEL_FILE_NAME)
    }

    fun isModelDownloaded(): Boolean {
        val file = getModelFile()
        return file.exists() && file.length() > 10 * 1024 * 1024 // Greater than 10MB to verify it didn't fail halfway
    }

    suspend fun startDownload() = withContext(Dispatchers.IO) {
        if (isModelDownloaded()) {
            _downloadState.value = DownloadState.Success(getModelFile())
            return@withContext
        }

        val destinationFile = getModelFile()
        val tempFile = File(context.cacheDir, "$MODEL_FILE_NAME.tmp")

        try {
            _downloadState.value = DownloadState.Downloading(0, 0.0, 0, 1)
            Log.d(TAG, "Starting Whisper weights download from $MODEL_DOWNLOAD_URL")

            val request = Request.Builder().url(MODEL_DOWNLOAD_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Server returned HTTP ${response.code}")
                }

                val body = response.body ?: throw Exception("Empty response body")
                val totalBytes = body.contentLength()
                
                var inputStream: InputStream? = null
                var outputStream: FileOutputStream? = null

                try {
                    inputStream = body.byteStream()
                    outputStream = FileOutputStream(tempFile)

                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    var totalRead: Long = 0
                    val startTime = System.currentTimeMillis()
                    var lastUpdate = System.currentTimeMillis()

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdate > 150) { // Throttle notifications
                            val progress = if (totalBytes > 0) ((totalRead * 100) / totalBytes).toInt() else 0
                            val elapsedSec = (currentTime - startTime) / 1000.0
                            val speedKb = if (elapsedSec > 0) (totalRead / 1024.0) / elapsedSec else 0.0
                            
                            _downloadState.value = DownloadState.Downloading(
                                progress,
                                speedKb,
                                totalRead,
                                totalBytes
                            )
                            lastUpdate = currentTime
                        }
                    }

                    outputStream.flush()
                } finally {
                    inputStream?.close()
                    outputStream?.close()
                }
            }

            // Verify
            _downloadState.value = DownloadState.Verifying
            Log.d(TAG, "Verifying downloaded Whisper model...")
            
            if (tempFile.length() < 10000000) {
                throw Exception("Downloaded file is too small. Weight integrity check failed.")
            }

            // Copy to actual files directory
            tempFile.renameTo(destinationFile)
            Log.d(TAG, "Whisper model loaded and verified at ${destinationFile.absolutePath}")
            _downloadState.value = DownloadState.Success(destinationFile)

        } catch (e: Exception) {
            Log.e(TAG, "Whisper model download failed", e)
            if (tempFile.exists()) tempFile.delete()
            _downloadState.value = DownloadState.Error(e.message ?: "Unknown error while fetching weights")
        }
    }

    fun deleteModel() {
        val file = getModelFile()
        if (file.exists()) {
            file.delete()
        }
        _downloadState.value = DownloadState.Idle
    }
}
