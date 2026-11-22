package app.oralis.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.oralis.AppContainer
import app.oralis.BuildConfig
import app.oralis.data.settings.Settings
import app.oralis.data.settings.ThemeMode
import app.oralis.generation.ReadingPreset
import app.oralis.model.ModelState
import app.oralis.ui.Routes
import app.oralis.ui.components.ChoiceRow
import app.oralis.ui.components.Hairline
import app.oralis.ui.components.OralisHeader
import app.oralis.ui.components.SectionLabel
import app.oralis.ui.components.VSpace
import app.oralis.ui.components.formatBytes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(container: AppContainer, nav: NavController) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(Settings())
    val model by container.modelRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var audioBytes by remember { mutableLongStateOf(0L) }
    var tempBytes by remember { mutableLongStateOf(0L) }
    var confirmClean by remember { mutableStateOf(false) }

    suspend fun measure() = withContext(Dispatchers.IO) {
        audioBytes = container.generationRepository.audioDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        tempBytes = File(container.generationRepository.audioDir.parentFile, "cache").let { 0L } +
            container.generationRepository.workRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
    LaunchedEffect(Unit) { measure() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OralisHeader("Configurações", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()) {
            SectionLabel("Aparência")
            ThemeMode.entries.forEach { m ->
                ChoiceRow(
                    selected = settings.theme == m,
                    onClick = { scope.launch { container.settings.setTheme(m) } },
                    title = when (m) { ThemeMode.SYSTEM -> "Automático"; ThemeMode.LIGHT -> "Claro"; ThemeMode.DARK -> "Escuro" },
                    subtitle = if (m == ThemeMode.SYSTEM) "Segue o tema do sistema" else null,
                )
            }

            SectionLabel("Leitura padrão")
            ReadingPreset.entries.forEach { p ->
                ChoiceRow(selected = settings.preset == p, onClick = { scope.launch { container.settings.setPreset(p) } }, title = p.label, subtitle = p.description)
            }

            SectionLabel("Texto")
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ignorar marcações Markdown", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Não lê símbolos de formatação (#, **, listas, links) comuns em textos de assistentes. As palavras nunca são alteradas e o texto original fica intacto.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = settings.stripMarkdown,
                    onCheckedChange = { scope.launch { container.settings.setStripMarkdown(it) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.tertiary),
                )
            }

            SectionLabel("Voz neural")
            NavRow(
                "Modelo Kokoro‑82M",
                when (val m = model) {
                    is ModelState.Installed -> "Instalado · ${m.variant.title}"
                    is ModelState.Downloading -> "Baixando · ${(m.fraction * 100).toInt()}%"
                    is ModelState.Verifying -> "Verificando integridade…"
                    is ModelState.Paused -> "Download pausado"
                    is ModelState.Failed -> "Erro na instalação"
                    else -> "Não instalado"
                },
            ) { nav.navigate(Routes.MODEL) }

            SectionLabel("Armazenamento")
            InfoRow("Áudios na biblioteca", formatBytes(audioBytes))
            InfoRow("Arquivos temporários de geração", formatBytes(tempBytes))
            Text(
                "Os áudios ficam no armazenamento interno do Oralis até você excluí-los. Exporte para Downloads ou outra pasta para tê-los fora do aplicativo.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (tempBytes > 0) {
                TextButton(onClick = { confirmClean = true }) { Text("Limpar temporários de gerações interrompidas", color = MaterialTheme.colorScheme.tertiary) }
            }

            SectionLabel("Privacidade")
            Text(
                "Textos e áudios nunca saem do aparelho. Não há conta, anúncios, rastreamento nem envio de dados. " +
                    "A internet é usada somente para baixar o modelo de voz do repositório oficial no Hugging Face.",
                style = MaterialTheme.typography.bodyMedium,
            )

            SectionLabel("Sobre")
            NavRow("Licenças e créditos", "Kokoro, MediaPipe, LAME, fontes") { nav.navigate(Routes.LICENSES) }
            InfoRow("Versão", BuildConfig.VERSION_NAME)
            VSpace(32)
        }
    }

    if (confirmClean) {
        AlertDialog(
            onDismissRequest = { confirmClean = false },
            title = { Text("Limpar temporários?") },
            text = { Text("Gerações interrompidas ou com erro não poderão ser retomadas de onde pararam; elas recomeçarão do início. Áudios prontos não são afetados.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClean = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val active = container.database.audioDao().active().map { it.id }.toSet()
                            container.generationRepository.workRoot.listFiles()?.filter { it.name !in active }?.forEach { it.deleteRecursively() }
                        }
                        measure()
                    }
                }) { Text("Limpar") }
            },
            dismissButton = { TextButton(onClick = { confirmClean = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
internal fun NavRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Hairline()
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
