package app.oralis.generation

import app.oralis.audio.mp3.LameNative
import app.oralis.audio.mp3.Mp3Inspector
import app.oralis.tts.SpeechEngine
import app.oralis.tts.SynthesizedAudio
import app.oralis.tts.Voice
import java.io.File
import java.nio.file.Files
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class GenerationPipelineTest {

    /** Deterministic fake: 60 ms of tone per character, records every call. */
    private class FakeEngine(var cancelAfter: Int = Int.MAX_VALUE) : SpeechEngine {
        val calls = mutableListOf<String>()
        override val sampleRateHz = 24_000
        override suspend fun voices() = listOf(Voice("pf_dora", "Kokoro Dora (pt-BR)", "pt-BR", 24_000))
        override suspend fun synthesize(text: String, voiceId: String, locale: String?, speakingRate: Float): SynthesizedAudio {
            if (calls.size >= cancelAfter) throw CancellationException("simulated interruption")
            calls += text
            val n = text.length * 24_000 * 60 / 1000
            return SynthesizedAudio(FloatArray(n) { (sin(it * 0.05) * 0.4).toFloat() }, 24_000)
        }
    }

    private lateinit var dir: File

    @Before
    fun setUp() {
        assumeTrue(runCatching { LameNative.ensureLoaded() }.isSuccess)
        dir = Files.createTempDirectory("oralis-gen").toFile()
    }

    private fun spec(preset: ReadingPreset = ReadingPreset.READING) = GenerationSpec(
        audioId = "a1", title = "Teste", voiceId = "pf_dora", voiceName = "Dora", languageTag = "pt-BR",
        speakingRate = 1f, presetId = preset.id, bitrateKbps = 64,
    )

    private val text = (1..25).joinToString("\n\n") { "Parágrafo $it. Uma frase com acentuação e pontuação, não é? Sim." }

    @Test
    fun fullRunProducesValidMp3WithAllSegmentsInOrder() = runBlocking {
        val engine = FakeEngine()
        val pipeline = GenerationPipeline(engine, segmenterMaxChars = 80)
        val out = File(dir, "out.mp3")
        val phases = mutableListOf<GenerationPhase>()
        val result = pipeline.run(spec(), text, File(dir, "work"), out) { phases += it.phase }

        val plan = pipeline.loadOrCreatePlan(spec(), text, File(dir, "work"))
        assertEquals(plan.segments.map { it.speechText }, engine.calls)
        assertTrue(Mp3Inspector.inspect(out).isValid)
        assertFalse(File(dir, "out.mp3.part").exists())
        assertEquals(GenerationPhase.DONE, phases.last())
        assertTrue(phases.contains(GenerationPhase.ENCODING))

        // Duration = speech + extra pauses + 600 ms tail (±1 MP3 frame).
        val speechMs = engine.calls.sumOf { it.length * 60L }
        val pausesMs = plan.segments.sumOf { ReadingPreset.READING.extraPauseMs(it.breakBefore) }
        assertEquals((speechMs + pausesMs + 600).toDouble(), result.durationMs.toDouble(), 30.0)
    }

    @Test
    fun interruptedRunResumesWithoutDuplicatesOrGaps() = runBlocking {
        val engine = FakeEngine(cancelAfter = 7)
        val pipeline = GenerationPipeline(engine, segmenterMaxChars = 80)
        val work = File(dir, "work")
        val out = File(dir, "out.mp3")
        val interrupted = runCatching { pipeline.run(spec(), text, work, out) {} }
        assertTrue(interrupted.exceptionOrNull() is CancellationException)
        assertFalse(out.exists())
        assertEquals(7, engine.calls.size)

        engine.cancelAfter = Int.MAX_VALUE
        pipeline.run(spec(), text, work, out) {}
        val plan = pipeline.loadOrCreatePlan(spec(), text, work)
        // Every segment synthesized exactly once across both runs, in order.
        assertEquals(plan.segments.map { it.speechText }, engine.calls)
        assertTrue(Mp3Inspector.inspect(out).isValid)
    }

    @Test
    fun changedTextInvalidatesCheckpoints() = runBlocking {
        val engine = FakeEngine(cancelAfter = 3)
        val pipeline = GenerationPipeline(engine, segmenterMaxChars = 80)
        val work = File(dir, "work")
        runCatching { pipeline.run(spec(), text, work, File(dir, "o.mp3")) {} }
        engine.cancelAfter = Int.MAX_VALUE
        engine.calls.clear()
        val other = "Outro texto completamente diferente. Com duas frases."
        pipeline.run(spec(), other, work, File(dir, "o.mp3")) {}
        assertEquals(listOf(other), engine.calls)
    }

    @Test
    fun nightPresetAddsLongerPauses() = runBlocking {
        val p1 = GenerationPipeline(FakeEngine(), 80).run(spec(ReadingPreset.READING), text, File(dir, "w1"), File(dir, "r.mp3")) {}
        val p2 = GenerationPipeline(FakeEngine(), 80).run(spec(ReadingPreset.NIGHT), text, File(dir, "w2"), File(dir, "n.mp3")) {}
        assertTrue(p2.durationMs > p1.durationMs + 24 * 600)
    }

    @Test(expected = GenerationFailure::class)
    fun textWithoutWordsFails() = runBlocking<Unit> {
        GenerationPipeline(FakeEngine()).run(spec(), "---\n\n***", File(dir, "w"), File(dir, "x.mp3")) {}
    }
}
