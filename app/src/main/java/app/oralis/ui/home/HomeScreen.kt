package app.oralis.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.oralis.AppContainer
import app.oralis.data.db.AudioEntity
import app.oralis.data.db.AudioStatus
import app.oralis.data.settings.Settings
import app.oralis.generation.GenerationPhase
import app.oralis.model.ModelState
import app.oralis.ui.Routes
import app.oralis.ui.components.Hairline
import app.oralis.ui.components.MiniPlayer
import app.oralis.ui.components.ModelInstallPanel
import app.oralis.ui.components.Pill
import app.oralis.ui.components.PrimaryButton
import app.oralis.ui.components.QuietButton
import app.oralis.ui.components.SectionLabel
import app.oralis.ui.components.VSpace
import app.oralis.ui.components.formatClock
import app.oralis.ui.components.formatDuration
import app.oralis.ui.theme.Newsreader
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(container: AppContainer, nav: NavController) {
    val modelState by container.modelRepository.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(Settings())
    val audiosFlow = remember { container.database.audioDao().observeAll() }
    val audios by audiosFlow.collectAsStateWithLifecycle(emptyList())
    val live by container.generationRepository.live.collectAsStateWithLifecycle()
    val player by container.playerConnection.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val ready = audios.filter { it.status == AudioStatus.READY }
    val continueItem = settings.lastPlayedAudioId?.let { id -> ready.firstOrNull { it.id == id } }
        ?.takeIf { it.playbackPositionMs > 0 && it.id != player.audioId }
    val pending = audios.filter { it.status in setOf(AudioStatus.QUEUED, AudioStatus.GENERATING, AudioStatus.INTERRUPTED, AudioStatus.FAILED) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("Oralis", style = MaterialTheme.typography.displayMedium)
                    Text(
                        "Dê voz ao que você lê.",
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = Newsreader, fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Configurações")
                }
            }

            VSpace(36)
            if (modelState !is ModelState.Installed) {
                ModelInstallPanel(modelState, settings.modelVariant, container.modelRepository)
                VSpace(20)
            }

            PrimaryButton(
                "Novo áudio",
                onClick = { nav.navigate(Routes.CREATE) },
                icon = Icons.Rounded.Add,
                modifier = Modifier.fillMaxWidth(),
            )
            if (modelState is ModelState.Installed) {
                VSpace(12)
                Text(
                    "Voz neural instalada · funciona offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { nav.navigate(Routes.MODEL) }.padding(vertical = 4.dp),
                )
            }

            if (pending.isNotEmpty()) {
                SectionLabel("Em produção")
                pending.forEach { a ->
                    PendingRow(a, live?.takeIf { it.audioId == a.id }?.let { l ->
                        when (l.phase) {
                            GenerationPhase.SYNTHESIZING -> if (l.segmentsTotal > 0) l.segmentsDone.toFloat() / l.segmentsTotal else null
                            GenerationPhase.ENCODING -> l.encodeFraction
                            else -> null
                        }
                    }) { nav.navigate(Routes.generation(a.id)) }
                }
            }

            if (continueItem != null) {
                SectionLabel("Continuar ouvindo")
                ContinueRow(continueItem) {
                    scope.launch { container.playerConnection.play(continueItem) }
                    nav.navigate(Routes.PLAYER)
                }
            }

            SectionLabel("Recentes")
            if (ready.isEmpty()) {
                Text(
                    "Seus áudios aparecerão aqui. Cole uma aula, um ensaio ou um capítulo e o Oralis o transforma em MP3.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ready.take(4).forEachIndexed { i, a ->
                    if (i > 0) Hairline()
                    RecentRow(a) {
                        scope.launch { container.playerConnection.play(a) }
                        nav.navigate(Routes.PLAYER)
                    }
                }
                VSpace(4)
                QuietButton("Abrir biblioteca (${ready.size})", onClick = { nav.navigate(Routes.LIBRARY) }, accent = true)
            }
            VSpace(24)
        }
        Box(Modifier.navigationBarsPadding()) {
            MiniPlayer(player, onOpen = { nav.navigate(Routes.PLAYER) }, onToggle = { container.playerConnection.togglePlay() })
        }
    }
}

@Composable
private fun PendingRow(a: AudioEntity, fraction: Float?, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.clickable(onClick = onClick).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Pill(
                    when (a.status) {
                        AudioStatus.QUEUED -> "Na fila"
                        AudioStatus.GENERATING -> fraction?.let { "${(it * 100).toInt()}%" } ?: "Gerando"
                        AudioStatus.INTERRUPTED -> "Interrompido"
                        AudioStatus.FAILED -> "Erro"
                        else -> ""
                    },
                    accent = a.status == AudioStatus.GENERATING,
                )
            }
            if (a.status == AudioStatus.GENERATING) {
                VSpace(10)
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(3.dp), color = MaterialTheme.colorScheme.tertiary, trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp), color = MaterialTheme.colorScheme.tertiary, trackColor = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun ContinueRow(a: AudioEntity, onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            VSpace(4)
            Text(
                "${formatClock(a.playbackPositionMs)} de ${formatClock(a.durationMs)} · ${a.voiceName}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(8)
            LinearProgressIndicator(
                progress = { if (a.durationMs > 0) a.playbackPositionMs.toFloat() / a.durationMs else 0f },
                modifier = Modifier.fillMaxWidth(0.7f).height(3.dp),
                color = MaterialTheme.colorScheme.tertiary, trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {},
            )
        }
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primary) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = "Continuar", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(14.dp))
        }
    }
}

@Composable
private fun RecentRow(a: AudioEntity, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatDuration(a.durationMs)} · ${a.voiceName} · ${if (a.languageTag.startsWith("pt")) "Português" else "English"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
