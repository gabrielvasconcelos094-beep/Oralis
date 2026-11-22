package app.oralis.playback

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sleep timer state. The PlaybackService enforces it: during the last [FADE_MS] it lowers the
 * player volume progressively, then pauses playback and restores the volume. It only affects
 * playback (never generation, never files).
 */
class SleepTimer {
    data class State(val endsAtElapsed: Long, val totalMs: Long) {
        fun remainingMs(now: Long = SystemClock.elapsedRealtime()) = (endsAtElapsed - now).coerceAtLeast(0)
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    fun start(minutes: Int) {
        require(minutes in 1..24 * 60)
        val ms = minutes * 60_000L
        _state.value = State(SystemClock.elapsedRealtime() + ms, ms)
    }

    fun cancel() {
        _state.value = null
    }

    companion object {
        const val FADE_MS = 20_000L
        val PRESETS = listOf(15, 30, 45, 60, 90, 120)

        /** Linear fade over the last FADE_MS. */
        fun volumeFor(remainingMs: Long): Float =
            if (remainingMs >= FADE_MS) 1f else (remainingMs.toFloat() / FADE_MS).coerceIn(0f, 1f)
    }
}
