package dev.pocketqwen

/** Raw JNI entry points, implemented in app/src/main/cpp/qwen_jni.cpp. Use [LlamaEngine] instead. */
internal object LlamaBridge {
    init {
        System.loadLibrary("pocketqwen")
    }

    external fun nativeInit(nativeLibDir: String)
    external fun nativeSystemInfo(): String
    /** @return null on success, otherwise an error message. */
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): String?
    external fun nativeReset(systemPromptUtf8: ByteArray): String?
    external fun nativeStartTurn(userUtf8: ByteArray, temperature: Float, topP: Float, maxTokens: Int): String?
    /** @return next UTF-8 chunk (may be empty), or null when the reply is complete. */
    external fun nativeNextToken(): ByteArray?
    external fun nativeStopTurn()
    external fun nativeContextUsed(): Int
    external fun nativeContextSize(): Int
    external fun nativeUnload()
}
