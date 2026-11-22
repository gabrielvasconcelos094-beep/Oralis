package app.oralis.ui.generation

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.oralis.AppContainer
import app.oralis.data.db.AudioStatus
import app.oralis.generation.GenerationPhase
import app.oralis.generation.LiveGeneration
import app.oralis.ui.Routes
import app.oralis.ui.components.Hairline
import app.oralis.ui.components.OralisHeader
import app.oralis.ui.components.PrimaryButton
import app.oralis.ui.components.QuietButton
import app.oralis.ui.components.SectionLabel
import app.oralis.ui.components.VSpace
import app.oralis.ui.components.formatBytes
import app.oralis.ui.components.formatClock
import app.oralis.ui.components.formatDuration
import app.oralis.ui.components.formatInt
import app.oralis.ui.components.formatSpeed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun GenerationScreen(container: AppContainer, nav: NavController, audioId: String) {
    val audioFlow = remember(audioId) { container.database.audioDao().observe(audioId) }
    val audio by audioFlow.collectAsStateWithLifecycle(null)
    val liveAll by container.generationRepository.live.collectAsStateWithLifecycle()
    val live = liveAll?.takeIf { it.audioId == audioId }
    val engine by container.engineHolder.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmCancel by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }

    val a = audio
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OralisHeader("", onBack = { nav.popBackStack() })
        if (a == null) return@Column
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Text(a.title, style = MaterialTheme.typography.headlineLarge)
            VSpace(6)
            Text(
                "${a.voiceName} · ${if (a.languageTag.startsWith("pt")) "Português (Brasil)" else "English (UK)"} · ${formatSpeed(a.speakingRate)} · ${a.bitrateKbps} kbps",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(40)

            val (label, fraction) = phaseOf(a.status, live)
            Text(label, style = MaterialTheme.typography.titleLarge)
            VSpace(16)
            when {
                a.status == AudioStatus.READY -> LinearProgressIndicator(progress = { 1f }, modifier = bar(), color = MaterialTheme.colorScheme.tertiary, trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
                !a.isActive -> {}
                fraction != null -> LinearProgressIndicator(progress = { fraction }, modifier = bar(), color = MaterialTheme.colorScheme.tertiary, trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
                else -> LinearProgressIndicator(modifier = bar(), color = MaterialTheme.colorScheme.tertiary, trackColor = MaterialTheme.colorScheme.outlineVariant)
            }

            if (a.isActive && live != null) {
                VSpace(14)
                Row {
                    Text(
                        if (live.phase == GenerationPhase.SYNTHESIZING && live.segmentsTotal > 0)
                            "Trecho ${formatInt(live.segmentsDone)} de ${formatInt(live.segmentsTotal)}" else "",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                    )
                    fraction?.let { Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.titleMedium) }
                }
                VSpace(24)
                Stat("Tempo decorrido", a.generationStartedAt?.let { formatClock(now - it) } ?: "—")
                eta(live)?.let { Stat("Tempo restante estimado", "≈ ${formatDuration(it)}") }
                if (live.audioMsSynthesized > 0) Stat("Áudio gerado nesta sessão", formatDuration(live.audioMsSynthesized))
                live.synthStartedAtElapsed?.let { start ->
                    val wall = SystemClock.elapsedRealtime() - start
                    if (live.audioMsSynthesized > 0 && wall > 5_000) {
                        val speed = live.audioMsSynthesized.toDouble() / wall
                        Stat("Velocidade", "%.1f× o tempo real".format(app.oralis.ui.components.PtBr, speed))
                    }
                }
                (engine as? app.oralis.tts.EngineState.Ready)?.let { Stat("Acelerador", if (it.delegate == "GPU") "GPU" else "CPU") }
                if (live.thermalPause) {
                    VSpace(12)
                    Text(
                        "O aparelho está muito quente. A geração foi pausada e continuará sozinha quando ele esfriar.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                VSpace(24)
                app.oralis.ui.components.BatteryHint()
                VSpace(16)
                Text(
                    "Você pode sair desta tela ou bloquear o aparelho: a geração continua em segundo plano e cada trecho concluído fica salvo.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (a.status == AudioStatus.QUEUED) {
                VSpace(16)
                Text("Aguardando a vez na fila de geração.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (a.status == AudioStatus.READY) {
                VSpace(24)
                Stat("Duração", formatClock(a.durationMs))
                Stat("Tamanho", formatBytes(a.fileSizeBytes))
                if (a.generationStartedAt != null && a.generationFinishedAt != null) {
                    Stat("Tempo de geração", formatDuration(a.generationFinishedAt - a.generationStartedAt))
                }
            }
            if (a.status == AudioStatus.FAILED || a.status == AudioStatus.INTERRUPTED) {
                VSpace(12)
                Text(
                    a.errorMessage ?: "A geração foi interrompida antes de terminar. Os trechos já gerados foram preservados.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (a.segmentsTotal > 0) {
                    VSpace(8)
                    Text(
                        "Progresso salvo: ${formatInt(a.segmentsDone)} de ${formatInt(a.segmentsTotal)} trechos.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (a.status == AudioStatus.CANCELLED) {
                VSpace(12)
                Text("Geração cancelada. Os arquivos temporários foram removidos.", style = MaterialTheme.typography.bodyLarge)
            }
            VSpace(24)
        }

        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
            when (a.status) {
                AudioStatus.READY -> {
                    PrimaryButton("Ouvir agora", icon = Icons.Rounded.PlayArrow, modifier = Modifier.fillMaxWidth(), onClick = {
                        scope.launch { container.playerConnection.play(a) }
                        nav.navigate(Routes.PLAYER) { popUpTo(Routes.HOME) }
                    })
                    QuietButton("Ver na biblioteca", onClick = { nav.navigate(Routes.LIBRARY) { popUpTo(Routes.HOME) } }, modifier = Modifier.fillMaxWidth())
                }
                AudioStatus.QUEUED, AudioStatus.GENERATING -> QuietButton("Cancelar geração", onClick = { confirmCancel = true }, modifier = Modifier.fillMaxWidth())
                AudioStatus.FAILED, AudioStatus.INTERRUPTED -> {
                    PrimaryButton("Retomar", modifier = Modifier.fillMaxWidth(), onClick = { scope.launch { container.generationRepository.resume(a.id) } })
                    QuietButton("Descartar", onClick = {
                        scope.launch { container.generationRepository.cancel(a.id) }
                    }, modifier = Modifier.fillMaxWidth())
                }
                AudioStatus.CANCELLED -> QuietButton("Voltar ao início", onClick = { nav.popBackStack(Routes.HOME, false) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancelar a geração?") },
            text = { Text("O áudio parcial e os trechos já gerados serão descartados.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancel = false
                    scope.launch { container.generationRepository.cancel(audioId) }
                }) { Text("Cancelar geração", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Continuar") } },
        )
    }
}

private fun bar() = Modifier.fillMaxWidth().height(4.dp)

/** Phase label and a determinate fraction only when it can be measured. */
private fun phaseOf(status: AudioStatus, live: LiveGeneration?): Pair<String, Float?> = when (status) {
    AudioStatus.QUEUED -> "Na fila" to null
    AudioStatus.READY -> "Concluído" to 1f
    AudioStatus.FAILED -> "Erro" to null
    AudioStatus.CANCELLED -> "Cancelado" to null
    AudioStatus.INTERRUPTED -> "Interrompido" to null
    AudioStatus.GENERATING -> when (live?.phase) {
        null, GenerationPhase.PREPARING -> "Preparando" to null
        GenerationPhase.LOADING_MODEL -> "Inicializando o modelo" to null
        GenerationPhase.PROCESSING_TEXT -> "Processando o texto" to null
        GenerationPhase.SYNTHESIZING -> "Gerando áudio" to (if (live.segmentsTotal > 0) live.segmentsDone.toFloat() / live.segmentsTotal else null)
        GenerationPhase.ENCODING -> "Codificando MP3" to live.encodeFraction
        GenerationPhase.FINALIZING -> "Finalizando e verificando o arquivo" to null
        GenerationPhase.DONE -> "Concluído" to 1f
    }
}

/** ETA from this session's measured pace; hidden until enough data exists. */
private fun eta(l: LiveGeneration): Long? {
    if (l.phase != GenerationPhase.SYNTHESIZING) return null
    val start = l.synthStartedAtElapsed ?: return null
    val done = l.segmentsDone - l.segmentsDoneAtSynthStart
    if (done < 3) return null
    val perSegment = (SystemClock.elapsedRealtime() - start) / done.toDouble()
    val remaining = l.segmentsTotal - l.segmentsDone
    // + a small allowance for MP3 encoding (fast: ~1–2% of synthesis time).
    return (perSegment * remaining * 1.02).toLong()
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall)
        }
        Hairline()
    }
}
