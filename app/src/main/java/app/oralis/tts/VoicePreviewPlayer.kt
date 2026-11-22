package app.oralis.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Generates short voice samples locally with Kokoro and plays them with AudioTrack. Samples are
 * cached (float PCM) per voice + rate + text, so listening again is instant.
 */
class VoicePreviewPlayer(private val holder: EngineHolder, private val cacheDir: File) {

    sealed interface State {
        data object Idle : State
        data class Preparing(val key: String) : State
        data class Playing(val key: String) : State
        data class Error(val key: String, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var track: AudioTrack? = null

    /** Suspends until playback ends; cancel the calling coroutine to stop. */
    suspend fun play(key: String, text: String, voice: Voice, rate: Float) {
        stop()
        try {
            _state.value = State.Preparing(key)
            val samples = loadCached(voice.id, rate, text) ?: holder.use { engine ->
                engine.synthesize(text, voice.id, voice.locale, rate).samples
            }.also { store(voice.id, rate, text, it) }
            _state.value = State.Playing(key)
            playBlocking(samples, KokoroEngine.SAMPLE_RATE)
            _state.value = State.Idle
        } catch (e: CancellationException) {
            stop()
            _state.value = State.Idle
            throw e
        } catch (e: SpeechEngineException) {
            _state.value = State.Error(key, ErrorMessages.engine(e))
        } catch (e: Exception) {
            _state.value = State.Error(key, "Não foi possível reproduzir a amostra.")
        }
    }

    fun stop() {
        track?.let { runCatching { it.pause(); it.flush(); it.stop() } }
    }

    private suspend fun playBlocking(samples: FloatArray, rate: Int) = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) return@withContext
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(samples.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = t
        try {
            if (t.state != AudioTrack.STATE_INITIALIZED) throw IllegalStateException("AudioTrack not initialized")
            t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            t.play()
            val durationMs = samples.size * 1000L / rate
            var waited = 0L
            while (waited < durationMs + 150 && t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                delay(50)
                waited += 50
            }
        } finally {
            track = null
            runCatching { t.stop() }
            t.release()
        }
    }

    private fun cacheFile(voiceId: String, rate: Float, text: String): File {
        val h = text.hashCode().toUInt().toString(16)
        return File(cacheDir, "preview_${voiceId}_${(rate * 100).toInt()}_$h.f32")
    }

    private fun loadCached(voiceId: String, rate: Float, text: String): FloatArray? {
        val f = cacheFile(voiceId, rate, text)
        if (!f.isFile) return null
        val bytes = f.readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    private fun store(voiceId: String, rate: Float, text: String, samples: FloatArray) {
        runCatching {
            cacheDir.mkdirs()
            val bb = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(samples)
            cacheFile(voiceId, rate, text).writeBytes(bb.array())
        }
    }

    companion object {
        fun sampleText(locale: String): String = when {
            locale.startsWith("pt") -> "Olá. Esta é a minha voz no Oralis. Posso ler aulas, ensaios e histórias para você, com calma e clareza."
            locale.startsWith("en") -> "Hello. This is my voice in Oralis. I can read lessons, essays and stories for you, calmly and clearly."
            locale.startsWith("es") -> "Hola. Esta es mi voz en Oralis. Puedo leer clases, ensayos e historias para ti."
            locale.startsWith("fr") -> "Bonjour. Voici ma voix dans Oralis. Je peux lire des cours, des essais et des histoires."
            locale.startsWith("it") -> "Ciao. Questa è la mia voce in Oralis. Posso leggere lezioni, saggi e storie per te."
            else -> "Hello. This is my voice in Oralis."
        }
    }
}
