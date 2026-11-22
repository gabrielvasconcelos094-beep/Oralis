package app.oralis.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.oralis.MainActivity
import app.oralis.OralisApp
import app.oralis.data.db.AudioEntity
import app.oralis.data.db.AudioStatus
import app.oralis.data.settings.ThemeMode
import app.oralis.generation.GenerationPhase
import app.oralis.generation.LiveGeneration
import app.oralis.model.ModelState
import app.oralis.model.ModelVariant
import app.oralis.playback.PlaybackService
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Launches the real app (Application, DI container, Room, DataStore, WorkManager, Compose UI) on
 * the JVM with Robolectric and captures each main screen. Serves as a smoke test that the app
 * starts and navigates without crashing, and produces the screenshots in docs/screenshots.
 * Native TTS/MP3 libraries are not loaded here (they are exercised by the other tests).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class AppScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<OralisApp>()
    private val c get() = app.container
    private val outDir = File(System.getProperty("oralis.screenshotDir") ?: "build/screenshots").apply { mkdirs() }

    private var scenario: ActivityScenario<MainActivity>? = null

    private fun capture(name: String) {
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        scenario!!.onActivity { a ->
            val v = a.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun installFakeModel() {
        // A placeholder file + verification marker: makes the UI show the "installed" state.
        // (Synthesis is not exercised here.)
        val v = ModelVariant.UNIVERSAL
        val dir = File(app.filesDir, "model").apply { mkdirs() }
        val f = File(dir, v.fileName).apply { writeText("placeholder") }
        File(dir, v.fileName + ".verified.json").writeText(
            """{"variant":"${v.id}","size":${f.length()},"lastModified":${f.lastModified()},"sha256":"${v.sha256}"}""",
        )
        val voices = listOf(
            "pf_dora" to "Dora (pt-BR)", "pm_alex" to "Alex (pt-BR)", "pm_santa" to "Santa (pt-BR)",
            "bf_alice" to "Alice (en-GB)", "bf_emma" to "Emma (en-GB)", "bf_isabella" to "Isabella (en-GB)", "bf_lily" to "Lily (en-GB)",
            "bm_daniel" to "Daniel (en-GB)", "bm_fable" to "Fable (en-GB)", "bm_george" to "George (en-GB)", "bm_lewis" to "Lewis (en-GB)",
        ).joinToString(",") { (id, n) ->
            """{"id":"$id","displayName":"Kokoro $n","locale":"${n.substringAfter('(').trimEnd(')')}","sampleRateHz":24000}"""
        }
        File(dir, "voices.json").writeText("[$voices]")
        runBlocking { c.modelRepository.refresh() }
        c.engineHolder.loadCachedVoices()
        assertTrue(c.modelRepository.state.value is ModelState.Installed)
    }

    private fun seedLibrary() = runBlocking {
        val dao = c.database.audioDao()
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        fun item(id: String, title: String, voice: String, lang: String, dur: Long, ago: Long, pos: Long = 0, fav: Boolean = false) = AudioEntity(
            id = id, title = title, languageTag = lang, voiceId = voice.lowercase(), voiceName = voice, speakingRate = 1f,
            presetId = "leitura", bitrateKbps = 96, createdAt = now - ago, status = AudioStatus.READY,
            textPath = File(app.filesDir, "texts/$id.txt").absolutePath, charCount = (dur / 60).toInt(), wordCount = (dur / 400).toInt(),
            estimatedDurationMs = dur, filePath = File(app.filesDir, "audio/$id.mp3").absolutePath, fileSizeBytes = dur * 12,
            durationMs = dur, favorite = fav, playbackPositionMs = pos, lastPlayedAt = if (pos > 0) now else null,
        )
        dao.insert(item("a1", "Espinosa e a liberdade — Aula 3", "Alex", "pt-BR", 47 * 60_000L, 2 * day, pos = 18 * 60_000L, fav = true))
        dao.insert(item("a2", "Uma breve história do cinema mudo", "Dora", "pt-BR", 23 * 60_000L, 3 * day))
        dao.insert(item("a3", "On Memory and Reconstruction", "Emma", "en-GB", 31 * 60_000L, 5 * day))
        dao.insert(item("a4", "Cartas a um jovem poeta", "Dora", "pt-BR", 64 * 60_000L, 9 * day, fav = true))
        dao.insert(item("a5", "Ensaio sobre o tempo livre", "George", "en-GB", 12 * 60_000L, 12 * day))
        c.settings.setLastPlayed("a1")
    }

    /** Robolectric's bindService needs the real MediaSessionService binder registered explicitly. */
    private fun registerPlaybackService() {
        val service = org.robolectric.Robolectric.buildService(PlaybackService::class.java).create().get()
        val binder = service.onBind(android.content.Intent(androidx.media3.session.MediaSessionService.SERVICE_INTERFACE))
        org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            android.content.ComponentName(app, PlaybackService::class.java), binder,
        )
    }

    @Test
    fun appLaunchesAndAllScreensRender() {
        Thread.sleep(800) // let startup reconciliation finish
        registerPlaybackService()
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }.use {
            capture("01_home_first_run")

            installFakeModel()
            seedLibrary()
            capture("02_home")

            compose.onNodeWithText("Novo áudio").performClick()
            compose.waitForIdle()
            compose.onAllNodes(hasSetTextAction())[0].performTextInput("Espinosa e a liberdade — Aula 4")
            compose.onAllNodes(hasSetTextAction())[1].performTextInput(
                "A filosofia de Espinosa propõe que Deus e a Natureza são a mesma substância.\n\n" +
                    "Nessa perspectiva, cada coisa singular é uma expressão finita de uma potência infinita, " +
                    "e compreender a necessidade dessa ordem é, para ele, o caminho da liberdade.",
            )
            Thread.sleep(400)
            capture("03_create")
            it.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()

            // A generation in progress.
            runBlocking {
                c.database.audioDao().insert(
                    AudioEntity(
                        id = "g1", title = "Ensaio sobre a memória", languageTag = "pt-BR", voiceId = "pf_dora", voiceName = "Dora",
                        speakingRate = 1f, presetId = "aula", bitrateKbps = 96, createdAt = System.currentTimeMillis(),
                        status = AudioStatus.GENERATING, textPath = "", charCount = 48_000, wordCount = 7_900,
                        estimatedDurationMs = 52 * 60_000L, segmentsTotal = 96, segmentsDone = 41,
                        generationStartedAt = System.currentTimeMillis() - 9 * 60_000L,
                    ),
                )
            }
            c.generationRepository.publish {
                LiveGeneration("g1", GenerationPhase.SYNTHESIZING, 41, 96, 0f, android.os.SystemClock.elapsedRealtime() - 9 * 60_000L,
                    android.os.SystemClock.elapsedRealtime() - 8 * 60_000L, 0, 22 * 60_000L)
            }
            compose.waitForIdle()
            capture("04_home_generating")
            compose.onNodeWithText("Ensaio sobre a memória").performClick()
            capture("05_generation_progress")
            it.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }

            compose.onNodeWithText("Abrir biblioteca", substring = true).performClick()
            capture("06_library")
            it.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }

            fun back() { it.onActivity { a -> a.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }

            compose.onNodeWithContentDescription("Configurações").performClick()
            capture("07_settings")
            compose.onNodeWithText("Modelo Kokoro", substring = true).performClick()
            capture("08_model")
            back(); back()

            val sample = File("build/test-output/real/pt_pm_alex.mp3")
            if (sample.isFile) {
                val target = File(app.filesDir, "audio/a1.mp3").apply { parentFile?.mkdirs() }
                sample.copyTo(target, overwrite = true)
                compose.onAllNodesWithText("Espinosa e a liberdade — Aula 3")[0].performClick()
                Thread.sleep(1500)
                capture("11_player")
                c.sleepTimer.start(30)
                compose.onNodeWithText("Alterar").performClick()
                capture("12_player_timer")
                back(); back()
            }

            runBlocking { c.settings.setTheme(ThemeMode.DARK) }
            capture("09_home_dark")
            compose.onNodeWithText("Novo áudio").performClick()
            capture("10_create_dark")
            back()
            if (sample.isFile) {
                compose.onAllNodesWithText("Espinosa e a liberdade — Aula 3")[0].performClick()
                capture("13_player_dark")
            }
        }
    }
}
