package app.oralis.generation

import app.oralis.audio.mp3.Mp3FileWriter
import app.oralis.audio.mp3.Mp3Inspector
import app.oralis.audio.mp3.Mp3Metadata
import app.oralis.audio.pcm.PcmSegmentStore
import app.oralis.core.text.BreakKind
import app.oralis.core.text.SpeechTextPreparer
import app.oralis.core.text.TextSegment
import app.oralis.core.text.TextSegmenter
import app.oralis.tts.SpeechEngine
import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything needed to (re)run a generation; persisted next to the work files. */
@Serializable
data class GenerationSpec(
    val audioId: String,
    val title: String,
    val voiceId: String,
    val voiceName: String,
    val languageTag: String,
    val speakingRate: Float,
    val presetId: String,
    val bitrateKbps: Int,
    val stripMarkdown: Boolean = true,
)

@Serializable
data class PlannedSegment(val index: Int, val start: Int, val end: Int, val breakBefore: BreakKind, val speechText: String)

@Serializable
data class GenerationPlan(val spec: GenerationSpec, val textLength: Int, val textHash: String, val segments: List<PlannedSegment>)

enum class GenerationPhase { PREPARING, LOADING_MODEL, PROCESSING_TEXT, SYNTHESIZING, ENCODING, FINALIZING, DONE }

data class GenerationProgress(
    val phase: GenerationPhase,
    val segmentsDone: Int = 0,
    val segmentsTotal: Int = 0,
    /** 0..1 within ENCODING. */
    val encodeFraction: Float = 0f,
    val audioMsSynthesized: Long = 0,
)

data class GenerationResult(val file: File, val durationMs: Long, val sizeBytes: Long, val segments: Int)

class GenerationFailure(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { NO_SPEAKABLE_TEXT, INSUFFICIENT_STORAGE, ENCODING_FAILED, VALIDATION_FAILED, TEXT_CHANGED }
}

/**
 * Text → segments → Kokoro → PCM segment files (checkpoints) → one MP3 file.
 *
 * - Synthesis is strictly sequential and in original order.
 * - Each finished segment is persisted atomically; a resumed run skips segments already on disk,
 *   so no segment is synthesized twice into the output and none is skipped.
 * - Audio never accumulates in RAM: only one segment (~40 s of samples) is held at a time, and
 *   the MP3 is encoded by streaming the PCM files block by block.
 * - The MP3 is written to "<name>.part", validated frame-by-frame and only then renamed.
 */
class GenerationPipeline(
    private val engine: SpeechEngine,
    private val segmenterMaxChars: Int = TextSegmenter.DEFAULT_MAX_CHARS,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun planFile(workDir: File) = File(workDir, "plan.json")

    fun loadOrCreatePlan(spec: GenerationSpec, text: String, workDir: File): GenerationPlan {
        val hash = sha256(text)
        val file = planFile(workDir)
        if (file.isFile) {
            val existing = runCatching { json.decodeFromString(GenerationPlan.serializer(), file.readText()) }.getOrNull()
            if (existing != null && existing.textHash == hash && existing.spec == spec) return existing
            // Different text or settings: previous checkpoints are not reusable.
            workDir.listFiles()?.filter { it.name.startsWith("seg_") }?.forEach { it.delete() }
        }
        val segments = TextSegmenter(segmenterMaxChars, SpeechTextPreparer(spec.stripMarkdown)).segment(text)
        val plan = GenerationPlan(spec, text.length, hash, segments.map { it.toPlanned() })
        workDir.mkdirs()
        val tmp = File(workDir, "plan.json.tmp")
        tmp.writeText(json.encodeToString(GenerationPlan.serializer(), plan))
        if (!tmp.renameTo(file)) throw IOException("Cannot write plan")
        return plan
    }

    suspend fun run(
        spec: GenerationSpec,
        text: String,
        workDir: File,
        output: File,
        beforeSegment: suspend () -> Unit = {},
        onProgress: suspend (GenerationProgress) -> Unit,
    ): GenerationResult {
        onProgress(GenerationProgress(GenerationPhase.PROCESSING_TEXT))
        val plan = loadOrCreatePlan(spec, text, workDir)
        val total = plan.segments.size
        if (plan.segments.none { it.speechText.isNotEmpty() }) {
            throw GenerationFailure(GenerationFailure.Kind.NO_SPEAKABLE_TEXT, "Text has nothing to read")
        }
        val store = PcmSegmentStore(workDir)
        val preset = ReadingPreset.fromId(spec.presetId)

        // ---- Synthesis (resumable) ----
        var done = store.completedCount(total)
        var audioMs = 0L
        onProgress(GenerationProgress(GenerationPhase.SYNTHESIZING, done, total))
        for (seg in plan.segments) {
            currentCoroutineContext().ensureActive()
            if (store.isComplete(seg.index)) continue
            beforeSegment()
            val samples = if (seg.speechText.isEmpty()) {
                FloatArray(0)
            } else {
                engine.synthesize(seg.speechText, spec.voiceId, spec.languageTag, spec.speakingRate).samples
            }
            currentCoroutineContext().ensureActive()
            store.write(seg.index, samples)
            done++
            audioMs += samples.size * 1000L / engine.sampleRateHz
            onProgress(GenerationProgress(GenerationPhase.SYNTHESIZING, done, total, audioMsSynthesized = audioMs))
        }
        check(store.completedCount(total) == total) { "Missing segments after synthesis" }

        // ---- Encoding ----
        onProgress(GenerationProgress(GenerationPhase.ENCODING, total, total, 0f))
        val sampleRate = engine.sampleRateHz
        val gain = loudnessGain(store, total)
        val part = File(output.parentFile, output.name + ".part")
        var expectedSamples = 0L
        try {
            Mp3FileWriter(part, sampleRate, spec.bitrateKbps, Mp3Metadata(title = spec.title, artist = "Oralis · ${spec.voiceName}")).use { writer ->
                val scaled = ShortArray(PcmSegmentStore.CHUNK)
                for (seg in plan.segments) {
                    currentCoroutineContext().ensureActive()
                    val pause = pauseSamples(seg.breakBefore, preset, sampleRate)
                    writer.writeSilence(pause)
                    expectedSamples += pause
                    store.forEachBlock(seg.index) { block, n ->
                        for (k in 0 until n) {
                            scaled[k] = (block[k] * gain).toInt().coerceIn(-32768, 32767).toShort()
                        }
                        writer.write(scaled, n)
                    }
                    expectedSamples += store.sampleCount(seg.index)
                    onProgress(GenerationProgress(GenerationPhase.ENCODING, total, total, (seg.index + 1f) / total))
                }
                // A short tail so the last word is never clipped by players.
                val tail = sampleRate * 600L / 1000
                writer.writeSilence(tail)
                expectedSamples += tail
                writer.finish()
            }
        } catch (e: IOException) {
            part.delete()
            if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true) {
                throw GenerationFailure(GenerationFailure.Kind.INSUFFICIENT_STORAGE, "No space left", e)
            }
            throw GenerationFailure(GenerationFailure.Kind.ENCODING_FAILED, e.message ?: "MP3 encoding failed", e)
        }

        // ---- Validation & finalization ----
        onProgress(GenerationProgress(GenerationPhase.FINALIZING, total, total, 1f))
        val report = Mp3Inspector.inspect(part)
        val expectedMs = expectedSamples * 1000 / sampleRate
        if (!report.isValid || kotlin.math.abs(report.durationMs - expectedMs) > 100) {
            part.delete()
            throw GenerationFailure(
                GenerationFailure.Kind.VALIDATION_FAILED,
                "MP3 validation failed: ${report.problem ?: "duration ${report.durationMs} != $expectedMs"}",
            )
        }
        if (output.exists()) output.delete()
        if (!part.renameTo(output)) throw GenerationFailure(GenerationFailure.Kind.ENCODING_FAILED, "Cannot finalize MP3")
        onProgress(GenerationProgress(GenerationPhase.DONE, total, total, 1f))
        return GenerationResult(output, report.durationMs, output.length(), total)
    }

    private fun pauseSamples(kind: BreakKind, preset: ReadingPreset, sampleRate: Int): Long =
        preset.extraPauseMs(kind) * sampleRate / 1000

    /**
     * Kokoro renders speech at roughly -25 dBFS RMS with peaks around -6 dBFS. A single, uniform
     * gain (same for the whole file, so relative dynamics are untouched) brings it closer to
     * spoken-word loudness, capped so the loudest peak stays below -1 dBFS.
     */
    private fun loudnessGain(store: PcmSegmentStore, total: Int): Float {
        var peak = 0f
        for (i in 0 until total) peak = maxOf(peak, store.peak(i))
        if (peak <= 0.0001f) return 1f
        return minOf(MAX_GAIN, PEAK_CEILING / peak).coerceAtLeast(1f)
    }

    companion object {
        const val MAX_GAIN = 1.8f
        const val PEAK_CEILING = 0.89f

        fun sha256(text: String): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            return md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        /** Upper bound of temporary + final storage a generation needs. */
        fun estimateStorageBytes(estimatedDurationMs: Long, bitrateKbps: Int, sampleRate: Int = 24_000): Long {
            val seconds = estimatedDurationMs / 1000.0 * 1.3 // margin for estimate error
            val pcm = seconds * sampleRate * 2
            val mp3 = seconds * bitrateKbps * 1000 / 8
            return (pcm + mp3).toLong() + 20L * 1024 * 1024
        }
    }
}

private fun TextSegment.toPlanned() = PlannedSegment(index, start, end, breakBefore, speechText)
