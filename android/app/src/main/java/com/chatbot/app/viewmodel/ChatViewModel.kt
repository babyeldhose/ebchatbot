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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    enum class ModelState { NOT_LOADED, DOWNLOADING, LOADING, READY, ERROR }
    enum class InferenceMode { ONLINE, LOCAL }

    enum class OnlineProvider(
        val displayName: String,
        val keyLabel: String,
        val keyHint: String
    ) {
        GEMINI("Gemini",  "Google AI API Key", "AIza…"),
        OPENAI("ChatGPT", "OpenAI API Key",    "sk-…"),
        CLAUDE("Claude",  "Anthropic API Key", "sk-ant-…"),
        GROK(  "Grok",    "xAI API Key",       "xai-…")
    }

    companion object {
        const val MODEL_FILE_NAME = "model.bin"
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

    private val _selectedProvider = MutableStateFlow(OnlineProvider.GEMINI)
    val selectedProvider: StateFlow<OnlineProvider> = _selectedProvider.asStateFlow()

    private val _providerKeys = MutableStateFlow<Map<OnlineProvider, String>>(emptyMap())
    val providerKeys: StateFlow<Map<OnlineProvider, String>> = _providerKeys.asStateFlow()

    private val _modelPath = MutableStateFlow("")
    val modelPath: StateFlow<String> = _modelPath.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _onlineReady = MutableStateFlow(false)
    val onlineReady: StateFlow<Boolean> = _onlineReady.asStateFlow()

    private val _localReady = MutableStateFlow(false)
    val localReady: StateFlow<Boolean> = _localReady.asStateFlow()

    private val _currentMode = MutableStateFlow<InferenceMode?>(null)
    val currentMode: StateFlow<InferenceMode?> = _currentMode.asStateFlow()

    private val _onlineError = MutableStateFlow<String?>(null)
    val onlineError: StateFlow<String?> = _onlineError.asStateFlow()

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
        val modelFile = File(appContext.filesDir, MODEL_FILE_NAME)
        if (modelFile.exists()) {
            _modelPath.value = modelFile.absolutePath
        }
    }

    // ── Provider selection ────────────────────────────────────────────────────

    fun selectProvider(provider: OnlineProvider) {
        _selectedProvider.value = provider
    }

    // ── Download ──────────────────────────────────────────────────────────────

    fun downloadModel(provider: OnlineProvider, apiKey: String) {
        val modelFile = File(appContext.filesDir, MODEL_FILE_NAME)
        _selectedProvider.value = provider
        if (apiKey.isNotBlank()) {
            _providerKeys.value = _providerKeys.value + (provider to apiKey)
        }
        _modelState.value = ModelState.DOWNLOADING
        _downloadProgress.value = 0f
        _downloadedBytes.value = 0L
        _totalBytes.value = 0L
        _statusMessage.value = "Starting download…"

        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (modelFile.exists()) modelFile.delete()

                val connection = openGoogleDriveConnection(MODEL_DOWNLOAD_URL)
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Server returned $responseCode")
                }

                val contentType = connection.contentType ?: ""
                if (contentType.contains("text/html", ignoreCase = true)) {
                    throw Exception("Google Drive returned a web page. Make sure the file is shared as 'Anyone with the link'.")
                }

                val total = connection.contentLengthLong
                _totalBytes.value = total
                var downloaded = 0L

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
                setup(provider, apiKey, modelFile.absolutePath)

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return@launch
                Log.e("ChatViewModel", "Download failed", e)
                _modelState.value = ModelState.ERROR
                _statusMessage.value = "Download failed: ${e.message}"
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _modelState.value = ModelState.NOT_LOADED
        _statusMessage.value = ""
    }

    fun resetToSetup() {
        llmInference?.close()
        llmInference = null
        generativeModel = null
        _onlineReady.value = false
        _localReady.value = false
        _modelState.value = ModelState.NOT_LOADED
        _statusMessage.value = ""
    }

    // ── Google Drive download helper ──────────────────────────────────────────

    private fun openGoogleDriveConnection(url: String): HttpURLConnection {
        val initial = URL(url).openConnection() as HttpURLConnection
        initial.connectTimeout = 15_000
        initial.readTimeout = 30_000
        initial.instanceFollowRedirects = true
        initial.connect()

        val contentType = initial.contentType ?: ""
        if (!contentType.contains("text/html", ignoreCase = true)) {
            return initial
        }

        val html = initial.inputStream.bufferedReader().readText()
        initial.disconnect()

        val confirmedUrl =
            Regex("""href="(https://drive\.usercontent\.google\.com/download[^"]+)"""")
                .find(html)
                ?.groupValues?.get(1)
                ?.replace("&amp;", "&")
                ?: throw Exception("Google Drive blocked the download. Open the share link in a browser, click 'Download anyway', copy that URL and use it as MODEL_DOWNLOAD_URL.")

        val confirmed = URL(confirmedUrl).openConnection() as HttpURLConnection
        confirmed.connectTimeout = 15_000
        confirmed.readTimeout = 30_000
        confirmed.instanceFollowRedirects = true
        confirmed.connect()
        return confirmed
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    fun setup(provider: OnlineProvider, apiKey: String, modelPath: String) {
        _selectedProvider.value = provider
        if (apiKey.isNotBlank()) {
            _providerKeys.value = _providerKeys.value + (provider to apiKey)
        }
        _modelPath.value = modelPath
        _modelState.value = ModelState.LOADING
        _statusMessage.value = "Setting up…"

        viewModelScope.launch(Dispatchers.IO) {
            _onlineReady.value = false
            _localReady.value = false
            generativeModel = null
            llmInference?.close()
            llmInference = null

            val errors = mutableListOf<String>()

            // Online mode
            if (apiKey.isNotBlank()) {
                try {
                    if (provider == OnlineProvider.GEMINI) {
                        generativeModel = GenerativeModel(
                            modelName = "gemini-3-flash-preview",
                            apiKey = apiKey
                        )
                    }
                    _onlineReady.value = true
                } catch (e: Exception) {
                    Log.e("ChatViewModel", "${provider.displayName} init failed", e)
                    errors.add("Online: ${e.message}")
                }
            }

            // Offline mode
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

            if (_onlineReady.value || _localReady.value) {
                val modes = buildList {
                    if (_onlineReady.value) add("${provider.displayName} (online)")
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
            isOnline() && _onlineReady.value -> InferenceMode.ONLINE
            _localReady.value               -> InferenceMode.LOCAL
            _onlineReady.value              -> InferenceMode.ONLINE
            else                            -> return
        }
        _currentMode.value = mode

        _messages.update { it + Message(content = text, role = Role.USER) }
        _messages.update { it + Message(content = "", role = Role.ASSISTANT, isStreaming = true, isLocal = mode == InferenceMode.LOCAL) }
        _isGenerating.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (mode) {
                    InferenceMode.ONLINE -> sendViaOnline(text)
                    InferenceMode.LOCAL  -> sendViaLocal(text)
                }
                finalizeStreaming()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Generation failed (mode=$mode)", e)
                if (mode == InferenceMode.ONLINE && _localReady.value) {
                    _onlineReady.value = false
                    _onlineError.value = "${_selectedProvider.value.displayName} failed: ${e.message ?: "Unknown error"}"
                    _currentMode.value = InferenceMode.LOCAL
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

    fun clearChat() {
        _messages.value = emptyList()
    }

    fun clearOnlineError() {
        _onlineError.value = null
    }

    // ── Online inference ──────────────────────────────────────────────────────

    private suspend fun sendViaOnline(text: String) {
        val provider = _selectedProvider.value
        val apiKey = _providerKeys.value[provider]
            ?: throw IllegalStateException("No API key for ${provider.displayName}")

        when (provider) {
            OnlineProvider.GEMINI -> sendViaGeminiSdk(text)
            OnlineProvider.OPENAI -> streamOpenAiCompatible(
                apiKey  = apiKey,
                baseUrl = "https://api.openai.com/v1/chat/completions",
                model   = "gpt-4o-mini"
            )
            OnlineProvider.CLAUDE -> streamAnthropic(apiKey)
            OnlineProvider.GROK   -> streamOpenAiCompatible(
                apiKey  = apiKey,
                baseUrl = "https://api.x.ai/v1/chat/completions",
                model   = "grok-3-mini"
            )
        }
    }

    private suspend fun sendViaGeminiSdk(text: String) {
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

    private suspend fun streamOpenAiCompatible(apiKey: String, baseUrl: String, model: String) {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", buildApiMessages())
            put("stream", true)
            put("max_tokens", 1024)
        }

        val connection = URL(baseUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.outputStream.use { it.write(body.toString().toByteArray()) }

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            throw Exception("${connection.responseCode}: $error")
        }

        connection.inputStream.bufferedReader().use { reader ->
            while (currentCoroutineContext().isActive) {
                val line = reader.readLine() ?: break
                val data = line.removePrefix("data: ")
                if (data.isBlank() || data == "[DONE]") continue
                try {
                    val content = JSONObject(data)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("delta")
                        .optString("content", "")
                    if (content.isNotEmpty()) appendToken(content)
                } catch (e: Exception) { /* skip malformed SSE lines */ }
            }
        }
    }

    private suspend fun streamAnthropic(apiKey: String) {
        val body = JSONObject().apply {
            put("model", "claude-haiku-4-5-20251001")
            put("messages", buildApiMessages())
            put("max_tokens", 1024)
            put("stream", true)
        }

        val connection = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("x-api-key", apiKey)
        connection.setRequestProperty("anthropic-version", "2023-06-01")
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.outputStream.use { it.write(body.toString().toByteArray()) }

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            val error = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            throw Exception("Anthropic ${connection.responseCode}: $error")
        }

        connection.inputStream.bufferedReader().use { reader ->
            while (currentCoroutineContext().isActive) {
                val line = reader.readLine() ?: break
                val data = line.removePrefix("data: ")
                if (data.isBlank()) continue
                try {
                    val json = JSONObject(data)
                    if (json.getString("type") == "content_block_delta") {
                        val token = json.getJSONObject("delta").optString("text", "")
                        if (token.isNotEmpty()) appendToken(token)
                    }
                } catch (e: Exception) { /* skip malformed SSE lines */ }
            }
        }
    }

    private fun buildApiMessages(): JSONArray {
        val arr = JSONArray()
        for (msg in _messages.value.dropLast(1)) {
            if (msg.content.isBlank()) continue
            arr.put(JSONObject().apply {
                put("role", if (msg.role == Role.USER) "user" else "assistant")
                put("content", msg.content)
            })
        }
        return arr
    }

    // ── Local inference ───────────────────────────────────────────────────────

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
