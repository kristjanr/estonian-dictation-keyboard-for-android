package ee.kristjanr.dictation.asr

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Builds the streaming recogniser.
 *
 * The parameters are the ones `alumae/kiirkirjutaja` runs in production against
 * this exact model (see PLAN.md for the original Python block), on the grounds
 * that a config already proven on live TV subtitling beats one we invent.
 */
object RecognizerFactory {

    /**
     * Trailing silence (seconds) that ends a segment when the recogniser is not
     * sure the audio contained speech.
     */
    const val RULE1_MIN_TRAILING_SILENCE = 5.0f

    /**
     * Trailing silence (seconds) that ends a segment after actual speech. This
     * is the rule that fires in practice, so it sets how long a dictating user
     * pauses before their words are committed. Kiirkirjutaja's 2 s suits
     * long-form broadcast; dictation may want less. Tune in Phase 0.
     */
    const val RULE2_MIN_TRAILING_SILENCE = 2.0f

    /**
     * Hard cap (seconds) on a single segment. 300 s effectively disables the
     * rule, which is what kiirkirjutaja wants for continuous broadcast audio;
     * a keyboard will hit an endpoint long before this.
     */
    const val RULE3_MIN_UTTERANCE_LENGTH = 300.0f

    /**
     * Beam search costs more CPU than greedy decoding but is what the reference
     * deployment uses, and dictation accuracy is the whole point here.
     */
    const val DECODING_METHOD = "modified_beam_search"

    fun config(
        files: ZipformerFiles,
        numThreads: Int = 2,
        provider: String = PROVIDER_CPU,
    ): OnlineRecognizerConfig = OnlineRecognizerConfig(
        featConfig = FeatureConfig(
            sampleRate = DictationSession.SAMPLE_RATE,
            featureDim = FEATURE_DIM,
        ),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = files.encoder,
                decoder = files.decoder,
                joiner = files.joiner,
            ),
            tokens = files.tokens,
            numThreads = numThreads,
            provider = provider,
            modelType = "zipformer2",
        ),
        endpointConfig = EndpointConfig(
            rule1 = EndpointRule(false, RULE1_MIN_TRAILING_SILENCE, 0.0f),
            rule2 = EndpointRule(true, RULE2_MIN_TRAILING_SILENCE, 0.0f),
            rule3 = EndpointRule(false, 0.0f, RULE3_MIN_UTTERANCE_LENGTH),
        ),
        enableEndpoint = true,
        decodingMethod = DECODING_METHOD,
    )

    /**
     * Loads the model. Blocking and slow (seconds), and it holds the weights in
     * memory for as long as the returned recogniser lives — call it off the main
     * thread and release it when dictation goes idle.
     */
    fun create(
        files: ZipformerFiles,
        numThreads: Int = 2,
        provider: String = PROVIDER_CPU,
    ): OnlineRecognizer = OnlineRecognizer(config = config(files, numThreads, provider))

    const val PROVIDER_CPU = "cpu"

    /**
     * Qualcomm NPU. Needs QNN-exported model files, not the stock ONNX ones, so
     * this is a Phase 3 experiment rather than a drop-in switch (see RESEARCH.md).
     */
    const val PROVIDER_QNN = "qnn"

    private const val FEATURE_DIM = 80
}
