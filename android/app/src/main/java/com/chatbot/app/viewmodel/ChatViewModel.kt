package com.chatbot.app.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chatbot.app.data.Message
import com.chatbot.app.data.Role
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    enum class ModelState { NOT_LOADED, DOWNLOADING, LOADING, READY, ERROR }
    enum class InferenceMode { GEMINI, LOCAL }

    companion object {
        const val MODEL_FILE_NAME = "model.bin"
        // Paste the file ID from your Google Drive share link:
        // https://drive.google.com/file/d/GOOGLE_DRIVE_FILE_ID/view?usp=sharing
        private const val GOOGLE_DRIVE_FILE_ID = "1FPEM_HEQsk2lc6VgXHzY5eu7F1w7_A09"
        const val MODEL_DOWNLOAD_URL =
            "https://drive.usercontent.google.com/download?id=$GOOGLE_DRIVE_FILE_ID&export=download&confirm=t"
    }

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _modelState = MutableStateFlow(ModelState.NOT_LOADED)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _modelPath = MutableStateFlow("")
    val modelPath: StateFlow<String> = _modelPath.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _geminiReady = MutableStateFlow(false)
    val geminiReady: StateFlow<Boolean> = _geminiReady.asStateFlow()

    private val _localReady = MutableStateFlow(false)
    val localReady: StateFlow<Boolean> = _localReady.asStateFlow()

    private val _geminiError = MutableStateFlow<String?>(null)
    val geminiError: StateFlow<String?> = _geminiError.asStateFlow()

    private val _currentMode = MutableStateFlow<InferenceMode?>(null)
    val currentMode: StateFlow<InferenceMode?> = _currentMode.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _downloadedBytes = MutableStateFlow(0L)
    val downloadedBytes: StateFlow<Long> = _downloadedBytes.asStateFlow()

    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes.asStateFlow()

    private var generativeModel: GenerativeModel? = null
    private var llmInference: LlmInference? = null
    private var downloadJob: Job? = null

    init {
        // Pre-fill path if model was already downloaded to private storage
        val modelFile = File(appContext.filesDir, MODEL_FILE_NAME)
        if (modelFile.exists()) {
            _modelPath.value = modelFile.absolutePath
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    fun downloadModel(apiKey: String) {
        val modelFile = File(appContext.filesDir, MODEL_FILE_NAME)
        _apiKey.value = apiKey
        _modelState.value = ModelState.DOWNLOADING
        _downloadProgress.value = 0f
        _downloadedBytes.value = 0L
        _totalBytes.value = 0L
        _statusMessage.value = "Starting download…"

        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Delete any previously failed/partial download before retrying
                if (modelFile.exists()) modelFile.delete()

                val connection = openGoogleDriveConnection(MODEL_DOWNLOAD_URL)
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Server returned $responseCode")
                }

                // Guard: Google Drive returns HTML when the confirmation fails
                val contentType = connection.contentType ?: ""
                if (contentType.contains("text/html", ignoreCase = true)) {
                    throw Exception("Google Drive returned a web page instead of the file. Make sure the file is shared as 'Anyone with the link'.")
                }

                val total = connection.contentLengthLong
                _totalBytes.value = total

                var downloaded = 0L
                _downloadedBytes.value = downloaded

                connection.inputStream.use { input ->
                    FileOutputStream(modelFile, false).use { output ->
                        val buffer = ByteArray(16_384)
                        var bytes: Int
                        while (input.read(buffer).also { bytes = it } != -1) {
                            output.write(buffer, 0, bytes)
                            downloaded += bytes
                            _downloadedBytes.value = downloaded
                            if (total > 0) {
                                _downloadProgress.value = downloaded.toFloat() / total.toFloat()
                            }
                        }
                    }
                }

                _modelPath.value = modelFile.absolutePath
                setup(apiKey, modelFile.absolutePath)

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return@launch
                Log.e("ChatViewModel", "Download failed", e)
                _modelState.value = ModelState.ERROR
                _statusMessage.value = "Download failed: ${e.message}"
            }
        }
    }

    fun resetToSetup() {
        llmInference?.close()
        llmInference = null
        generativeModel = null
        _geminiReady.value = false
        _localReady.value = false
        _modelState.value = ModelState.NOT_LOADED
        _statusMessage.value = ""
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _modelState.value = ModelState.NOT_LOADED
        _statusMessage.value = ""
    }

    // ── Google Drive download helper ──────────────────────────────────────────
    //
    // Google Drive serves a virus-scan warning HTML page for large files.
    // We detect the warning cookie in the response and replay the request
    // with that cookie to get the actual binary stream.

    private fun openGoogleDriveConnection(url: String): HttpURLConnection {
        var connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.connect()

        // Check if Google Drive set a download-warning cookie (large file confirmation)
        val cookies = connection.headerFields["Set-Cookie"] ?: emptyList()
        val warningCookie = cookies.firstOrNull { it.contains("download_warning") }

        if (warningCookie != null) {
            // Extract cookie value and replay the request with confirmation
            val cookieValue = warningCookie.split(";").first()
            val confirmToken = cookieValue.substringAfter("download_warning").substringAfter("=")
            val confirmedUrl = "$url&confirm=$confirmToken"

            connection.disconnect()
            connection = URL(confirmedUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Cookie", cookieValue)
            connection.connect()
        }

        return connection
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    fun setup(apiKey: String, modelPath: String) {
        _apiKey.value = apiKey
        _modelPath.value = modelPath
        _modelState.value = ModelState.LOADING
        _statusMessage.value = "Setting up…"

        viewModelScope.launch(Dispatchers.IO) {
            _geminiReady.value = false
            _localReady.value = false
            generativeModel = null
            llmInference?.close()
            llmInference = null

            val errors = mutableListOf<String>()

            // Online mode — Gemini API (no test call; errors surface on first message)
            if (apiKey.isNotBlank()) {
                try {
                    generativeModel = GenerativeModel(modelName = "gemini-3-flash-preview", apiKey = apiKey)
                    _geminiReady.value = true
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Gemini init failed", e)
                    errors.add("Online: ${e.message}")
                }
            }

            // Offline mode — on-device model
            if (modelPath.isNotBlank()) {
                try {
                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelPath)
                        .setMaxTokens(1024)
                        .build()
                    llmInference = LlmInference.createFromOptions(appContext, options)
                    _localReady.value = true
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "Local model setup failed", e)
                    errors.add("Offline: ${e.message}")
                }
            }

            if (_geminiReady.value || _localReady.value) {
                val modes = buildList {
                    if (_geminiReady.value) add("Gemini (online)")
                    if (_localReady.value) add("Local (offline)")
                }.joinToString(" + ")
                _modelState.value = ModelState.READY
                _statusMessage.value = "Ready: $modes"
            } else {
                _modelState.value = ModelState.ERROR
                _statusMessage.value = errors.joinToString("\n").ifBlank { "Nothing configured" }
            }
        }
    }

    // ── Chat ──────────────────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        if (_isGenerating.value || _modelState.value != ModelState.READY) return

        val mode = when {
            isOnline() && _geminiReady.value -> InferenceMode.GEMINI
            _localReady.value -> InferenceMode.LOCAL
            _geminiReady.value -> InferenceMode.GEMINI
            else -> return
        }
        _currentMode.value = mode

        _messages.update { it + Message(content = text, role = Role.USER) }
        _messages.update { it + Message(content = "", role = Role.ASSISTANT, isStreaming = true, isLocal = mode == InferenceMode.LOCAL) }
        _isGenerating.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (mode) {
                    InferenceMode.GEMINI -> sendViaGemini(text)
                    InferenceMode.LOCAL  -> sendViaLocal(text)
                }
                finalizeStreaming()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Generation failed (mode=$mode)", e)
                if (mode == InferenceMode.GEMINI && _localReady.value) {
                    // Gemini failed — disable it, notify UI, retry with local model
                    _geminiReady.value = false
                    _geminiError.value = e.message ?: "Gemini request failed"
                    _currentMode.value = InferenceMode.LOCAL
                    // Replace the streaming bubble with a local one and retry
                    _messages.update { list ->
                        list.dropLast(1) + Message(content = "", role = Role.ASSISTANT, isStreaming = true, isLocal = true)
                    }
                    try {
                        sendViaLocal(text)
                        finalizeStreaming()
                    } catch (localEx: Exception) {
                        appendError(localEx.message ?: "Generation failed")
                    }
                } else {
                    appendError(e.message ?: "Generation failed")
                }
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun clearGeminiError() {
        _geminiError.value = null
    }

    fun clearChat() {
        _messages.value = emptyList()
    }

    // ── Inference helpers ─────────────────────────────────────────────────────

    private suspend fun sendViaGemini(text: String) {
        val model = generativeModel ?: throw IllegalStateException("Gemini not initialized")
        val history = _messages.value.dropLast(2).map { msg ->
            content(role = if (msg.role == Role.USER) "user" else "model") {
                text(msg.content)
            }
        }
        val chat = model.startChat(history = history)
        chat.sendMessageStream(text).collect { chunk ->
            chunk.text?.let { if (it.isNotEmpty()) appendToken(it) }
        }
    }

    private suspend fun sendViaLocal(text: String) {
        val llm = llmInference ?: throw IllegalStateException("Local model not loaded")
        val prompt = buildGemmaPrompt(_messages.value.dropLast(1))
        suspendCancellableCoroutine { continuation ->
            llm.generateResponseAsync(prompt) { partial, done ->
                if (!partial.isNullOrEmpty()) appendToken(partial)
                if (done && continuation.isActive) continuation.resume(Unit)
            }
        }
    }

    private fun isOnline(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun buildGemmaPrompt(history: List<Message>): String = buildString {
        for (msg in history) {
            when (msg.role) {
                Role.USER      -> append("<start_of_turn>user\n${msg.content}<end_of_turn>\n")
                Role.ASSISTANT -> append("<start_of_turn>model\n${msg.content}<end_of_turn>\n")
            }
        }
        append("<start_of_turn>model\n")
    }

    // ── State helpers ─────────────────────────────────────────────────────────

    private fun appendToken(token: String) {
        _messages.update { list ->
            val idx = list.indexOfLast { it.role == Role.ASSISTANT }
            if (idx == -1) return@update list
            list.toMutableList().also { it[idx] = it[idx].copy(content = it[idx].content + token) }
        }
    }

    private fun appendError(msg: String) {
        _messages.update { list ->
            val idx = list.indexOfLast { it.role == Role.ASSISTANT }
            if (idx == -1) return@update list
            list.toMutableList().also {
                it[idx] = it[idx].copy(content = "Error: $msg", isStreaming = false)
            }
        }
    }

    private fun finalizeStreaming() {
        _messages.update { list ->
            list.map { if (it.isStreaming) it.copy(isStreaming = false) else it }
        }
    }

    override fun onCleared() {
        super.onCleared()
        llmInference?.close()
    }
}
