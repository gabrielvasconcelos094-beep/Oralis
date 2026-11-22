package app.oralis.ui.create

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.oralis.AppContainer
import app.oralis.core.text.TextAnalysis
import app.oralis.core.text.TextStats
import app.oralis.data.settings.AppLanguage
import app.oralis.generation.ReadingPreset
import app.oralis.generation.StorageException
import app.oralis.model.ModelState
import app.oralis.tts.EngineState
import app.oralis.tts.ErrorMessages
import app.oralis.tts.SpeechEngineException
import app.oralis.tts.Voice
import app.oralis.tts.VoicePreviewPlayer
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CreateOptions(
    val language: AppLanguage = AppLanguage.PT_BR,
    val voiceId: String = "pf_dora",
    val preset: ReadingPreset = ReadingPreset.READING,
    val speed: Float = 1f,
    val bitrateKbps: Int = 96,
    val stripMarkdown: Boolean = true,
)

@OptIn(FlowPreview::class)
class CreateViewModel(private val c: AppContainer) : ViewModel() {

    val text = TextFieldState()
    val title = MutableStateFlow("")

    private val _options = MutableStateFlow(CreateOptions())
    val options: StateFlow<CreateOptions> = _options.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _starting = MutableStateFlow(false)
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    val modelState = c.modelRepository.state
    val engineState = c.engineHolder.state
    val previewState = c.previewPlayer.state

    val stats: StateFlow<TextStats> = snapshotFlow { text.text.toString() }
        .debounce(250)
        .distinctUntilChanged()
        .map { withContext(Dispatchers.Default) { TextAnalysis.stats(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TextStats(0, 0, 0))

    val voices: StateFlow<List<Voice>> = combine(c.engineHolder.voices, _options) { all, o ->
        all.filter { it.locale.equals(o.language.tag, ignoreCase = true) }
            .sortedWith(compareBy({ it.gender.ordinal }, { it.shortName }))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var previewJob: Job? = null

    init {
        viewModelScope.launch {
            val s = c.settings.settings.first()
            _options.value = CreateOptions(s.language, s.voiceFor(s.language), s.preset, s.preset.speakingRate, s.bitrateKbps, s.stripMarkdown)
        }
        // Discover voices from the installed model the first time (cached afterwards).
        viewModelScope.launch {
            val st = c.modelRepository.state.first { it is ModelState.Installed || it is ModelState.NotInstalled }
            if (st is ModelState.Installed && c.engineHolder.voices.value.isEmpty()) loadVoices()
        }
    }

    fun loadVoices() {
        viewModelScope.launch {
            c.engineHolder.warmUp().onFailure { e ->
                _message.value = (e as? SpeechEngineException)?.let(ErrorMessages::engine) ?: "Não foi possível carregar as vozes."
            }
        }
    }

    fun setLanguage(l: AppLanguage) = viewModelScope.launch {
        val s = c.settings.settings.first()
        _options.value = _options.value.copy(language = l, voiceId = s.voiceFor(l))
        c.settings.setLanguage(l)
    }

    fun setVoice(v: Voice) = viewModelScope.launch {
        _options.value = _options.value.copy(voiceId = v.id)
        c.settings.setVoice(_options.value.language, v.id)
    }

    fun setPreset(p: ReadingPreset) = viewModelScope.launch {
        _options.value = _options.value.copy(preset = p, speed = p.speakingRate)
        c.settings.setPreset(p)
    }

    fun setSpeed(s: Float) {
        _options.value = _options.value.copy(speed = (s * 20).let { kotlin.math.round(it) / 20f })
    }

    fun setBitrate(kbps: Int) = viewModelScope.launch {
        _options.value = _options.value.copy(bitrateKbps = kbps)
        c.settings.setBitrate(kbps)
    }

    fun consumeMessage() { _message.value = null }

    fun setText(value: String) = text.setTextAndPlaceCursorAtEnd(value)

    fun previewVoice(v: Voice) {
        val key = "voice:${v.id}"
        if (togglePreview(key)) return
        previewJob = viewModelScope.launch {
            c.previewPlayer.play(key, VoicePreviewPlayer.sampleText(v.locale), v, _options.value.speed)
        }
    }

    /** Reads the beginning of the user's own text with the chosen voice and speed. */
    fun previewText() {
        val key = "text"
        if (togglePreview(key)) return
        val v = voices.value.firstOrNull { it.id == _options.value.voiceId } ?: return
        val excerpt = excerpt(text.text.toString())
        if (excerpt.isBlank()) return
        previewJob = viewModelScope.launch { c.previewPlayer.play(key, excerpt, v, _options.value.speed) }
    }

    private fun togglePreview(key: String): Boolean {
        val st = previewState.value
        val active = (st is VoicePreviewPlayer.State.Playing && st.key == key) || (st is VoicePreviewPlayer.State.Preparing && st.key == key)
        previewJob?.cancel()
        c.previewPlayer.stop()
        return active
    }

    fun stopPreview() {
        previewJob?.cancel()
        c.previewPlayer.stop()
    }

    private fun excerpt(t: String): String {
        val trimmed = t.trim()
        if (trimmed.length <= 300) return trimmed
        val cut = trimmed.lastIndexOfAny(charArrayOf('.', '!', '?', '…'), 300)
        return if (cut > 80) trimmed.substring(0, cut + 1) else trimmed.substring(0, trimmed.lastIndexOf(' ', 300).coerceAtLeast(1))
    }

    fun importTxt(context: Context, uri: Uri) = viewModelScope.launch {
        runCatching {
            withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("empty")
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                decode(bytes) to name
            }
        }.onSuccess { (content, name) ->
            setText(content)
            if (title.value.isBlank() && name != null) title.value = name.substringBeforeLast('.')
        }.onFailure {
            _message.value = "Não foi possível ler o arquivo. Verifique se é um texto (.txt)."
        }
    }

    /** UTF-8 (with or without BOM), falling back to Windows-1252 for legacy files. */
    private fun decode(bytes: ByteArray): String {
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body)).toString()
        } catch (e: CharacterCodingException) {
            String(body, Charset.forName("windows-1252"))
        }
    }

    /** Returns the new audio id, or null when it could not start (message is set). */
    suspend fun generate(): String? {
        val content = text.text.toString()
        if (content.isBlank() || TextAnalysis.stats(content).words == 0) {
            _message.value = "Escreva, cole ou importe um texto primeiro."
            return null
        }
        if (c.modelRepository.state.value !is ModelState.Installed) {
            _message.value = "Instale a voz neural na tela inicial antes de gerar áudios."
            return null
        }
        val o = _options.value
        val voice = voices.value.firstOrNull { it.id == o.voiceId }
            ?: c.engineHolder.voices.value.firstOrNull { it.id == o.voiceId }
        if (voice == null) {
            _message.value = "Escolha uma voz."
            return null
        }
        stopPreview()
        _starting.value = true
        return try {
            c.generationRepository.start(
                title = title.value.trim(),
                text = content,
                languageTag = o.language.tag,
                voiceId = voice.id,
                voiceName = voice.shortName,
                speakingRate = o.speed,
                preset = o.preset,
                bitrateKbps = o.bitrateKbps,
                stripMarkdown = o.stripMarkdown,
            )
        } catch (e: StorageException) {
            _message.value = "Espaço insuficiente: este áudio precisa de cerca de ${app.oralis.ui.components.formatBytes(e.neededBytes)} " +
                "durante a geração (há ${app.oralis.ui.components.formatBytes(e.availableBytes)} livres)."
            null
        } finally {
            _starting.value = false
        }
    }

    val engineBusy: Boolean get() = c.engineHolder.busy
    fun isEngineLoading(s: EngineState) = s is EngineState.Loading

    override fun onCleared() {
        stopPreview()
    }
}
