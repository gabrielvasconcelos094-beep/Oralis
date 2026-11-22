package app.oralis.ui.create

import android.Manifest
import android.content.ClipboardManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import app.oralis.AppContainer
import app.oralis.core.text.TextAnalysis
import app.oralis.data.settings.AppLanguage
import app.oralis.generation.ReadingPreset
import app.oralis.model.ModelState
import app.oralis.tts.EngineState
import app.oralis.tts.VoiceGender
import app.oralis.tts.VoicePreviewPlayer
import app.oralis.ui.Routes
import app.oralis.ui.components.ChoiceRow
import app.oralis.ui.components.Hairline
import app.oralis.ui.components.OralisHeader
import app.oralis.ui.components.PrimaryButton
import app.oralis.ui.components.QuietButton
import app.oralis.ui.components.SectionLabel
import app.oralis.ui.components.SmallSpinner
import app.oralis.ui.components.VSpace
import app.oralis.ui.components.formatDuration
import app.oralis.ui.components.formatInt
import app.oralis.ui.components.formatSpeed
import app.oralis.ui.theme.ReadingStyle
import kotlinx.coroutines.launch

@Composable
fun CreateScreen(container: AppContainer, nav: NavController, sharedText: String?, onSharedConsumed: () -> Unit) {
    val vm: CreateViewModel = viewModel { CreateViewModel(container) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val title by vm.title.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val voices by vm.voices.collectAsStateWithLifecycle()
    val preview by vm.previewState.collectAsStateWithLifecycle()
    val modelState by vm.modelState.collectAsStateWithLifecycle()
    val engineState by vm.engineState.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val starting by vm.starting.collectAsStateWithLifecycle()

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            vm.setText(sharedText)
            onSharedConsumed()
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importTxt(context, uri)
    }
    val startGeneration: () -> Unit = {
        scope.launch {
            val id = vm.generate()
            if (id != null) nav.navigate(Routes.generation(id)) { popUpTo(Routes.HOME) }
        }
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { startGeneration() }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        OralisHeader("Novo áudio", onBack = { vm.stopPreview(); nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            // ---- Title ----
            BasicTextField(
                value = title,
                onValueChange = { vm.title.value = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                decorationBox = { inner ->
                    Box {
                        if (title.isEmpty()) {
                            Text("Título", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.outline)
                        }
                        inner()
                    }
                },
            )
            Hairline()

            // ---- Text ----
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (stats.words == 0) "Texto" else
                        "${formatInt(stats.words)} palavras · ${formatInt(stats.characters)} caracteres",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                    val pasted = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (!pasted.isNullOrEmpty()) vm.setText(if (vm.text.text.isEmpty()) pasted else vm.text.text.toString() + "\n\n" + pasted)
                }) { Icon(Icons.Outlined.ContentPaste, contentDescription = "Colar") }
                IconButton(onClick = { importer.launch(arrayOf("text/plain", "text/*")) }) {
                    Icon(Icons.Outlined.FileOpen, contentDescription = "Importar arquivo TXT")
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                BasicTextField(
                    state = vm.text,
                    textStyle = ReadingStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                    lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 8, maxHeightInLines = 14),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp).padding(16.dp),
                    decorator = { inner ->
                        Box {
                            if (vm.text.text.isEmpty()) {
                                Text(
                                    "Escreva, cole ou importe um texto. O Oralis lê exatamente o que estiver aqui — sem resumir, sem reescrever.",
                                    style = ReadingStyle, color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            if (stats.words > 0) {
                val est = TextAnalysis.estimateDurationMs(stats, options.language.tag, options.speed, options.preset.paragraphGapMs)
                VSpace(8)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Duração estimada ≈ ${formatDuration(est)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (vm.text.text.isNotEmpty()) QuietButton("Limpar", onClick = { vm.setText("") })
                }
            }

            // ---- Language ----
            SectionLabel("Idioma")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AppLanguage.entries.forEachIndexed { i, l ->
                    SegmentedButton(
                        selected = options.language == l,
                        onClick = { vm.setLanguage(l) },
                        shape = SegmentedButtonDefaults.itemShape(i, AppLanguage.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primary,
                            activeContentColor = MaterialTheme.colorScheme.onPrimary,
                            inactiveContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                        icon = {},
                    ) { Text(l.label, style = MaterialTheme.typography.labelLarge) }
                }
            }

            // ---- Voices ----
            SectionLabel("Voz")
            when {
                modelState !is ModelState.Installed -> Text(
                    "As vozes ficam disponíveis depois de instalar o modelo Kokoro na tela inicial.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                voices.isEmpty() && engineState is EngineState.Failed -> Column {
                    Text("Não foi possível carregar as vozes.", style = MaterialTheme.typography.bodyMedium)
                    QuietButton("Tentar de novo", onClick = vm::loadVoices, accent = true)
                }
                voices.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallSpinner(); Spacer(Modifier.width(12.dp))
                    Text("Carregando o modelo e descobrindo as vozes…", style = MaterialTheme.typography.bodyMedium)
                }
                else -> voices.forEach { v ->
                    val key = "voice:${v.id}"
                    ChoiceRow(
                        selected = options.voiceId == v.id,
                        onClick = { vm.setVoice(v) },
                        title = v.shortName,
                        subtitle = when (v.gender) {
                            VoiceGender.FEMALE -> "Voz feminina"
                            VoiceGender.MALE -> "Voz masculina"
                            VoiceGender.UNKNOWN -> v.locale
                        } + " · ${v.id}",
                        trailing = { PreviewButton(preview, key, onClick = { vm.previewVoice(v) }, label = "Ouvir amostra de ${v.shortName}") },
                    )
                }
            }
            (preview as? VoicePreviewPlayer.State.Error)?.let {
                Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            (preview as? VoicePreviewPlayer.State.Preparing)?.takeIf { it.loadingModel }?.let {
                Text(
                    "Carregando o modelo de voz… a primeira amostra leva alguns segundos; as seguintes são imediatas.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- Preset ----
            SectionLabel("Estilo de leitura")
            ReadingPreset.entries.forEach { p ->
                ChoiceRow(selected = options.preset == p, onClick = { vm.setPreset(p) }, title = p.label, subtitle = p.description)
            }

            // ---- Speed ----
            SectionLabel("Velocidade da voz")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = options.speed,
                    onValueChange = vm::setSpeed,
                    valueRange = 0.7f..1.4f,
                    steps = 13,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.tertiary,
                        activeTrackColor = MaterialTheme.colorScheme.tertiary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                        activeTickColor = MaterialTheme.colorScheme.tertiary,
                        inactiveTickColor = MaterialTheme.colorScheme.outline,
                    ),
                )
                Spacer(Modifier.width(16.dp))
                Text(formatSpeed(options.speed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(56.dp))
            }
            Text(
                "Ajusta a velocidade de fala do próprio modelo (sem distorcer a voz). A velocidade do player é independente.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stats.words > 0 && voices.isNotEmpty()) {
                VSpace(12)
                OutlinedButton(
                    onClick = vm::previewText,
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    val active = (preview as? VoicePreviewPlayer.State.Playing)?.key == "text" || (preview as? VoicePreviewPlayer.State.Preparing)?.key == "text"
                    if ((preview as? VoicePreviewPlayer.State.Preparing)?.key == "text") SmallSpinner()
                    else Icon(if (active) Icons.Rounded.Stop else Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (active) "Parar" else "Ouvir o início do texto", color = MaterialTheme.colorScheme.onSurface)
                }
            }

            // ---- Quality ----
            SectionLabel("Qualidade do MP3")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val rates = listOf(64, 96, 128)
                rates.forEachIndexed { i, kbps ->
                    SegmentedButton(
                        selected = options.bitrateKbps == kbps,
                        onClick = { vm.setBitrate(kbps) },
                        shape = SegmentedButtonDefaults.itemShape(i, rates.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            inactiveContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                        icon = {},
                    ) { Text("$kbps kbps", style = MaterialTheme.typography.labelMedium) }
                }
            }
            VSpace(6)
            Text(
                "≈ ${options.bitrateKbps * 450 / 1000} MB por hora de áudio. " + when (options.bitrateKbps) {
                    64 -> "Econômico, ainda nítido para voz."
                    96 -> "Recomendado: ótimo equilíbrio."
                    else -> "Máxima fidelidade."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VSpace(28)
        }

        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                PrimaryButton(
                    if (starting) "Preparando…" else "Gerar áudio",
                    enabled = !starting,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else startGeneration()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    message?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::consumeMessage,
            confirmButton = { TextButton(onClick = vm::consumeMessage) { Text("Entendi") } },
            text = { Text(msg, style = MaterialTheme.typography.bodyLarge) },
        )
    }
}

@Composable
private fun PreviewButton(state: VoicePreviewPlayer.State, key: String, onClick: () -> Unit, label: String) {
    val preparing = state is VoicePreviewPlayer.State.Preparing && state.key == key
    val playing = state is VoicePreviewPlayer.State.Playing && state.key == key
    IconButton(onClick = onClick) {
        Box(
            Modifier.size(36.dp).border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraLarge),
            contentAlignment = Alignment.Center,
        ) {
            when {
                preparing -> SmallSpinner()
                playing -> Icon(Icons.Rounded.Stop, contentDescription = "Parar amostra", modifier = Modifier.size(18.dp))
                else -> Icon(Icons.Rounded.PlayArrow, contentDescription = label, modifier = Modifier.size(20.dp))
            }
        }
    }
}
