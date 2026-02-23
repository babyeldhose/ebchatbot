package com.chatbot.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chatbot.app.data.Message
import com.chatbot.app.data.Role
import com.chatbot.app.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val modelState by vm.modelState.collectAsState()
    val messages by vm.messages.collectAsState()
    val isGenerating by vm.isGenerating.collectAsState()
    val apiKey by vm.apiKey.collectAsState()
    val modelPath by vm.modelPath.collectAsState()
    val statusMessage by vm.statusMessage.collectAsState()
    val currentMode by vm.currentMode.collectAsState()
    val geminiReady by vm.geminiReady.collectAsState()
    val localReady by vm.localReady.collectAsState()
    val downloadProgress by vm.downloadProgress.collectAsState()
    val downloadedBytes by vm.downloadedBytes.collectAsState()
    val totalBytes by vm.totalBytes.collectAsState()
    val geminiError by vm.geminiError.collectAsState()

    AnimatedContent(targetState = modelState, label = "screen") { state ->
        when (state) {
            ChatViewModel.ModelState.NOT_LOADED,
            ChatViewModel.ModelState.ERROR -> {
                ModelSetupScreen(
                    initialApiKey = apiKey,
                    initialModelPath = modelPath,
                    errorMessage = if (state == ChatViewModel.ModelState.ERROR) statusMessage else null,
                    onSetup = { key, path -> vm.setup(key, path) },
                    onDownload = { key -> vm.downloadModel(key) }
                )
            }

            ChatViewModel.ModelState.DOWNLOADING -> {
                DownloadScreen(
                    progress = downloadProgress,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    onCancel = { vm.cancelDownload() }
                )
            }

            ChatViewModel.ModelState.LOADING -> {
                LoadingScreen()
            }

            ChatViewModel.ModelState.READY -> {
                ChatInterface(
                    messages = messages,
                    isGenerating = isGenerating,
                    currentMode = currentMode,
                    geminiReady = geminiReady,
                    localReady = localReady,
                    geminiError = geminiError,
                    onSend = { vm.sendMessage(it) },
                    onClear = { vm.clearChat() },
                    onBack = { vm.resetToSetup() },
                    onDismissGeminiError = { vm.clearGeminiError() }
                )
            }
        }
    }
}

// ─── Download screen ──────────────────────────────────────────────────────────

@Composable
fun DownloadScreen(
    progress: Float,
    downloadedBytes: Long,
    totalBytes: Long,
    onCancel: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Downloading Model", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Gemma 2B · INT8 · ~1.3 GB",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))

            if (totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            } else {
                // Unknown total — show indeterminate bar
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatBytes(downloadedBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (totalBytes > 0) {
                    Text(
                        "${(progress * 100).toInt()}%  ·  ${formatBytes(totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Download will resume if interrupted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "${bytes / 1_024} KB"
    bytes < 1_073_741_824 -> "${"%.1f".format(bytes / 1_048_576f)} MB"
    else -> "${"%.2f".format(bytes / 1_073_741_824f)} GB"
}

// ─── Setup screen ─────────────────────────────────────────────────────────────

@Composable
fun ModelSetupScreen(
    initialApiKey: String,
    initialModelPath: String,
    errorMessage: String?,
    onSetup: (apiKey: String, modelPath: String) -> Unit,
    onDownload: (apiKey: String) -> Unit
) {
    var apiKey by remember { mutableStateOf(initialApiKey) }
    var keyVisible by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val modelFile = remember { File(context.filesDir, ChatViewModel.MODEL_FILE_NAME) }
    val modelExists = modelFile.exists()
    val modelSizeText = if (modelExists) formatBytes(modelFile.length()) else null

    // If model was pre-downloaded, use its path; otherwise empty (Gemini-only is valid too)
    val effectiveModelPath = if (modelExists) modelFile.absolutePath else initialModelPath

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("✨", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(12.dp))
        Text("EB's Chat", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Online when connected · Offline when not — configure one or both.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        // ── Online section ────────────────────────────────────────────────
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🌐  Online Mode", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Uses Gemini API — requires internet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Gemini API Key") },
                    placeholder = { Text("AIza…") },
                    leadingIcon = { Icon(Icons.Default.Key, null) },
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Get a free key at aistudio.google.com",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Offline section ───────────────────────────────────────────────
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("📱  Offline Mode", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Uses on-device Gemma — works without internet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                if (modelExists) {
                    // Model is ready
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("✓", color = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    "Model ready",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                if (modelSizeText != null) {
                                    Text(
                                        "$modelSizeText · ${ChatViewModel.MODEL_FILE_NAME}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Model not downloaded yet
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Gemma 2B INT8 · ~1.3 GB",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Will be saved to private app storage — no storage permission needed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onDownload(apiKey.trim()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Download Model")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        AnimatedVisibility(visible = errorMessage != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "❌  $errorMessage",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { onSetup(apiKey.trim(), effectiveModelPath) },
            modifier = Modifier.fillMaxWidth(),
            enabled = apiKey.isNotBlank() || modelExists
        ) {
            Text("Setup")
        }
    }
}

// ─── Loading screen ───────────────────────────────────────────────────────────

@Composable
fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(20.dp))
            Text("Setting up…", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Connecting to Gemini and/or loading local model.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─── Chat interface ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInterface(
    messages: List<Message>,
    isGenerating: Boolean,
    currentMode: ChatViewModel.InferenceMode?,
    geminiReady: Boolean,
    localReady: Boolean,
    geminiError: String?,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onDismissGeminiError: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onBack)

    if (geminiError != null) {
        AlertDialog(
            onDismissRequest = onDismissGeminiError,
            title = { Text("Gemini unavailable") },
            text = { Text("Switched to offline mode.\n\n$geminiError") },
            confirmButton = {
                TextButton(onClick = onDismissGeminiError) { Text("OK") }
            }
        )
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) {
            scope.launch { listState.scrollToItem(messages.size - 1) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("EB's Chat")
                        currentMode?.let { mode ->
                            Text(
                                text = when (mode) {
                                    ChatViewModel.InferenceMode.GEMINI -> "🌐 Online · Gemini"
                                    ChatViewModel.InferenceMode.LOCAL  -> "📱 Offline · Local"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Delete, "Clear chat")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask anything…") },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (input.isNotBlank() && !isGenerating) {
                                    onSend(input.trim()); input = ""
                                }
                            }
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = {
                            if (input.isNotBlank() && !isGenerating) {
                                onSend(input.trim()); input = ""
                            }
                        },
                        enabled = !isGenerating && input.isNotBlank(),
                        modifier = Modifier.size(56.dp)
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send")
                        }
                    }
                }
            }
        }
    ) { padding ->

        if (messages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✨", style = MaterialTheme.typography.displayLarge)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        when {
                            currentMode == ChatViewModel.InferenceMode.GEMINI -> "Gemini ready!"
                            currentMode == ChatViewModel.InferenceMode.LOCAL  -> "Local model ready!"
                            else -> "Ready!"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Ask me anything.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
            }
        }
    }
}

// ─── Message bubble ───────────────────────────────────────────────────────────

@Composable
fun MessageBubble(message: Message) {
    val isUser = message.role == Role.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (!isUser) {
            Text(
                if (message.isLocal) "📱 Local" else "🌐 Gemini",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = if (isUser) 16.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            ),
            color = if (isUser)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (message.isStreaming && message.content.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        repeat(3) { i ->
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        RoundedCornerShape(50)
                                    )
                            )
                            if (i < 2) Spacer(Modifier.width(4.dp))
                        }
                    }
                } else {
                    Text(
                        text = message.content,
                        color = if (isUser)
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        if (message.isStreaming && message.content.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            LinearProgressIndicator(modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth())
        }
    }
}
