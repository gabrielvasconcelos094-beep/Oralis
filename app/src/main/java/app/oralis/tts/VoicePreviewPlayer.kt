package app.oralis.tts

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Generates short voice samples locally with Kokoro and plays them.
 *
 * Samples are written once as 16-bit WAV files in the cache (per voice + rate + text) and played
 * with MediaPlayer: a streaming player with no buffer-size limits, unlike a static AudioTrack
 * holding the whole float buffer, which some devices refuse for multi-second samples.
 */
class VoicePreviewPlayer(
    private val holder: EngineHolder,
    private val cacheDir: File,
    /** True while a generation owns the engine (it can hold it for a long time). */
    private val isGenerating: () -> Boolean,
) {

    sealed interface State {
        data object Idle : State
        data class Preparing(val key: String, val loadingModel: Boolean) : State
        data class Playing(val key: String) : State
        data class Error(val key: String, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var player: MediaPlayer? = null

    /** Suspends until playback ends; cancel the calling coroutine to stop. */
    suspend fun play(key: String, text: String, voice: Voice, rate: Float) {
        stop()
        val file = cacheFile(voice.id, rate, text)
        try {
            if (!file.isFile) {
                if (isGenerating()) {
                    _state.value = State.Error(
                        key, "O motor de voz está gerando um áudio agora. As amostras voltam a funcionar quando a geração terminar.",
                    )
                    return
                }
                _state.value = State.Preparing(key, loadingModel = holder.state.value !is EngineState.Ready)
                val samples = holder.use(rate) { engine ->
                    engine.synthesize(text, voice.id, voice.locale, rate).samples
                }
                withContext(Dispatchers.IO) { writeWav(file, samples, KokoroEngine.SAMPLE_RATE) }
            }
            _state.value = State.Playing(key)
            playFile(file)
            _state.value = State.Idle
        } catch (e: CancellationException) {
            stop()
            _state.value = State.Idle
            throw e
        } catch (e: SpeechEngineException) {
            Log.e(TAG, "Preview synthesis failed", e)
            _state.value = State.Error(key, ErrorMessages.engine(e))
        } catch (e: Exception) {
            Log.e(TAG, "Preview playback failed", e)
            file.delete()
            _state.value = State.Error(key, "Não foi possível reproduzir a amostra (${e.javaClass.simpleName}).")
        }
    }

    fun stop() {
        player?.let { p -> runCatching { if (p.isPlaying) p.stop() } }
    }

    private suspend fun playFile(file: File) = withContext(Dispatchers.Main) {
        val mp = MediaPlayer()
        player = mp
        try {
            suspendCancellableCoroutine { cont ->
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { if (cont.isActive) cont.resumeWith(Result.success(Unit)) }
                mp.setOnErrorListener { _, what, extra ->
                    if (cont.isActive) cont.resumeWith(Result.failure(IllegalStateException("MediaPlayer error $what/$extra")))
                    true
                }
                mp.setOnPreparedListener { it.start() }
                mp.prepareAsync()
                cont.invokeOnCancellation { runCatching { mp.stop() } }
            }
        } finally {
            player = null
            mp.release()
        }
    }

    private fun cacheFile(voiceId: String, rate: Float, text: String): File {
        val h = text.hashCode().toUInt().toString(16)
        return File(cacheDir, "preview_${voiceId}_${(rate * 100).roundToInt()}_$h.wav")
    }

    companion object {
        private const val TAG = "OralisPreview"

        /** Writes mono 16-bit PCM WAV atomically. */
        fun writeWav(target: File, samples: FloatArray, sampleRate: Int) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            val dataBytes = samples.size * 2
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(0)
                val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
                header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                header.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
                header.put("data".toByteArray()).putInt(dataBytes)
                raf.write(header.array())
                val buf = java.nio.ByteBuffer.allocate(8192 * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                var i = 0
                while (i < samples.size) {
                    buf.clear()
                    val n = minOf(8192, samples.size - i)
                    for (k in 0 until n) buf.putShort((samples[i + k] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
                    raf.write(buf.array(), 0, n * 2)
                    i += n
                }
            }
            if (!tmp.renameTo(target)) throw java.io.IOException("Cannot write preview")
        }

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
