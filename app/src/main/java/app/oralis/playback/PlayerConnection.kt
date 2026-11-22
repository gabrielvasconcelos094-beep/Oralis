package app.oralis.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.oralis.data.db.AudioDao
import app.oralis.data.db.AudioEntity
import app.oralis.data.settings.SettingsRepository
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val audioId: String? = null,
    val title: String = "",
    val subtitle: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
)

/** UI-side bridge to [PlaybackService] through a Media3 MediaController. */
class PlayerConnection(
    private val context: Context,
    private val dao: AudioDao,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private var controller: MediaController? = null
    private val connectMutex = Mutex()
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh(player)
    }

    private suspend fun controller(): MediaController = connectMutex.withLock {
        controller?.let { if (it.isConnected) return it }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val c = suspendCancellableCoroutine<MediaController> { cont ->
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }, ContextCompat.getMainExecutor(context))
            cont.invokeOnCancellation { future.cancel(true) }
        }
        c.addListener(listener)
        controller = c
        refresh(c)
        c
    }

    /** Connects if a session already exists (e.g. after the UI was recreated). */
    fun attach() {
        scope.launch(Dispatchers.Main) { runCatching { controller() } }
    }

    private fun refresh(p: Player) {
        val item = p.currentMediaItem
        _state.update {
            it.copy(
                audioId = item?.mediaId,
                title = item?.mediaMetadata?.title?.toString() ?: "",
                subtitle = item?.mediaMetadata?.artist?.toString() ?: "",
                isPlaying = p.isPlaying,
                isBuffering = p.playbackState == Player.STATE_BUFFERING,
                positionMs = p.currentPosition.coerceAtLeast(0),
                durationMs = p.duration.takeIf { d -> d > 0 } ?: it.durationMs,
                speed = p.playbackParameters.speed,
            )
        }
        if (p.isPlaying) startTicker(p) else ticker?.cancel()
    }

    private fun startTicker(p: Player) {
        if (ticker?.isActive == true) return
        ticker = scope.launch(Dispatchers.Main) {
            while (isActive && p.isPlaying) {
                _state.update { it.copy(positionMs = p.currentPosition, durationMs = p.duration.takeIf { d -> d > 0 } ?: it.durationMs) }
                delay(500)
            }
        }
    }

    /** Plays [audio], resuming from its saved position unless it was finished. */
    suspend fun play(audio: AudioEntity) = withContext(Dispatchers.Main) {
        val path = audio.filePath ?: return@withContext
        if (!File(path).isFile) return@withContext
        val c = controller()
        val speed = settings.settings.first().playbackSpeed
        if (c.currentMediaItem?.mediaId == audio.id) {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
            c.play()
            return@withContext
        }
        val fresh = dao.get(audio.id) ?: audio
        val start = fresh.playbackPositionMs.takeIf { it > 0 && (fresh.durationMs == 0L || it < fresh.durationMs - 3_000) } ?: 0L
        val item = MediaItem.Builder()
            .setMediaId(audio.id)
            .setUri(Uri.fromFile(File(path)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(audio.title)
                    .setArtist("Oralis · ${audio.voiceName}")
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                    .build(),
            )
            .build()
        c.setMediaItem(item, start)
        c.playbackParameters = PlaybackParameters(speed)
        c.prepare()
        c.play()
        settings.setLastPlayed(audio.id)
    }

    fun togglePlay() = act { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } }
    fun seekBack() = act { it.seekBack() }
    fun seekForward() = act { it.seekForward() }
    fun seekTo(ms: Long) = act { it.seekTo(ms); _state.update { s -> s.copy(positionMs = ms) } }
    fun setSpeed(speed: Float) {
        act { it.playbackParameters = PlaybackParameters(speed) }
        scope.launch { settings.setPlaybackSpeed(speed) }
    }

    /** Stops playback if [audioId] is loaded (e.g. before deleting it). */
    fun stopIf(audioId: String) = act {
        if (it.currentMediaItem?.mediaId == audioId) {
            it.stop()
            it.clearMediaItems()
            _state.value = PlayerUiState()
        }
    }

    private fun act(block: (MediaController) -> Unit) {
        scope.launch(Dispatchers.Main) { runCatching { block(controller()) } }
    }

    companion object {
        val SPEEDS = listOf(0.75f, 0.85f, 1.0f, 1.1f, 1.25f, 1.5f, 1.75f, 2.0f)
    }
}
