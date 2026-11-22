package app.oralis.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.oralis.MainActivity
import app.oralis.OralisApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background playback with a MediaSession: notification controls, lock-screen controls,
 * headset / Bluetooth buttons, audio focus (pauses on calls) and "becoming noisy" (pauses when
 * headphones are unplugged). Also persists the listening position and enforces the sleep timer.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var timerJob: Job? = null
    private val container get() = (application as OralisApp).container

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .build()

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "player"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val back = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setDisplayName("Voltar 10 segundos")
            .setPlayerCommand(Player.COMMAND_SEEK_BACK)
            .setSlots(CommandButton.SLOT_BACK)
            .build()
        val forward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_10)
            .setDisplayName("Avançar 10 segundos")
            .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setMediaButtonPreferences(listOf(back, forward))
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) savePosition()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) savePosition(ended = true)
            }
        })

        // Periodic position persistence while playing.
        scope.launch {
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) savePosition()
            }
        }
        // Sleep timer enforcement (with fade-out).
        scope.launch {
            container.sleepTimer.state.collect { t ->
                timerJob?.cancel()
                if (t == null) {
                    player.volume = 1f
                } else {
                    timerJob = launch {
                        while (isActive) {
                            val remaining = t.remainingMs(SystemClock.elapsedRealtime())
                            if (player.isPlaying) player.volume = SleepTimer.volumeFor(remaining)
                            if (remaining <= 0) {
                                player.pause()
                                player.volume = 1f
                                container.sleepTimer.cancel()
                                break
                            }
                            delay(200)
                        }
                    }
                }
            }
        }
    }

    private fun savePosition(ended: Boolean = false) {
        val id = player.currentMediaItem?.mediaId ?: return
        val pos = if (ended) 0L else player.currentPosition
        container.scope.launch {
            container.database.audioDao().savePosition(id, pos, System.currentTimeMillis())
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            savePosition()
            stopSelf()
        }
    }

    override fun onDestroy() {
        savePosition()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val SEEK_MS = 10_000L
    }
}
