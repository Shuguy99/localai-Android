package dev.localai.agent.data.hf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HfException(
    val statusCode: Int,
    override val message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Minimal Hugging Face Hub client.
 *
 * - `GET /api/models?search=...&full=true` for discovery
 * - `GET /api/models/{id}?blobs=true` for file sizes
 * - `GET /{repo}/resolve/main/{file}?download=true` for content (handled in ModelDownloader)
 *
 * The token is attached only if the user configured one. Gated repos surface a
 * 401/403 with an actionable message instead of a generic network error.
 */
class HuggingFaceApi(
    private val client: OkHttpClient,
    private val tokenProvider: () -> String?,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun searchModels(query: String, ggufOnly: Boolean = true, limit: Int = 30): List<HfModel> {
        val url = "https://huggingface.co/api/models".toHttpUrl().newBuilder()
            .addQueryParameter("search", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("sort", "downloads")
            .addQueryParameter("direction", "-1")
            .addQueryParameter("full", "true")
            .apply { if (ggufOnly) addQueryParameter("filter", "gguf") }
            .build()

        val body = requestText(url.toString(), accept = "application/json")
        return json.decodeFromString(body)
    }

    suspend fun modelDetails(repoId: String): HfModel {
        val encoded = repoId.split('/').joinToString("/") { it }
        val url = "https://huggingface.co/api/models/$encoded".toHttpUrl().newBuilder()
            .addQueryParameter("blobs", "true")
            .build()
        val body = requestText(url.toString(), accept = "application/json")
        return json.decodeFromString(body)
    }

    fun downloadUrl(repoId: String, fileName: String): String {
        val builder = "https://huggingface.co".toHttpUrl().newBuilder()
        repoId.split('/').forEach { builder.addPathSegment(it) }
        builder.addPathSegment("resolve")
        builder.addPathSegment("main")
        fileName.split('/').forEach { builder.addPathSegment(it) }
        return builder.addQueryParameter("download", "true").build().toString()
    }

    fun authorize(request: Request.Builder): Request.Builder =
        tokenProvider()?.let { request.header("Authorization", "Bearer $it") } ?: request

    private suspend fun requestText(url: String, accept: String): String = withContext(Dispatchers.IO) {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < 3) {
            attempt++
            val request = authorize(
                Request.Builder()
                    .url(url)
                    .header("Accept", accept)
                    .header("User-Agent", USER_AGENT),
            ).build()

            try {
                val response = call(request)
                response.use { resp ->
                    if (resp.isSuccessful) {
                        return@withContext resp.body?.string().orEmpty()
                    }
                    val detail = resp.body?.string()?.take(400).orEmpty()
                    when (resp.code) {
                        401 -> throw HfException(401, "This repository needs authentication. Add an HF token in Settings.", null)
                        403 -> throw HfException(403, "Access denied. The model may be gated; accept its license on huggingface.co and use a token with access.", null)
                        404 -> throw HfException(404, "Not found on Hugging Face.", null)
                        429 -> {
                            lastError = HfException(429, "Rate limited by Hugging Face. Try again in a moment.", null)
                            delay(1_000L * attempt * attempt)
                            return@use
                        }
                        in 500..599 -> {
                            lastError = HfException(resp.code, "Hugging Face server error (${resp.code}).", null)
                            delay(800L * attempt)
                            return@use
                        }
                        else -> throw HfException(resp.code, "Hugging Face error ${resp.code}: $detail", null)
                    }
                }
            } catch (e: HfException) {
                if (e.statusCode !in listOf(429) && e.statusCode !in 500..599) throw e
                lastError = e
            } catch (e: IOException) {
                lastError = e
                delay(500L * attempt)
            }
        }
        throw lastError ?: IOException("Hugging Face request failed")
    }

    private suspend fun call(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    companion object {
        const val USER_AGENT = "LocalAiAgent/0.1 (Android; +https://huggingface.co)"
    }
}
