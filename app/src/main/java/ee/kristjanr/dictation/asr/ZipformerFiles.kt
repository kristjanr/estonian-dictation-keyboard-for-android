package ee.kristjanr.dictation.asr

/** Absolute paths to the four files a sherpa-onnx transducer needs. */
data class ZipformerFiles(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
)
