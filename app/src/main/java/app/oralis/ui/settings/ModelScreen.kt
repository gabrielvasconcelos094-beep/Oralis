package app.oralis.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.oralis.AppContainer
import app.oralis.data.settings.Settings
import app.oralis.model.ModelState
import app.oralis.model.ModelVariant
import app.oralis.tts.AccelerationMode
import app.oralis.tts.EngineState
import app.oralis.ui.components.ChoiceRow
import app.oralis.ui.components.ModelInstallPanel
import app.oralis.ui.components.OralisHeader
import app.oralis.ui.components.QuietButton
import app.oralis.ui.components.SectionLabel
import app.oralis.ui.components.VSpace
import app.oralis.ui.components.formatBytes
import kotlinx.coroutines.launch

@Composable
fun ModelScreen(container: AppContainer, nav: NavController) {
    val model by container.modelRepository.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(Settings())
    val engine by container.engineHolder.state.collectAsStateWithLifecycle()
    val voices by container.engineHolder.voices.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<String?>(null) }
    var pendingVariant by remember { mutableStateOf<ModelVariant?>(null) }
    val installed = (model as? ModelState.Installed)?.variant
    val busy = model is ModelState.Downloading || model is ModelState.Verifying

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OralisHeader("Voz neural", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text(
                "Kokoro‑82M (Apache‑2.0), executado no aparelho pelo MediaPipe Text‑to‑Speech / LiteRT‑LM. " +
                    "Origem: huggingface.co/${ModelVariant.REPO}, revisão ${ModelVariant.REVISION.take(10)}.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(16)
            ModelInstallPanel(model, settings.modelVariant, container.modelRepository)

            if (installed != null) {
                SectionLabel("Estado")
                InfoRow("Versão instalada", installed.title)
                InfoRow("Tamanho", formatBytes(installed.sizeBytes))
                InfoRow("Integridade", "SHA‑256 verificado")
                InfoRow("Vozes disponíveis", if (voices.isEmpty()) "—" else voices.size.toString())
                InfoRow(
                    "Motor",
                    when (val e = engine) {
                        is EngineState.Ready -> "Ativo · ${if (e.delegate == "GPU") "GPU" else "CPU"} · carregou em ${e.loadMs} ms"
                        EngineState.Loading -> "Inicializando…"
                        is EngineState.Failed -> "Falhou ao iniciar"
                        EngineState.Unloaded -> "Em repouso (carrega quando necessário)"
                    },
                )
                (engine as? EngineState.Failed)?.let {
                    Text(app.oralis.tts.ErrorMessages.engine(it.error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                VSpace(4)
                QuietButton("Testar o motor agora", onClick = { scope.launch { container.engineHolder.warmUp() } }, accent = true)
                QuietButton("Verificar integridade novamente", onClick = { scope.launch { container.modelRepository.verify(installed) } })
            }

            SectionLabel("Versão do modelo")
            ModelVariant.entries.forEach { v ->
                ChoiceRow(
                    selected = (installed ?: settings.modelVariant) == v,
                    onClick = {
                        if (busy) return@ChoiceRow
                        if (installed != null && installed != v) pendingVariant = v
                        else scope.launch { container.settings.setModelVariant(v) }
                    },
                    title = v.title + if (v == ModelVariant.DEFAULT) " (recomendado)" else "",
                    subtitle = v.description,
                )
            }

            SectionLabel("Aceleração")
            if (installed == ModelVariant.GPU) {
                Text(
                    "A versão otimizada sempre usa a GPU. Se o seu aparelho não tiver GPU compatível, instale a versão Universal.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ChoiceRow(
                    selected = settings.acceleration == AccelerationMode.AUTO,
                    onClick = { scope.launch { container.settings.setAcceleration(AccelerationMode.AUTO); container.engineHolder.release() } },
                    title = "Automática",
                    subtitle = "Tenta a GPU e usa a CPU se a GPU não puder ser inicializada.",
                )
                ChoiceRow(
                    selected = settings.acceleration == AccelerationMode.CPU,
                    onClick = { scope.launch { container.settings.setAcceleration(AccelerationMode.CPU); container.engineHolder.release() } },
                    title = "Somente CPU",
                    subtitle = "Útil se a GPU do aparelho produzir instabilidade.",
                )
            }

            if (installed != null) {
                SectionLabel("Manutenção")
                QuietButton("Reinstalar o modelo", onClick = { confirm = "reinstall" })
                QuietButton("Remover o modelo do aparelho", onClick = { confirm = "delete" })
                val totalRam = container.engineHolder.totalMemoryBytes()
                Text(
                    "Memória do aparelho: ${formatBytes(totalRam)}. O motor usa de ~0,35 a ~0,95 GB enquanto gera áudio e é liberado após alguns minutos sem uso.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            VSpace(32)
        }
    }

    pendingVariant?.let { v ->
        AlertDialog(
            onDismissRequest = { pendingVariant = null },
            title = { Text("Trocar para “${v.title}”?") },
            text = { Text("A versão atual será removida e a nova será baixada (${formatBytes(v.sizeBytes)}). Seus áudios não são afetados.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingVariant = null
                    scope.launch {
                        container.settings.setModelVariant(v)
                        container.engineHolder.release()
                        container.modelRepository.deleteModel()
                        container.modelRepository.startDownload(v)
                    }
                }) { Text("Trocar") }
            },
            dismissButton = { TextButton(onClick = { pendingVariant = null }) { Text("Cancelar") } },
        )
    }

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "delete") "Remover o modelo?" else "Reinstalar o modelo?") },
            text = {
                Text(
                    if (what == "delete") "Libera cerca de 295 MB. Para gerar novos áudios será preciso baixá-lo de novo. Seus áudios continuam na biblioteca."
                    else "O modelo será removido e baixado novamente do repositório oficial.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        container.engineHolder.release()
                        container.modelRepository.deleteModel()
                        if (what == "reinstall") container.modelRepository.startDownload(settings.modelVariant)
                    }
                }) { Text(if (what == "delete") "Remover" else "Reinstalar") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancelar") } },
        )
    }
}
