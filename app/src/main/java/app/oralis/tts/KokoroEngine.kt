package app.oralis.tts

import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.tasks.audio.texttospeech.SynthesisOptions
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeech
import com.google.mediapipe.tasks.audio.texttospeech.TextToSpeechOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

enum class AccelerationMode { AUTO, CPU }

/**
 * Kokoro-82M running through the MediaPipe Tasks Audio `TextToSpeech` API (LiteRT-LM runtime).
 *
 * - All native calls run on one dedicated thread.
 * - `speakingRate` is an engine-level option in this API (not per utterance), so the native
 *   instance is recreated when the rate changes (≈0.5–3 s; weights are memory-mapped).
 * - Delegate: AUTO tries the GPU first and falls back to the CPU if the GPU delegate cannot be
 *   created (same strategy as Google's official sample). The GPU-only bundle never falls back to
 *   CPU, because its vocoder is noisy on CPU.
 */
class KokoroEngine(
    private val modelFile: File,
    private val cpuCapable: Boolean,
    private val acceleration: AccelerationMode,
    private val cacheDir: File,
) : SpeechEngine {

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "kokoro-tts").apply { priority = Thread.NORM_PRIORITY } }
    private val dispatcher = thread.asCoroutineDispatcher()

    private var tts: TextToSpeech? = null
    private var currentRate: Float = Float.NaN
    private var cachedVoices: List<Voice>? = null

    @Volatile
    var activeDelegate: Delegate? = null
        private set

    @Volatile
    var lastLoadMs: Long = 0
        private set

    override val sampleRateHz: Int = SAMPLE_RATE

    /** Loads (or reloads for a new rate) the native engine. Must run on [dispatcher]. */
    private fun ensureEngine(rate: Float): TextToSpeech {
        val existing = tts
        if (existing != null && currentRate == rate) return existing
        existing?.close()
        tts = null
        if (!modelFile.isFile) throw SpeechEngineException(SpeechEngineException.Kind.MODEL_MISSING, "Model file missing")
        val start = SystemClock.elapsedRealtime()
        fun build(delegate: Delegate) = TextToSpeech.create(
            TextToSpeechOptions(
                baseOptions = BaseOptions.builder()
                    .setModelAssetPath(modelFile.absolutePath)
                    .setDelegate(delegate)
                    .build(),
                defaultVoiceId = null,
                defaultLocale = null,
                speakingRate = rate,
                targetBucket = TARGET_BUCKET,
                cacheDirectory = cacheDir.absolutePath,
            ),
        )

        val engine = try {
            when {
                !cpuCapable -> build(Delegate.GPU).also { activeDelegate = Delegate.GPU }
                acceleration == AccelerationMode.CPU -> build(Delegate.CPU).also { activeDelegate = Delegate.CPU }
                else -> try {
                    build(Delegate.GPU).also { activeDelegate = Delegate.GPU }
                } catch (e: Exception) {
                    Log.w(TAG, "GPU delegate unavailable, using CPU: ${e.message}")
                    build(Delegate.CPU).also { activeDelegate = Delegate.CPU }
                }
            }
        } catch (e: OutOfMemoryError) {
            throw SpeechEngineException(SpeechEngineException.Kind.OUT_OF_MEMORY, "Not enough memory to load the model", e)
        } catch (e: Exception) {
            val kind = if (!cpuCapable) SpeechEngineException.Kind.MODEL_INCOMPATIBLE else SpeechEngineException.Kind.INIT_FAILED
            throw SpeechEngineException(kind, e.message ?: "Engine initialization failed", e)
        }
        lastLoadMs = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "Kokoro loaded in ${lastLoadMs} ms (delegate=$activeDelegate, rate=$rate)")
        tts = engine
        currentRate = rate
        return engine
    }

    suspend fun load(rate: Float = 1f) = withContext(dispatcher) { ensureEngine(rate); Unit }

    override suspend fun voices(): List<Voice> = withContext(dispatcher) {
        cachedVoices ?: ensureEngine(if (currentRate.isNaN()) 1f else currentRate).listVoices()
            .map { Voice(it.voiceId, it.displayName, it.locale, it.sampleRateHz) }
            .also { cachedVoices = it }
    }

    override suspend fun synthesize(text: String, voiceId: String, locale: String?, speakingRate: Float): SynthesizedAudio =
        coroutineScope {
            val engine = withContext(dispatcher) { ensureEngine(speakingRate) }
            val job = async(dispatcher) {
                try {
                    engine.synthesize(text, SynthesisOptions(voiceId, locale))
                } catch (e: OutOfMemoryError) {
                    throw SpeechEngineException(SpeechEngineException.Kind.OUT_OF_MEMORY, "Out of memory during synthesis", e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw SpeechEngineException(SpeechEngineException.Kind.SYNTHESIS_FAILED, e.message ?: "Synthesis failed", e)
                }
            }
            val result = try {
                job.await()
            } catch (e: CancellationException) {
                // Abort the in-flight native inference right away (lock-free in the runtime).
                runCatching { engine.cancel() }
                throw e
            }
            SynthesizedAudio(result.samples, result.sampleRateHz)
        }

    /** Releases native memory (~0.35–0.95 GB). The engine can be loaded again later. */
    suspend fun release() = withContext(dispatcher) {
        tts?.close()
        tts = null
        currentRate = Float.NaN
    }

    fun shutdown() {
        runCatching { tts?.cancel() }
        thread.execute { runCatching { tts?.close() }; tts = null }
        thread.shutdown()
    }

    companion object {
        private const val TAG = "OralisKokoro"
        const val SAMPLE_RATE = 24_000
        /**
         * Phoneme bucket length per acoustic-model inference; 0 = adaptive (runtime default).
         * Measured with the real model (same text/voice): 0 → 98.6 s for 204 s of audio;
         * 64 (value used by Google's sample) → 139.9 s for 212 s, i.e. ~30% slower and with extra
         * mid-sentence breaks; 32 → 2.4× slower.
         */
        const val TARGET_BUCKET = 0
    }
}
