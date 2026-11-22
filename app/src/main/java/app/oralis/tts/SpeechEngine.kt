package app.oralis.tts

/** A voice reported by the engine at runtime (never hard-coded). */
data class Voice(
    val id: String,
    val displayName: String,
    val locale: String,
    val sampleRateHz: Int,
) {
    /** Kokoro convention: second letter of the id is f (female) or m (male). */
    val gender: VoiceGender
        get() = when (id.getOrNull(1)) {
            'f' -> VoiceGender.FEMALE
            'm' -> VoiceGender.MALE
            else -> VoiceGender.UNKNOWN
        }

    /** "Kokoro Emma (en-GB)" → "Emma". */
    val shortName: String
        get() = displayName.removePrefix("Kokoro ").substringBefore(" (").ifBlank {
            id.substringAfter('_').replaceFirstChar { it.titlecase() }
        }
}

enum class VoiceGender { FEMALE, MALE, UNKNOWN }

/** Mono float PCM in [-1, 1]. */
class SynthesizedAudio(val samples: FloatArray, val sampleRateHz: Int) {
    val durationMs: Long get() = samples.size * 1000L / sampleRateHz
}

/**
 * Abstraction over the on-device TTS runtime. Oralis ships a single implementation
 * ([KokoroEngine]); the interface exists so the generation pipeline can be tested on the JVM and
 * so another local engine could be added later without touching the pipeline.
 */
interface SpeechEngine {
    val sampleRateHz: Int

    suspend fun voices(): List<Voice>

    /**
     * Synthesizes [text] completely. Must be cancellable: coroutine cancellation aborts the
     * in-flight native inference.
     */
    suspend fun synthesize(text: String, voiceId: String, locale: String?, speakingRate: Float): SynthesizedAudio
}

class SpeechEngineException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { MODEL_MISSING, MODEL_INCOMPATIBLE, INIT_FAILED, OUT_OF_MEMORY, SYNTHESIS_FAILED }
}
