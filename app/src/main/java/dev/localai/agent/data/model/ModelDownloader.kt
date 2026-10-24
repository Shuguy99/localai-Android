package dev.localai.agent.data.model

import dev.localai.agent.data.hf.HfException
import dev.localai.agent.data.hf.HuggingFaceApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

sealed interface DownloadEvent {
    data class Progress(val bytes: Long, val totalBytes: Long) : DownloadEvent
    data object Verifying : DownloadEvent
    data class Done(val file: File, val bytes: Long) : DownloadEvent
}

/**
 * Resumable model download with `Range` requests.
 *
 * A `.part` file survives process death and is resumed on the next attempt.
 * The final file is only renamed into place after the size and GGUF magic check.
 */
class ModelDownloader(
    private val client: OkHttpClient,
    private val api: HuggingFaceApi,
    private val store: ModelStore,
) {

    fun download(
        repoId: String,
        fileName: String,
        expectedSize: Long?,
    ): Flow<DownloadEvent> = flow {
        val destination = store.destinationFor(repoId, fileName)
        val part = File(destination.parentFile, destination.name + ".part")

        if (destination.exists() && destination.length() > 0) {
            emit(DownloadEvent.Done(destination, destination.length()))
            return@flow
        }

        if (expectedSize != null && expectedSize > 0 && !store.hasRoomFor(expectedSize)) {
            throw IOException(
                "Not enough free space. Need about ${expectedSize / (1024 * 1024)} MB plus headroom.",
            )
        }

        var existing = if (part.exists()) part.length() else 0L
        val url = api.downloadUrl(repoId, fileName)

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", HuggingFaceApi.USER_AGENT)
            .header("Accept", "application/octet-stream")
        if (existing > 0) requestBuilder.header("Range", "bytes=$existing-")
        val request = api.authorize(requestBuilder).build()

        var total = expectedSize?.takeIf { it > 0 } ?: -1L

        client.newCall(request).execute().use { response ->
                // A previous run may have downloaded every byte but died before the
                // final rename. The server then answers 416; finish the job locally.
                if (response.code == 416 && existing > 0 &&
                    expectedSize != null && existing == expectedSize
                ) {
                    if (hasGgufMagic(part)) {
                        if (destination.exists()) destination.delete()
                        if (part.renameTo(destination)) {
                            emit(DownloadEvent.Done(destination, destination.length()))
                            return@flow
                        }
                    }
                    throw IOException(
                        "Server rejected the resume request and the partial file is not a valid GGUF. " +
                            "Delete the .part file and download again.",
                    )
                }

                when {
                    response.code == 401 -> throw HfException(
                        401,
                        "This file requires authentication. Set an HF token in Settings.",
                    )
                    response.code == 403 -> throw HfException(
                        403,
                        "Access denied (gated model). Accept the license on huggingface.co and use a token.",
                    )
                    !response.isSuccessful -> throw IOException("HTTP ${response.code} while downloading $fileName")
                }

                val body = response.body ?: throw IOException("Empty response body")
                val appending = response.code == 206
                if (!appending) {
                    // Server ignored the Range header; restart cleanly.
                    existing = 0L
                    part.delete()
                }
                if (total <= 0) {
                    val len = body.contentLength()
                    if (len > 0) total = existing + len
                }

                var written = existing
                var lastEmit = 0L
                val buffer = ByteArray(256 * 1024)
                FileOutputStream(part, appending).use { out ->
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            written += read
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 200 || written == total) {
                                lastEmit = now
                                emit(DownloadEvent.Progress(written, total))
                            }
                        }
                    }
                    out.flush()
                    out.fd.sync()
                }

                emit(DownloadEvent.Verifying)

                if (total > 0 && part.length() != total) {
                    throw IOException(
                        "Incomplete download: got ${part.length()} of $total bytes. " +
                            "Tap download again to resume.",
                    )
                }
                if (!hasGgufMagic(part)) {
                    part.delete()
                    throw IOException("Downloaded file is not a GGUF model (bad magic). Deleted.")
                }

                if (destination.exists()) destination.delete()
                if (!part.renameTo(destination)) {
                    throw IOException("Could not move the downloaded file into place.")
                }
                emit(DownloadEvent.Done(destination, destination.length()))
            }
    }.flowOn(Dispatchers.IO)

    private fun hasGgufMagic(file: File): Boolean {
        if (file.length() < 4) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                String(magic, Charsets.US_ASCII) == "GGUF"
            }
        }.getOrDefault(false)
    }
}
