package dev.pocketqwen.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pocketqwen.ChatMessage
import dev.pocketqwen.ChatViewModel
import dev.pocketqwen.ModelCatalog
import dev.pocketqwen.Role
import java.io.File

@Composable
fun App(vm: ChatViewModel) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val colors = if (android.os.Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = colors) {
        var showModels by rememberSaveable { mutableStateOf(true) }
        // jump to the chat once a model finishes loading
        LaunchedEffect(vm.loadedModel) { if (vm.loadedModel != null) showModels = false }

        if (showModels || vm.loadedModel == null) {
            ModelsScreen(vm, onBack = if (vm.loadedModel != null) ({ showModels = false }) else null)
        } else {
            ChatScreen(vm, onOpenModels = { showModels = true })
        }

        vm.error?.let { msg ->
            AlertDialog(
                onDismissRequest = { vm.error = null },
                confirmButton = { TextButton(onClick = { vm.error = null }) { Text("OK") } },
                title = { Text("Something went wrong") },
                text = { Text(msg) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelsScreen(vm: ChatViewModel, onBack: (() -> Unit)?) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }
    onBack?.let { BackHandler(onBack = it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Default.Close, "Back to chat") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Download", style = MaterialTheme.typography.titleMedium)
            ModelCatalog.models.forEach { m ->
                val installed = vm.installedModels.any { it.name == m.fileName }
                val progress = vm.transfers[m.id]
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(m.title, style = MaterialTheme.typography.titleMedium)
                        Text(m.subtitle, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        when {
                            progress != null -> {
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${(progress * 100).toInt()}%  — keep the app open", Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { vm.cancelDownload(m) }) { Text("Pause") }
                                }
                            }
                            installed -> Text("Downloaded ✓", color = MaterialTheme.colorScheme.primary)
                            else -> Button(onClick = { vm.download(m) }) {
                                Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp)); Text("Download")
                            }
                        }
                    }
                }
            }

            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = vm.transfers["import"] == null) {
                Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Import .gguf from phone")
            }
            vm.transfers["import"]?.let { p ->
                if (p < 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
            }

            HorizontalDivider()
            Text("On this phone", style = MaterialTheme.typography.titleMedium)
            if (vm.installedModels.isEmpty()) {
                Text("No models yet. Download one above (Wi-Fi recommended).",
                    style = MaterialTheme.typography.bodyMedium)
            }
            vm.installedModels.forEach { f ->
                val isLoaded = f == vm.loadedModel
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("%.2f GB".format(f.length() / 1e9), style = MaterialTheme.typography.bodySmall)
                        }
                        if (!isLoaded) {
                            IconButton(onClick = { vm.deleteModel(f) }, enabled = !vm.loading) {
                                Icon(Icons.Default.Delete, "Delete")
                            }
                        }
                        Button(onClick = { vm.loadModel(f) }, enabled = !vm.loading && !isLoaded) {
                            Text(if (isLoaded) "Loaded" else "Load")
                        }
                    }
                }
            }
            if (vm.loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp)); Text("Loading model…")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(vm: ChatViewModel, onOpenModels: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // keep the newest text in view while streaming
    val lastLen = vm.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(vm.messages.size, lastLen) {
        if (vm.messages.isNotEmpty()) listState.scrollToItem(vm.messages.lastIndex, Int.MAX_VALUE)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.loadedModel?.nameWithoutExtension ?: "", maxLines = 1,
                            style = MaterialTheme.typography.titleMedium)
                        val tps = vm.tokensPerSecond?.let { "%.1f tok/s · ".format(it) } ?: ""
                        Text(tps + vm.contextInfo, style = MaterialTheme.typography.bodySmall)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.newChat() }) { Icon(Icons.Default.Add, "New chat") }
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Tune, "Settings") }
                    IconButton(onClick = onOpenModels) { Icon(Icons.Default.Memory, "Models") }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 5,
                )
                Spacer(Modifier.width(8.dp))
                if (vm.generating) {
                    FilledIconButton(onClick = { vm.stop() }) { Icon(Icons.Default.Stop, "Stop") }
                } else {
                    FilledIconButton(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        },
    ) { pad ->
        if (vm.messages.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Runs fully offline on your phone.\nSay hi 👋", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(vm.messages) { m -> Bubble(m, typing = vm.generating && m === vm.messages.last()) }
        }
    }

    if (showSettings) SettingsSheet(vm) { showSettings = false }
}

@Composable
private fun Bubble(m: ChatMessage, typing: Boolean) {
    val user = m.role == Role.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            SelectionContainer {
                Text(
                    if (m.text.isEmpty() && typing) "…" else m.text,
                    color = if (user) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(vm: ChatViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Chat settings", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = vm.systemPrompt, onValueChange = { vm.systemPrompt = it },
                label = { Text("System prompt (applies to new chats)") }, modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Text("Temperature: %.2f".format(vm.settings.temperature))
            Slider(
                value = vm.settings.temperature, valueRange = 0f..1.5f,
                onValueChange = { vm.settings = vm.settings.copy(temperature = it) },
            )
            Text("Max reply length: ${vm.settings.maxTokens} tokens")
            Slider(
                value = vm.settings.maxTokens.toFloat(), valueRange = 128f..2048f, steps = 14,
                onValueChange = { vm.settings = vm.settings.copy(maxTokens = it.toInt()) },
            )
            Button(onClick = { vm.newChat(); onDismiss() }) { Text("Start new chat with these settings") }
            Text("CPU features", style = MaterialTheme.typography.titleSmall)
            Text(vm.systemInfo, style = MaterialTheme.typography.bodySmall)
        }
    }
}
