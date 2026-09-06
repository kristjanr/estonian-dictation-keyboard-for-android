package ee.kristjanr.dictation.model

import android.content.Context
import ee.kristjanr.dictation.asr.ZipformerFiles
import java.io.File

/**
 * Where model files live on the device and whether they are actually there.
 *
 * Everything sits in app-private storage: it needs no permissions, and it is
 * removed when the app is uninstalled, which is the right behaviour for ~600 MB
 * of weights the user did not consciously put anywhere.
 */
class ModelStore(context: Context) {

    private val root = File(context.filesDir, "models")

    val zipformerDir: File = File(root, ZIPFORMER_DIR)

    /**
     * Resolves the four transducer files, or null if the set is incomplete.
     *
     * Each part is looked up as int8 first: quantised files are what we want on
     * a phone, but whether TalTech ships them for the large model is still
     * unconfirmed (RESEARCH.md §7), so a float32 download has to work too.
     */
    fun zipformerFiles(): ZipformerFiles? {
        val encoder = resolve("encoder") ?: return null
        val decoder = resolve("decoder") ?: return null
        val joiner = resolve("joiner") ?: return null
        val tokens = File(zipformerDir, TOKENS).takeIf { it.isFile } ?: return null

        return ZipformerFiles(
            encoder = encoder.absolutePath,
            decoder = decoder.absolutePath,
            joiner = joiner.absolutePath,
            tokens = tokens.absolutePath,
        )
    }

    fun isZipformerInstalled(): Boolean = zipformerFiles() != null

    /** Total bytes currently on disk, for the setup screen. */
    fun installedBytes(): Long =
        zipformerDir.listFiles()?.sumOf { it.length() } ?: 0L

    fun deleteZipformer(): Boolean = zipformerDir.deleteRecursively()

    private fun resolve(part: String): File? =
        sequenceOf("$part.int8.onnx", "$part.onnx")
            .map { File(zipformerDir, it) }
            .firstOrNull { it.isFile && it.length() > 0 }

    companion object {
        const val ZIPFORMER_DIR = "streaming-zipformer-large.et-en"
        const val TOKENS = "tokens.txt"

        /** Names tried, in order, when downloading each transducer part. */
        val ZIPFORMER_CANDIDATES: List<List<String>> = listOf(
            listOf("encoder.int8.onnx", "encoder.onnx"),
            listOf("decoder.int8.onnx", "decoder.onnx"),
            listOf("joiner.int8.onnx", "joiner.onnx"),
            listOf(TOKENS),
        )
    }
}
