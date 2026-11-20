package dev.pocketqwen

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

data class GenerationSettings(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxTokens: Int = 1024,
)

/**
 * Thread-safe facade over llama.cpp. All native calls run on one dedicated thread,
 * because the native side keeps a single global model/context.
 */
class LlamaEngine private constructor(nativeLibDir: String) {

    private val llamaThread: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    init {
        LlamaBridge.nativeInit(nativeLibDir)
    }

    val systemInfo: String by lazy { LlamaBridge.nativeSystemInfo() }

    suspend fun load(path: String, nCtx: Int = 4096, nThreads: Int = 0) = withContext(llamaThread) {
        LlamaBridge.nativeLoad(path, nCtx, nThreads)?.let { throw IllegalStateException(it) }
    }

    suspend fun newChat(systemPrompt: String) = withContext(llamaThread) {
        LlamaBridge.nativeReset(systemPrompt.toByteArray())?.let { throw IllegalStateException(it) }
    }

    /** Streams the assistant reply. Cancelling the collector stops generation. */
    fun reply(userMessage: String, settings: GenerationSettings): Flow<String> = flow {
        LlamaBridge.nativeStartTurn(
            userMessage.toByteArray(), settings.temperature, settings.topP, settings.maxTokens
        )?.let { throw IllegalStateException(it) }
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val chunk = LlamaBridge.nativeNextToken() ?: break
                if (chunk.isNotEmpty()) emit(String(chunk, Charsets.UTF_8))
            }
        } finally {
            LlamaBridge.nativeStopTurn() // no-op if the turn already ended
        }
    }.flowOn(llamaThread)

    suspend fun contextUsage(): Pair<Int, Int> = withContext(llamaThread) {
        LlamaBridge.nativeContextUsed() to LlamaBridge.nativeContextSize()
    }

    suspend fun unload() = withContext(llamaThread) { LlamaBridge.nativeUnload() }

    companion object {
        @Volatile private var instance: LlamaEngine? = null

        fun get(context: Context): LlamaEngine = instance ?: synchronized(this) {
            instance ?: LlamaEngine(context.applicationInfo.nativeLibraryDir).also { instance = it }
        }
    }
}
