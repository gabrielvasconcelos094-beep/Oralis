package app.oralis.ui.player

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.oralis.AppContainer
import app.oralis.playback.PlayerConnection
import app.oralis.playback.SleepTimer
import app.oralis.ui.components.OralisHeader
import app.oralis.ui.components.PrimaryButton
import app.oralis.ui.components.QuietButton
import app.oralis.ui.components.SectionLabel
import app.oralis.ui.components.VSpace
import app.oralis.ui.components.formatClock
import app.oralis.ui.components.formatSpeed
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(container: AppContainer, nav: NavController, playId: String?) {
    val conn = container.playerConnection
    val state by conn.state.collectAsStateWithLifecycle()
    val timer by container.sleepTimer.state.collectAsStateWithLifecycle()
    val audioFlow = remember(state.audioId) {
        state.audioId?.let { container.database.audioDao().observe(it) } ?: kotlinx.coroutines.flow.flowOf(null)
    }
    val audio by audioFlow.collectAsStateWithLifecycle(null)
    val context = LocalContext.current
    var showTimer by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var timerRemaining by remember { mutableLongStateOf(0L) }

    LaunchedEffect(playId) {
        if (playId != null) container.database.audioDao().get(playId)?.takeIf { it.isPlayable }?.let { conn.play(it) }
    }
    LaunchedEffect(timer) {
        while (timer != null) {
            timerRemaining = timer!!.remainingMs(SystemClock.elapsedRealtime())
            delay(500)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        OralisHeader("", onBack = { nav.popBackStack() }) {
            val a = audio
            if (a != null && a.isPlayable) {
                IconButton(onClick = { runCatching { context.startActivity(container.exportManager.shareIntent(a)) } }) {
                    Icon(Icons.Outlined.IosShare, contentDescription = "Compartilhar MP3")
                }
            }
        }
        if (state.audioId == null) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Nada tocando agora.", style = MaterialTheme.typography.titleLarge)
                VSpace(8)
                QuietButton("Abrir biblioteca", onClick = { nav.navigate("library") }, accent = true)
            }
            return@Column
        }

        Column(Modifier.weight(1f).padding(horizontal = 28.dp), verticalArrangement = Arrangement.Center) {
            Text(state.title, style = MaterialTheme.typography.displaySmall, maxLines = 4)
            VSpace(10)
            Text(
                state.subtitle.removePrefix("Oralis · ").let { v -> audio?.let { "$v · ${if (it.languageTag.startsWith("pt")) "Português" else "English"}" } ?: v },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(Modifier.padding(horizontal = 24.dp)) {
            val duration = state.durationMs.coerceAtLeast(1)
            val shown = scrubbing ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            val sliderColors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onSurface,
                activeTrackColor = MaterialTheme.colorScheme.tertiary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
            )
            Slider(
                value = shown,
                onValueChange = { scrubbing = it },
                onValueChangeFinished = { scrubbing?.let { conn.seekTo((it * duration).toLong()) }; scrubbing = null },
                colors = sliderColors,
                thumb = {
                    Box(Modifier.size(16.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape))
                },
                track = { st ->
                    SliderDefaults.Track(
                        sliderState = st, modifier = Modifier.height(3.dp), colors = sliderColors,
                        thumbTrackGapSize = 0.dp, drawStopIndicator = null,
                    )
                },
                modifier = Modifier.semantics { contentDescription = "Posição no áudio" },
            )
            Row(Modifier.fillMaxWidth()) {
                val pos = (shown * duration).toLong()
                Text(formatClock(pos), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text("−" + formatClock(duration - pos), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            VSpace(20)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = conn::seekBack, modifier = Modifier.size(64.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Replay10, contentDescription = "Voltar 10 segundos", modifier = Modifier.size(34.dp))
                    }
                }
                Surface(onClick = conn::togglePlay, shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(84.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pausar" else "Reproduzir",
                            tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(42.dp),
                        )
                    }
                }
                IconButton(onClick = conn::seekForward, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Rounded.Forward10, contentDescription = "Avançar 10 segundos", modifier = Modifier.size(34.dp))
                }
            }
            Text(
                "Saltos de 10 s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )

            SectionLabel("Velocidade de reprodução")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerConnection.SPEEDS.forEach { s ->
                    FilterChip(
                        selected = kotlin.math.abs(state.speed - s) < 0.01f,
                        onClick = { conn.setSpeed(s) },
                        label = { Text(formatSpeed(s)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
            VSpace(12)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Bedtime, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (timer != null) "Timer de sono · ${formatClock(timerRemaining)}" else "Timer de sono",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                if (timer != null) QuietButton("Desligar", onClick = { container.sleepTimer.cancel() })
                QuietButton(if (timer != null) "Alterar" else "Definir", onClick = { showTimer = true }, accent = true)
            }
            VSpace(16)
        }
    }

    if (showTimer) {
        ModalBottomSheet(onDismissRequest = { showTimer = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            var custom by remember { mutableFloatStateOf(20f) }
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
                Text("Timer de sono", style = MaterialTheme.typography.titleLarge)
                VSpace(4)
                Text(
                    "A reprodução pausa sozinha no fim do tempo, com o volume diminuindo suavemente nos últimos 20 segundos. Nada é apagado.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VSpace(16)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SleepTimer.PRESETS.forEach { m ->
                        FilterChip(selected = false, onClick = { container.sleepTimer.start(m); showTimer = false }, label = { Text("$m min") })
                    }
                }
                SectionLabel("Personalizado")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = custom, onValueChange = { custom = it }, valueRange = 5f..240f, steps = 46,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.tertiary, activeTrackColor = MaterialTheme.colorScheme.tertiary),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("${custom.toInt()} min", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(72.dp))
                }
                VSpace(8)
                PrimaryButton("Iniciar timer de ${custom.toInt()} min", onClick = { container.sleepTimer.start(custom.toInt()); showTimer = false }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
