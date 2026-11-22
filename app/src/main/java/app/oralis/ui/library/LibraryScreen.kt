package app.oralis.ui.library

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.oralis.AppContainer
import app.oralis.data.db.AudioEntity
import app.oralis.data.db.AudioStatus
import app.oralis.generation.ReadingPreset
import app.oralis.ui.Routes
import app.oralis.ui.components.Hairline
import app.oralis.ui.components.MiniPlayer
import app.oralis.ui.components.OralisHeader
import app.oralis.ui.components.Pill
import app.oralis.ui.components.PtBr
import app.oralis.ui.components.VSpace
import app.oralis.ui.components.formatBytes
import app.oralis.ui.components.formatClock
import app.oralis.ui.components.formatDate
import app.oralis.ui.components.formatDuration
import app.oralis.ui.components.formatInt
import app.oralis.ui.components.formatSpeed
import kotlinx.coroutines.launch

enum class SortMode(val label: String) { DATE("Mais recentes"), TITLE("Título"), DURATION("Duração") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(container: AppContainer, nav: NavController) {
    val dao = container.database.audioDao()
    val allFlow = remember { dao.observeAll() }
    val all by allFlow.collectAsStateWithLifecycle(emptyList())
    val player by container.playerConnection.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(SortMode.DATE) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<AudioEntity?>(null) }
    var renameFor by remember { mutableStateOf<AudioEntity?>(null) }
    var deleteFor by remember { mutableStateOf<AudioEntity?>(null) }
    var infoFor by remember { mutableStateOf<AudioEntity?>(null) }
    var exportTarget by remember { mutableStateOf<AudioEntity?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) { uri ->
        val a = exportTarget ?: return@rememberLauncherForActivityResult
        exportTarget = null
        if (uri != null) scope.launch {
            runCatching { container.exportManager.exportTo(a, uri) }
                .onSuccess { snackbar.showSnackbar("MP3 exportado.") }
                .onFailure { snackbar.showSnackbar("Não foi possível exportar o arquivo.") }
        }
    }

    val items = remember(all, query, sort, favoritesOnly) {
        val q = query.trim().lowercase(PtBr)
        all.asSequence()
            .filter { !favoritesOnly || it.favorite }
            .filter { it.status != AudioStatus.CANCELLED }
            .filter { q.isEmpty() || it.title.lowercase(PtBr).contains(q) || it.voiceName.lowercase(PtBr).contains(q) }
            .sortedWith(
                when (sort) {
                    SortMode.DATE -> compareByDescending { it.createdAt }
                    SortMode.TITLE -> compareBy(java.text.Collator.getInstance(PtBr)) { it.title }
                    SortMode.DURATION -> compareByDescending { if (it.durationMs > 0) it.durationMs else it.estimatedDurationMs }
                },
            ).toList()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            OralisHeader("Biblioteca", onBack = { nav.popBackStack() }) {
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.SwapVert, contentDescription = "Ordenar") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortMode.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.label + if (m == sort) "  ✓" else "") },
                                onClick = { sort = m; sortMenu = false },
                            )
                        }
                    }
                }
            }
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.weight(1f),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        BasicTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiary),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                Box {
                                    if (query.isEmpty()) Text("Pesquisar", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    inner()
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = favoritesOnly, onClick = { favoritesOnly = !favoritesOnly },
                    label = { Text("Favoritos") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer),
                )
            }
            VSpace(8)
            if (items.isEmpty()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (all.isEmpty()) "Sua biblioteca está vazia." else "Nada encontrado.",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    VSpace(6)
                    Text(
                        if (all.isEmpty()) "Os áudios que você gerar ficam guardados aqui até você decidir removê-los." else "Tente outra busca ou filtro.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f).padding(horizontal = 24.dp)) {
                    items(items, key = { it.id }) { a ->
                        LibraryRow(
                            a = a,
                            isCurrent = player.audioId == a.id,
                            onClick = {
                                if (a.isPlayable) {
                                    scope.launch { container.playerConnection.play(a) }
                                    nav.navigate(Routes.PLAYER)
                                } else {
                                    nav.navigate(Routes.generation(a.id))
                                }
                            },
                            onFavorite = { scope.launch { dao.setFavorite(a.id, !a.favorite) } },
                            onMore = { actionsFor = a },
                        )
                        Hairline()
                    }
                }
            }
            Box(Modifier.navigationBarsPadding()) {
                MiniPlayer(player, onOpen = { nav.navigate(Routes.PLAYER) }, onToggle = { container.playerConnection.togglePlay() })
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
    }

    // ---- Action sheet ----
    actionsFor?.let { a ->
        ModalBottomSheet(onDismissRequest = { actionsFor = null }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(a.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                VSpace(12)
                fun close() { actionsFor = null }
                if (a.isPlayable) {
                    SheetAction("Salvar em Downloads") {
                        close()
                        scope.launch {
                            runCatching { container.exportManager.saveToDownloads(a) }
                                .onSuccess { snackbar.showSnackbar("Salvo em $it") }
                                .onFailure { snackbar.showSnackbar("Não foi possível salvar em Downloads.") }
                        }
                    }
                    SheetAction("Exportar para uma pasta…") {
                        close(); exportTarget = a; exportLauncher.launch(container.exportManager.fileName(a))
                    }
                    SheetAction("Compartilhar") {
                        close()
                        runCatching { context.startActivity(container.exportManager.shareIntent(a)) }
                    }
                    SheetAction("Abrir em outro aplicativo") {
                        close()
                        try { context.startActivity(container.exportManager.openWithIntent(a)) } catch (e: ActivityNotFoundException) {
                            scope.launch { snackbar.showSnackbar("Nenhum aplicativo de áudio encontrado.") }
                        }
                    }
                } else if (a.status == AudioStatus.FAILED || a.status == AudioStatus.INTERRUPTED) {
                    SheetAction("Retomar geração") { close(); scope.launch { container.generationRepository.resume(a.id) }; nav.navigate(Routes.generation(a.id)) }
                }
                SheetAction(if (a.favorite) "Remover dos favoritos" else "Favoritar") { close(); scope.launch { dao.setFavorite(a.id, !a.favorite) } }
                SheetAction("Renomear") { close(); renameFor = a }
                SheetAction("Informações") { close(); infoFor = a }
                SheetAction("Excluir", destructive = true) { close(); deleteFor = a }
            }
        }
    }

    renameFor?.let { a ->
        var value by remember(a.id) { mutableStateOf(a.title) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Renomear") },
            text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = {
                    renameFor = null
                    scope.launch { dao.rename(a.id, value.trim()) }
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Cancelar") } },
        )
    }

    deleteFor?.let { a ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Excluir “${a.title}”?") },
            text = {
                Text(
                    "O áudio e o texto guardados no Oralis serão apagados deste aparelho. " +
                        "Cópias que você já exportou ou compartilhou (por exemplo, em Downloads) não são afetadas.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteFor = null
                    scope.launch {
                        if (a.isActive) container.generationRepository.cancel(a.id)
                        container.playerConnection.stopIf(a.id)
                        container.generationRepository.deleteFiles(a)
                        dao.delete(a.id)
                    }
                }) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancelar") } },
        )
    }

    infoFor?.let { a ->
        AlertDialog(
            onDismissRequest = { infoFor = null },
            confirmButton = { TextButton(onClick = { infoFor = null }) { Text("Fechar") } },
            title = { Text(a.title) },
            text = {
                Column {
                    InfoLine("Estado", statusLabel(a.status))
                    InfoLine("Idioma", if (a.languageTag.startsWith("pt")) "Português (Brasil)" else "English (UK)")
                    InfoLine("Voz", "${a.voiceName} (${a.voiceId})")
                    InfoLine("Velocidade", formatSpeed(a.speakingRate))
                    InfoLine("Estilo", ReadingPreset.fromId(a.presetId).label)
                    if (a.durationMs > 0) InfoLine("Duração", formatClock(a.durationMs))
                    InfoLine("Texto", "${formatInt(a.wordCount)} palavras · ${formatInt(a.charCount)} caracteres")
                    InfoLine("Criado em", formatDate(a.createdAt))
                    if (a.fileSizeBytes > 0) InfoLine("Arquivo", "MP3 ${a.bitrateKbps} kbps · ${formatBytes(a.fileSizeBytes)}")
                    InfoLine("Local", "Armazenamento interno do Oralis")
                    a.lastExportUri?.let { InfoLine("Última exportação", "Sim") }
                    if (a.playbackPositionMs > 0) InfoLine("Parou em", formatClock(a.playbackPositionMs))
                }
            },
        )
    }
}

@Composable
private fun LibraryRow(a: AudioEntity, isCurrent: Boolean, onClick: () -> Unit, onFavorite: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    a.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (isCurrent) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (a.status != AudioStatus.READY) {
                    Spacer(Modifier.width(8.dp)); Pill(statusLabel(a.status), accent = a.status == AudioStatus.GENERATING)
                }
            }
            VSpace(3)
            val dur = if (a.durationMs > 0) formatDuration(a.durationMs) else "≈ ${formatDuration(a.estimatedDurationMs)}"
            Text(
                "$dur · ${a.voiceName} · ${formatDate(a.createdAt)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (a.status == AudioStatus.READY && a.playbackPositionMs > 0 && a.durationMs > 0) {
                VSpace(6)
                LinearProgressIndicator(
                    progress = { a.playbackPositionMs.toFloat() / a.durationMs },
                    modifier = Modifier.fillMaxWidth(0.4f).height(2.dp),
                    color = MaterialTheme.colorScheme.tertiary, trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {},
                )
            }
        }
        IconButton(onClick = onFavorite) {
            Icon(
                if (a.favorite) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (a.favorite) "Remover dos favoritos" else "Favoritar",
                tint = if (a.favorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, contentDescription = "Mais ações") }
    }
}

@Composable
private fun SheetAction(text: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

fun statusLabel(s: AudioStatus) = when (s) {
    AudioStatus.QUEUED -> "Na fila"
    AudioStatus.GENERATING -> "Gerando"
    AudioStatus.READY -> "Pronto"
    AudioStatus.FAILED -> "Erro"
    AudioStatus.CANCELLED -> "Cancelado"
    AudioStatus.INTERRUPTED -> "Interrompido"
}
