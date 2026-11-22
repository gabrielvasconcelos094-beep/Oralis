package app.oralis.tts

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import app.oralis.data.settings.SettingsRepository
import app.oralis.model.ModelRepository
import app.oralis.model.ModelState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface EngineState {
    data object Unloaded : EngineState
    data object Loading : EngineState
    data class Ready(val delegate: String, val loadMs: Long) : EngineState
    data class Failed(val error: SpeechEngineException) : EngineState
}

/**
 * Process-wide owner of the Kokoro engine. Voice previews and generations share one native
 * instance through [use], which serializes access (the model is large; two instances would double
 * memory). The engine is released after a period without use to give memory back to the system.
 */
class EngineHolder(
    private val context: Context,
    private val models: ModelRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var engine: KokoroEngine? = null
    private var engineKey: String? = null
    private var idleJob: Job? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _voices = MutableStateFlow<List<Voice>>(emptyList())
    /** Voices reported by the installed model (cached on disk after the first load). */
    val voices: StateFlow<List<Voice>> = _voices.asStateFlow()

    @Volatile
    var busy: Boolean = false
        private set

    private val voicesCache = File(context.filesDir, "model/voices.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class CachedVoice(val id: String, val displayName: String, val locale: String, val sampleRateHz: Int)

    init {
        scope.launch {
            runCatching {
                val cached = json.decodeFromString(ListSerializer(CachedVoice.serializer()), voicesCache.readText())
                _voices.value = cached.map { Voice(it.id, it.displayName, it.locale, it.sampleRateHz) }
            }
            models.state.collect { s ->
                if (s !is ModelState.Installed) {
                    mutex.withLock { releaseLocked() }
                    if (s is ModelState.NotInstalled) _voices.value = emptyList()
                }
            }
        }
    }

    /** Runs [block] with exclusive access to a loaded engine. */
    suspend fun <T> use(block: suspend (KokoroEngine) -> T): T = mutex.withLock {
        idleJob?.cancel()
        busy = true
        try {
            val e = obtainLocked()
            block(e)
        } finally {
            busy = false
            scheduleIdleRelease()
        }
    }

    /** Loads the engine and discovers voices (used after installation). */
    suspend fun warmUp(): Result<List<Voice>> = runCatching { use { it.voices() } }

    suspend fun release() = mutex.withLock { releaseLocked() }

    private suspend fun obtainLocked(): KokoroEngine {
        val installed = models.state.first { it !is ModelState.Checking && it !is ModelState.Verifying }
        if (installed !is ModelState.Installed) {
            throw SpeechEngineException(SpeechEngineException.Kind.MODEL_MISSING, "Model not installed")
        }
        val prefs = settings.settings.first()
        val key = "${installed.path}|${prefs.acceleration}"
        engine?.let { if (engineKey == key) return it }
        releaseLocked()
        if (lowMemory()) Log.w(TAG, "Device reports low memory before loading the model")
        _state.value = EngineState.Loading
        val e = KokoroEngine(
            modelFile = File(installed.path),
            cpuCapable = installed.variant.cpuCapable,
            acceleration = prefs.acceleration,
            cacheDir = File(context.cacheDir, "litert").apply { mkdirs() },
        )
        try {
            e.load(1f)
            val v = e.voices()
            if (v.isEmpty()) throw SpeechEngineException(SpeechEngineException.Kind.MODEL_INCOMPATIBLE, "Model reported no voices")
            _voices.value = v
            runCatching {
                voicesCache.writeText(
                    json.encodeToString(ListSerializer(CachedVoice.serializer()), v.map { CachedVoice(it.id, it.displayName, it.locale, it.sampleRateHz) }),
                )
            }
        } catch (t: SpeechEngineException) {
            e.shutdown()
            _state.value = EngineState.Failed(t)
            throw t
        } catch (t: Throwable) {
            e.shutdown()
            val err = SpeechEngineException(SpeechEngineException.Kind.INIT_FAILED, t.message ?: "init failed", t)
            _state.value = EngineState.Failed(err)
            throw err
        }
        engine = e
        engineKey = key
        _state.value = EngineState.Ready(e.activeDelegate?.name ?: "?", e.lastLoadMs)
        return e
    }

    private fun releaseLocked() {
        engine?.shutdown()
        engine = null
        engineKey = null
        if (_state.value !is EngineState.Failed) _state.value = EngineState.Unloaded
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            mutex.withLock { releaseLocked() }
        }
    }

    private fun lowMemory(): Boolean {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.lowMemory || info.availMem < 700L * 1024 * 1024
    }

    /** Total device RAM, used to warn users of very low-end devices. */
    fun totalMemoryBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem
    }

    companion object {
        private const val TAG = "OralisEngine"
        private const val IDLE_RELEASE_MS = 3 * 60_000L
    }
}
