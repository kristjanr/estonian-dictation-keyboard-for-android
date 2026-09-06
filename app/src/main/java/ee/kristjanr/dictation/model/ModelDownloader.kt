package ee.kristjanr.dictation.model

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches model files to app-private storage, resuming where it left off.
 *
 * Hundreds of megabytes over a phone connection will get interrupted, so every
 * file is written to a `.part` sibling and continued with a Range request
 * rather than restarted. Blocking; call it off the main thread.
 */
class ModelDownloader(
    private val baseUrl: String = HUGGING_FACE_ZIPFORMER,
) {

    /** Progress of the whole set, not of one file. */
    data class Progress(
        val fileName: String,
        val fileIndex: Int,
        val fileCount: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
    )

    sealed interface Result {
        data object Success : Result
        data class Failed(val reason: String) : Result
        data object Cancelled : Result
    }

    /**
     * Downloads every missing file into [targetDir].
     *
     * [candidates] lists, per file, the names to try in preference order — used
     * to prefer int8 weights and fall back to float32 when the repository has no
     * quantised build. [shouldContinue] is polled between chunks so the caller
     * can cancel.
     */
    fun download(
        targetDir: File,
        candidates: List<List<String>> = ModelStore.ZIPFORMER_CANDIDATES,
        shouldContinue: () -> Boolean = { true },
        onProgress: (Progress) -> Unit = {},
    ): Result {
        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            return Result.Failed("Cannot create ${targetDir.absolutePath}")
        }

        candidates.forEachIndexed { index, names ->
            if (!shouldContinue()) return Result.Cancelled

            if (names.any { File(targetDir, it).isFile }) return@forEachIndexed

            var lastError = "No candidate found for ${names.first()}"
            val downloaded = names.any { name ->
                when (val outcome = downloadOne(targetDir, name, index, candidates.size, shouldContinue, onProgress)) {
                    is Result.Success -> true
                    is Result.Cancelled -> return Result.Cancelled
                    is Result.Failed -> {
                        lastError = outcome.reason
                        false
                    }
                }
            }
            if (!downloaded) return Result.Failed(lastError)
        }

        return Result.Success
    }

    private fun downloadOne(
        targetDir: File,
        name: String,
        index: Int,
        count: Int,
        shouldContinue: () -> Boolean,
        onProgress: (Progress) -> Unit,
    ): Result {
        val target = File(targetDir, name)
        val part = File(targetDir, "$name.part")
        var done = part.length()

        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$baseUrl$name").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                if (done > 0) setRequestProperty("Range", "bytes=$done-")
            }

            val code = connection.responseCode
            when {
                code == HttpURLConnection.HTTP_PARTIAL -> Unit
                code == HttpURLConnection.HTTP_OK -> {
                    // Server ignored the range; start over so we do not splice
                    // the whole file onto a partial one.
                    done = 0
                    part.delete()
                }
                code == HttpURLConnection.HTTP_NOT_FOUND -> return Result.Failed("$name not published")
                code == HTTP_RANGE_NOT_SATISFIABLE -> {
                    // Already have every byte; just promote it.
                    return if (part.renameTo(target)) Result.Success
                    else Result.Failed("Cannot rename ${part.name}")
                }
                else -> return Result.Failed("HTTP $code for $name")
            }

            val total = done + connection.contentLengthLong.coerceAtLeast(0L)
            onProgress(Progress(name, index, count, done, total))

            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, done > 0).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var sinceReport = 0L
                    while (true) {
                        if (!shouldContinue()) return Result.Cancelled
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        sinceReport += read
                        if (sinceReport >= REPORT_EVERY_BYTES) {
                            sinceReport = 0
                            onProgress(Progress(name, index, count, done, total))
                        }
                    }
                    output.fd.sync()
                }
            }
        } catch (e: IOException) {
            // Keep the .part file: the next attempt resumes from here.
            return Result.Failed(e.message ?: "Network error on $name")
        } finally {
            connection?.disconnect()
        }

        if (!part.renameTo(target)) return Result.Failed("Cannot rename ${part.name}")
        onProgress(Progress(name, index, count, done, done))
        return Result.Success
    }

    companion object {
        /**
         * TalTech's Hugging Face repository. The model is MIT licensed, so
         * serving it from our own mirror later is permitted (RESEARCH.md §1).
         */
        const val HUGGING_FACE_ZIPFORMER =
            "https://huggingface.co/TalTechNLP/streaming-zipformer-large.et-en/resolve/main/"

        private const val TIMEOUT_MS = 30_000
        private const val BUFFER_BYTES = 1 shl 16
        private const val REPORT_EVERY_BYTES = 1L shl 20
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
    }
}
