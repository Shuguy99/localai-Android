package dev.localai.agent.inference

import android.util.Log

/**
 * JNI surface. Keep the names in sync with app/src/main/cpp/llama_jni.cpp.
 * Every entry point is static so the native symbols are stable and cheap to bind.
 */
object LlamaBridge {

    interface TokenSink {
        fun onToken(text: String)
    }

    private const val TAG = "LlamaBridge"

    @Volatile
    private var loadError: String? = null

    @Volatile
    private var attempted = false

    @Synchronized
    fun ensureLoaded(): String? {
        if (attempted) return loadError
        attempted = true
        loadError = try {
            System.loadLibrary("localai")
            val version = backendInit()
            Log.i(TAG, "llama.cpp backend ready: $version")
            null
        } catch (t: Throwable) {
            Log.e(TAG, "Native backend unavailable", t)
            t.message ?: "Native library failed to load"
        }
        return loadError
    }

    @JvmStatic
    external fun backendInit(): String

    @JvmStatic
    external fun loadModel(
        path: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
        nBatch: Int,
    ): Long

    @JvmStatic
    external fun freeModel(handle: Long)

    /**
     * Blocking generation. Returns the number of generated tokens, or
     * -1 when cancelled, -2 on an internal error, -3 when the prompt does not fit.
     */
    @JvmStatic
    external fun generate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        stopSequences: Array<String>,
        sink: TokenSink?,
    ): Int

    @JvmStatic
    external fun cancel(handle: Long)

    @JvmStatic
    external fun tokenCount(handle: Long, text: String): Int

    @JvmStatic
    external fun modelInfo(handle: Long): String
}
