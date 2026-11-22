package app.oralis.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import app.oralis.ui.components.OralisHeader
import app.oralis.ui.components.SectionLabel
import app.oralis.ui.components.VSpace

private val credits = listOf(
    "Kokoro‑82M" to "Modelo de voz por hexgrad (StyleTTS 2 + ISTFTNet). Pesos Apache‑2.0. Conversão LiteRT‑LM por Google (litert-community), Apache‑2.0. Léxicos: misaki (Apache‑2.0), unidic-lite (BSD), jieba/pypinyin (MIT).",
    "MediaPipe Tasks Audio / LiteRT" to "Google, Apache‑2.0. Executa o Kokoro no aparelho.",
    "LAME 3.100" to "Codificador MP3, LGPL‑2.0‑or‑later. Incluído como biblioteca dinâmica separada (libmp3lame.so), compilada sem modificações a partir do código-fonte oficial; o código-fonte usado acompanha o projeto do Oralis.",
    "AndroidX, Jetpack Compose, Media3, Room, WorkManager, DataStore" to "Google / Android Open Source Project, Apache‑2.0.",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "JetBrains, Apache‑2.0.",
    "Newsreader" to "Production Type, SIL Open Font License 1.1.",
    "Inter" to "Rasmus Andersson, SIL Open Font License 1.1.",
)

@Composable
fun LicensesScreen(nav: NavController) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OralisHeader("Licenças e créditos", onBack = { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()) {
            credits.forEach { (name, text) ->
                SectionLabel(name)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            VSpace(32)
        }
    }
}
