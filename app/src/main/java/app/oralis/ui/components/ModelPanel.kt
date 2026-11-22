package app.oralis.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.oralis.model.ModelRepository
import app.oralis.model.ModelState
import app.oralis.model.ModelVariant

/** Install / progress / error panel for the Kokoro model. */
@Composable
fun ModelInstallPanel(
    state: ModelState,
    preferredVariant: ModelVariant,
    repo: ModelRepository,
    compact: Boolean = false,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            when (state) {
                ModelState.Checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmallSpinner(); Spacer(Modifier.width(12.dp))
                        Text("Verificando o modelo de voz…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                ModelState.NotInstalled -> {
                    Text("Antes de começar", style = MaterialTheme.typography.titleLarge)
                    VSpace(8)
                    Text(
                        "O Oralis lê com a voz neural Kokoro, que roda inteiramente no seu aparelho. " +
                            "É um download único de ${formatBytes(preferredVariant.sizeBytes)} do repositório oficial; " +
                            "depois disso, tudo funciona sem internet e nenhum texto sai do aparelho.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    VSpace(18)
                    PrimaryButton("Instalar a voz neural", onClick = { repo.startDownload(preferredVariant) }, modifier = Modifier.fillMaxWidth())
                    if (!compact) {
                        VSpace(6)
                        Text(
                            "Espaço livre: ${formatBytes(repo.availableBytes())} · Prefira uma rede Wi‑Fi.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is ModelState.Downloading -> {
                    Text("Instalando a voz neural", style = MaterialTheme.typography.titleLarge)
                    VSpace(14)
                    LinearProgressIndicator(
                        progress = { state.fraction },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.tertiary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant,
                        gapSize = 0.dp, drawStopIndicator = {},
                    )
                    VSpace(10)
                    Row {
                        Text(
                            "${(state.fraction * 100).toInt()}% · ${formatBytes(state.bytes)} de ${formatBytes(state.total)}",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                        )
                        if (state.bytesPerSecond > 0) {
                            Text("${formatBytes(state.bytesPerSecond)}/s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    VSpace(8)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        QuietButton("Cancelar", onClick = { repo.cancelDownload() })
                        QuietButton("Pausar", onClick = { repo.pauseDownload() }, accent = true)
                    }
                }
                is ModelState.Paused -> {
                    Text("Download pausado", style = MaterialTheme.typography.titleLarge)
                    VSpace(6)
                    Text(
                        "${formatBytes(state.bytes)} de ${formatBytes(state.total)} já baixados e guardados.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    VSpace(12)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        QuietButton("Descartar", onClick = { repo.cancelDownload() })
                        QuietButton("Retomar", onClick = { repo.startDownload(state.variant) }, accent = true)
                    }
                }
                is ModelState.Verifying -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmallSpinner(); Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Verificando a integridade", style = MaterialTheme.typography.titleMedium)
                            Text("Conferindo o SHA‑256 oficial do arquivo…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                is ModelState.Installed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text("Voz neural instalada e verificada · funciona offline", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is ModelState.Failed -> {
                    Text("Instalação interrompida", style = MaterialTheme.typography.titleLarge)
                    VSpace(6)
                    Text(state.error.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    VSpace(12)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        if (state.bytes > 0) QuietButton("Descartar", onClick = { repo.cancelDownload() })
                        QuietButton(state.error.action, onClick = { repo.startDownload(state.variant ?: preferredVariant) }, accent = true)
                    }
                }
            }
        }
    }
}
