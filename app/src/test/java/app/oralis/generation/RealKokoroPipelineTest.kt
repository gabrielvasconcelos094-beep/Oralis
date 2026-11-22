package app.oralis.generation

import app.oralis.audio.mp3.LameNative
import app.oralis.audio.mp3.Mp3Inspector
import app.oralis.tts.SpeechEngine
import app.oralis.tts.SynthesizedAudio
import app.oralis.tts.Voice
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * End-to-end validation of the Oralis generation pipeline with the REAL Kokoro-82M model (the
 * official .litertlm bundle, run by MediaPipe's TextToSpeech on the host through
 * tools/kokoro_host_server.py). Everything else is the production Kotlin code: segmentation,
 * checkpoints, gain, LAME MP3 encoding and validation.
 *
 * Runs only when -Doralis.modelPath and -Doralis.python are provided, e.g.
 *   ORALIS_MODEL_PATH=/path/kokoro_int8_fp16.litertlm ORALIS_PYTHON=/path/python \
 *   ./gradlew :app:testDebugUnitTest --tests '*RealKokoroPipelineTest*'
 */
class RealKokoroPipelineTest {

    class HostKokoro(python: String, model: String, script: File) : SpeechEngine {
        private val proc = ProcessBuilder(python, "-I", script.absolutePath, model).redirectError(ProcessBuilder.Redirect.appendTo(File(System.getProperty("java.io.tmpdir"), "kokoro_host.log"))).start()
        private val out = proc.outputStream.bufferedWriter()
        private val input = BufferedReader(InputStreamReader(proc.inputStream))
        private val tmp = File.createTempFile("kokoro", ".f32")
        val calls = mutableListOf<String>()
        var failAfter = Int.MAX_VALUE

        init {
            check(readJson()["ok"]!!.jsonPrimitive.boolean) { "host Kokoro failed to start" }
        }

        private fun readJson(): JsonObject = Json.parseToJsonElement(input.readLine() ?: error("server died")).jsonObject

        private fun request(obj: Map<String, Any?>): JsonObject {
            val json = JsonObject(obj.mapValues { (_, v) ->
                when (v) { null -> kotlinx.serialization.json.JsonNull; is Number -> JsonPrimitive(v); else -> JsonPrimitive(v.toString()) }
            })
            out.write(json.toString()); out.newLine(); out.flush()
            return readJson()
        }

        override val sampleRateHz = 24_000

        override suspend fun voices(): List<Voice> = withContext(Dispatchers.IO) {
            request(mapOf("cmd" to "voices"))["voices"]!!.jsonArray.map {
                val a = it.jsonArray
                Voice(a[0].jsonPrimitive.content, a[1].jsonPrimitive.content, a[2].jsonPrimitive.content, a[3].jsonPrimitive.int)
            }
        }

        override suspend fun synthesize(text: String, voiceId: String, locale: String?, speakingRate: Float): SynthesizedAudio =
            withContext(Dispatchers.IO) {
                if (calls.size >= failAfter) throw CancellationException("simulated process death")
                val r = request(mapOf("text" to text, "voice" to voiceId, "locale" to locale, "rate" to speakingRate, "out" to tmp.absolutePath))
                check(r["ok"]!!.jsonPrimitive.boolean) { "synthesis failed: $r" }
                calls += text
                val bytes = tmp.readBytes()
                val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                SynthesizedAudio(FloatArray(fb.remaining()).also { fb.get(it) }, r["sample_rate"]!!.jsonPrimitive.int)
            }

        fun close() { runCatching { out.close() }; proc.destroy(); tmp.delete() }
    }

    companion object {
        private var engine: HostKokoro? = null
        private lateinit var outDir: File

        @BeforeClass @JvmStatic
        fun start() {
            val model = System.getProperty("oralis.modelPath") ?: System.getenv("ORALIS_MODEL_PATH")
            val python = System.getProperty("oralis.python") ?: System.getenv("ORALIS_PYTHON")
            if (model == null || python == null || !File(model).isFile) return
            if (runCatching { LameNative.ensureLoaded() }.isFailure) return
            val script = generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "tools/kokoro_host_server.py") }.first { it.isFile }
            engine = HostKokoro(python, model, script)
            outDir = File(System.getProperty("oralis.testOutputDir") ?: "build/test-output", "real").apply { mkdirs() }
        }

        @AfterClass @JvmStatic
        fun stop() { engine?.close() }
    }

    private fun kokoro(): HostKokoro {
        assumeTrue("real Kokoro not configured", engine != null)
        return engine!!.also { it.calls.clear(); it.failAfter = Int.MAX_VALUE }
    }

    private fun spec(id: String, voice: String, lang: String, rate: Float = 1f, preset: ReadingPreset = ReadingPreset.READING) =
        GenerationSpec(id, "Teste $id", voice, voice, lang, rate, preset.id, 96)

    private fun run(spec: GenerationSpec, text: String): GenerationResult = runBlocking {
        val work = File(outDir, "work-${spec.audioId}").apply { deleteRecursively() }
        val out = File(outDir, "${spec.audioId}.mp3")
        val t0 = System.nanoTime()
        val r = GenerationPipeline(kokoro().let { engine!! }).run(spec, text, work, out) {}
        val secs = (System.nanoTime() - t0) / 1e9
        println("REAL[${spec.audioId}] voice=${spec.voiceId} segments=${r.segments} audio=${r.durationMs / 1000.0}s " +
            "wall=${"%.1f".format(secs)}s RTF=${"%.2f".format(secs / (r.durationMs / 1000.0))} size=${r.sizeBytes}B")
        r
    }

    @Test
    fun discoversVoicesForBothPriorityLanguages() = runBlocking {
        val voices = kokoro().voices()
        val pt = voices.filter { it.locale == "pt-BR" }.map { it.id }
        val gb = voices.filter { it.locale == "en-GB" }.map { it.id }
        println("pt-BR voices: $pt\nen-GB voices: $gb")
        assertEquals(54, voices.size)
        assertTrue(pt.containsAll(listOf("pf_dora", "pm_alex", "pm_santa")))
        assertTrue(gb.size == 8)
    }

    @Test
    fun shortPortugueseAllVoices() {
        val text = "Olá! Este é o Oralis. Ele lê o seu texto com fidelidade, respeitando acentuação, pontuação e parágrafos.\n\n" +
            "Segundo parágrafo: em 1922, a Semana de Arte Moderna mudou a cultura brasileira."
        for (v in listOf("pf_dora", "pm_alex", "pm_santa")) {
            val r = run(spec("pt_$v", v, "pt-BR"), text)
            assertTrue(Mp3Inspector.inspect(r.file).isValid)
            assertTrue("too short: ${r.durationMs}", r.durationMs in 8_000..30_000)
        }
    }

    @Test
    fun shortBritishEnglishVoices() {
        val text = "Good evening. This is a quiet reading in British English.\n\nThe history of cinema begins, arguably, in eighteen ninety-five."
        for (v in listOf("bf_emma", "bf_isabella", "bm_george", "bm_lewis")) {
            val r = run(spec("gb_$v", v, "en-GB"), text)
            assertTrue(Mp3Inspector.inspect(r.file).isValid)
            assertTrue(r.durationMs in 5_000..25_000)
        }
    }

    @Test
    fun speakingRateChangesDuration() {
        val text = "A velocidade da fala deve mudar de forma perceptível, sem distorcer a voz nem cortar palavras."
        val slow = run(spec("rate085", "pf_dora", "pt-BR", 0.85f), text)
        val fast = run(spec("rate125", "pf_dora", "pt-BR", 1.25f), text)
        assertTrue("slow=${slow.durationMs} fast=${fast.durationMs}", slow.durationMs > fast.durationMs * 1.25)
    }

    @Test
    fun interruptedRealGenerationResumesExactly() {
        val k = kokoro()
        val text = LongText.portuguese(minutes = 2)
        val sp = spec("resume", "pm_alex", "pt-BR")
        val work = File(outDir, "work-resume").apply { deleteRecursively() }
        val out = File(outDir, "resume.mp3")
        k.failAfter = 4
        val first = runCatching { runBlocking { GenerationPipeline(k).run(sp, text, work, out) {} } }
        assertTrue(first.exceptionOrNull() is CancellationException)
        val firstCalls = k.calls.toList()
        k.failAfter = Int.MAX_VALUE
        k.calls.clear()
        val r = runBlocking { GenerationPipeline(k).run(sp, text, work, out) {} }
        val plan = GenerationPipeline(k).loadOrCreatePlan(sp, text, work)
        val spoken = plan.segments.filter { it.speechText.isNotEmpty() }.map { it.speechText }
        assertEquals(spoken, firstCalls + k.calls)
        assertTrue(Mp3Inspector.inspect(r.file).isValid)
    }

    /** Long generation; length set with -Doralis.longMinutes (default 5). */
    @Test
    fun longPortugueseText() {
        val minutes = (System.getProperty("oralis.longMinutes") ?: System.getenv("ORALIS_LONG_MINUTES") ?: "5").toInt()
        val text = LongText.portuguese(minutes)
        File(outDir, "long_${minutes}min.txt").writeText(text)
        val rt = Runtime.getRuntime()
        val r = run(spec("long_${minutes}min", "pf_dora", "pt-BR", preset = ReadingPreset.LESSON), text)
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1_000_000
        println("JVM heap in use after long run: ${usedMb} MB")
        val report = Mp3Inspector.inspect(r.file)
        assertTrue(report.problem, report.isValid)
        assertTrue("duration ${r.durationMs}", r.durationMs > minutes * 60_000L * 0.6)
    }
}

/** Original Portuguese prose (written for these tests) used to build long inputs. */
object LongText {
    private val paragraphs = listOf(
        "A leitura em voz alta é uma das formas mais antigas de transmitir conhecimento. Antes da imprensa, os livros eram raros, e ouvir alguém ler era, para muitos, a única maneira de conhecer uma história, uma lei ou uma ideia.",
        "Quando escutamos um texto, prestamos atenção ao ritmo das frases, às pausas e à respiração de quem lê. Essas pequenas variações ajudam a compreender a estrutura do argumento, mesmo quando não estamos olhando para a página.",
        "Na filosofia, a clareza da exposição sempre foi um valor. Um raciocínio bem construído avança por etapas: apresenta o problema, examina as objeções e, só então, propõe uma resposta. Ouvir esse percurso exige paciência e algum silêncio.",
        "A história do cinema mostra como a técnica transforma a experiência estética. O som sincronizado, a cor e, mais tarde, a imagem digital não substituíram a narrativa; apenas ofereceram novos modos de contá-la.",
        "Na psicologia, a memória é descrita como um processo ativo de reconstrução. Lembramos menos do que imaginamos e reinterpretamos mais do que gostaríamos de admitir. Por isso, revisitar um texto em outro formato pode revelar detalhes antes despercebidos.",
        "Estudar ouvindo não é o mesmo que estudar lendo, mas pode ser um excelente complemento. Durante uma caminhada, no transporte ou antes de dormir, um áudio permite retomar conceitos e consolidar o que já foi aprendido.",
        "Os números também precisam soar naturais: em 1500, a frota de Cabral chegou ao litoral; em 1822, o país declarou sua independência; e, em 1988, uma nova Constituição foi promulgada após vinte e um anos de regime autoritário.",
        "Há textos que pedem uma leitura lenta. Poemas, ensaios e cartas guardam camadas de sentido que se revelam aos poucos, como uma paisagem que muda de cor ao entardecer. Nada disso precisa ser resumido ou reescrito para ser compreendido.",
    )

    /** About [minutes] of narration at ~155 words/min, with numbered sections (no exact repeats). */
    fun portuguese(minutes: Int): String {
        val targetWords = minutes * 155
        val sb = StringBuilder()
        var words = 0
        var i = 0
        while (words < targetWords) {
            val p = paragraphs[i % paragraphs.size]
            val section = "Seção ${i + 1}."
            sb.append(section).append("\n\n").append(p).append("\n\n")
            words += p.split(' ').size + 2
            i++
        }
        return sb.toString().trim()
    }
}
