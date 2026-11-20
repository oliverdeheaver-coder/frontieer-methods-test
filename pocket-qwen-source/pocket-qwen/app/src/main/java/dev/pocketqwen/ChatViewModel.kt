package dev.pocketqwen

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

enum class Role { USER, ASSISTANT }

class ChatMessage(val role: Role, text: String = "") {
    var text by mutableStateOf(text)
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app)
    private val engine by lazy { LlamaEngine.get(app) }

    // ---- model management ----
    var installedModels by mutableStateOf(store.installed())
        private set
    /** catalog id / "import" -> progress 0..1 (or -1 when size unknown) */
    val transfers = mutableStateMapOf<String, Float>()
    private val transferJobs = mutableMapOf<String, Job>()

    var loadedModel by mutableStateOf<File?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    // ---- chat ----
    val messages = mutableStateListOf<ChatMessage>()
    var generating by mutableStateOf(false)
        private set
    var tokensPerSecond by mutableStateOf<Float?>(null)
        private set
    var contextInfo by mutableStateOf("")
        private set

    var systemPrompt by mutableStateOf("You are a helpful, concise assistant.")
    var settings by mutableStateOf(GenerationSettings())

    private var genJob: Job? = null

    fun refresh() { installedModels = store.installed() }

    fun download(model: CatalogModel) {
        if (transferJobs[model.id]?.isActive == true) return
        transfers[model.id] = 0f
        transferJobs[model.id] = viewModelScope.launch {
            try {
                store.download(model) { done, total -> transfers[model.id] = done.toFloat() / total }
                refresh()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Download failed: ${e.message}. Tap download again to resume."
            } finally {
                transfers.remove(model.id)
            }
        }
    }

    fun cancelDownload(model: CatalogModel) {
        transferJobs.remove(model.id)?.cancel() // the .part file is kept, so it resumes later
    }

    fun import(uri: Uri) {
        transfers["import"] = -1f
        viewModelScope.launch {
            try {
                store.import(uri) { done, total ->
                    transfers["import"] = if (total > 0) done.toFloat() / total else -1f
                }
                refresh()
            } catch (e: Exception) {
                error = "Import failed: ${e.message}"
            } finally {
                transfers.remove("import")
            }
        }
    }

    fun deleteModel(file: File) {
        if (file == loadedModel) return
        store.delete(file)
        refresh()
    }

    fun loadModel(file: File) {
        if (loading) return
        stop()
        loading = true
        viewModelScope.launch {
            try {
                engine.unload()
                engine.load(file.absolutePath, nCtx = 4096)
                engine.newChat(systemPrompt)
                loadedModel = file
                messages.clear()
                updateContextInfo()
            } catch (e: Throwable) {
                loadedModel = null
                error = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }

    fun newChat() {
        stop()
        viewModelScope.launch {
            genJob?.join()
            try {
                engine.newChat(systemPrompt)
                messages.clear()
                tokensPerSecond = null
                updateContextInfo()
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || generating || loadedModel == null) return
        messages += ChatMessage(Role.USER, prompt)
        val reply = ChatMessage(Role.ASSISTANT)
        messages += reply
        generating = true
        tokensPerSecond = null
        genJob = viewModelScope.launch {
            var chunks = 0
            var start = 0L
            try {
                engine.reply(prompt, settings).collect { piece ->
                    if (chunks == 0) start = SystemClock.elapsedRealtime()
                    chunks++
                    reply.text += piece
                    val secs = (SystemClock.elapsedRealtime() - start) / 1000f
                    if (chunks > 1 && secs > 0) tokensPerSecond = (chunks - 1) / secs
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // user pressed stop
            } catch (e: Exception) {
                if (reply.text.isEmpty()) messages.remove(reply)
                error = e.message
            } finally {
                generating = false
                updateContextInfo()
            }
        }
    }

    fun stop() {
        genJob?.cancel()
    }

    private fun updateContextInfo() {
        viewModelScope.launch {
            val (used, size) = engine.contextUsage()
            contextInfo = if (size > 0) "$used / $size tokens" else ""
        }
    }

    val systemInfo: String get() = runCatching { engine.systemInfo }.getOrDefault("")
}
