package app.oralis.generation

import android.content.Context
import android.os.StatFs
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.oralis.core.text.TextAnalysis
import app.oralis.data.db.AudioDao
import app.oralis.data.db.AudioEntity
import app.oralis.data.db.AudioStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Live, in-memory progress of the running generation (the DB holds coarse checkpoints). */
data class LiveGeneration(
    val audioId: String,
    val phase: GenerationPhase,
    val segmentsDone: Int,
    val segmentsTotal: Int,
    val encodeFraction: Float,
    val startedAtElapsed: Long,
    val synthStartedAtElapsed: Long?,
    val segmentsDoneAtSynthStart: Int,
    val audioMsSynthesized: Long,
    val thermalPause: Boolean = false,
)

class StorageException(val neededBytes: Long, val availableBytes: Long) : Exception("Insufficient storage")

/**
 * Creates library entries and schedules generations. Generations run one at a time, in order,
 * as a WorkManager chain (unique work "generation-queue", APPEND_OR_REPLACE), so several texts can
 * be queued and each one survives process death: WorkManager re-runs the worker and the pipeline
 * resumes from its on-disk checkpoints.
 */
class GenerationRepository(private val context: Context, private val dao: AudioDao) {

    private val _live = MutableStateFlow<LiveGeneration?>(null)
    val live: StateFlow<LiveGeneration?> = _live.asStateFlow()

    val textsDir = File(context.filesDir, "texts").apply { mkdirs() }
    val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
    val workRoot = File(context.filesDir, "work").apply { mkdirs() }

    fun workDir(id: String) = File(workRoot, id)
    fun outputFile(id: String) = File(audioDir, "$id.mp3")

    suspend fun start(
        title: String,
        text: String,
        languageTag: String,
        voiceId: String,
        voiceName: String,
        speakingRate: Float,
        preset: ReadingPreset,
        bitrateKbps: Int,
        stripMarkdown: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val stats = TextAnalysis.stats(text)
        val estimate = TextAnalysis.estimateDurationMs(stats, languageTag, speakingRate, preset.paragraphGapMs)
        val needed = GenerationPipeline.estimateStorageBytes(estimate, bitrateKbps)
        val available = StatFs(context.filesDir.absolutePath).availableBytes
        if (available < needed) throw StorageException(needed, available)

        val id = UUID.randomUUID().toString()
        val textFile = File(textsDir, "$id.txt")
        textFile.writeText(text, Charsets.UTF_8) // verbatim copy of the user's text
        dao.insert(
            AudioEntity(
                id = id,
                title = title.ifBlank { defaultTitle(text) },
                languageTag = languageTag,
                voiceId = voiceId,
                voiceName = voiceName,
                speakingRate = speakingRate,
                presetId = preset.id,
                bitrateKbps = bitrateKbps,
                createdAt = System.currentTimeMillis(),
                status = AudioStatus.QUEUED,
                textPath = textFile.absolutePath,
                charCount = stats.characters,
                wordCount = stats.words,
                estimatedDurationMs = estimate,
            ),
        )
        writeSpecFlag(id, stripMarkdown)
        enqueue(id)
        id
    }

    /** Resumes an interrupted/failed generation, reusing completed segments. */
    suspend fun resume(id: String) {
        dao.setStatus(id, AudioStatus.QUEUED)
        enqueue(id)
    }

    private fun enqueue(id: String) {
        val request = OneTimeWorkRequestBuilder<GenerationWorker>()
            .setInputData(workDataOf(GenerationWorker.KEY_AUDIO_ID to id))
            .addTag(TAG_GENERATION)
            .addTag(tagFor(id))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    suspend fun cancel(id: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(tagFor(id))
        dao.setStatus(id, AudioStatus.CANCELLED)
        if (_live.value?.audioId == id) _live.value = null
        withContext(Dispatchers.IO) {
            workDir(id).deleteRecursively()
            File(audioDir, "$id.mp3.part").delete()
        }
    }

    /**
     * Called at app start: entries left in QUEUED/GENERATING without pending work (e.g. the app was
     * force-stopped) are marked INTERRUPTED so the user can resume them.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val infos = WorkManager.getInstance(context).getWorkInfosByTag(TAG_GENERATION).get()
        val pending = infos.filter { !it.state.isFinished }.flatMap { it.tags }.toSet()
        for (a in dao.active()) {
            if (tagFor(a.id) !in pending) dao.setStatus(a.id, AudioStatus.INTERRUPTED)
        }
        // Remove work folders of items that no longer need them.
        workRoot.listFiles()?.forEach { dir ->
            val e = dao.get(dir.name)
            if (e == null || e.status == AudioStatus.READY || e.status == AudioStatus.CANCELLED) dir.deleteRecursively()
        }
    }

    fun isRunning(id: String): Boolean = WorkManager.getInstance(context).getWorkInfosByTag(tagFor(id)).get()
        .any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

    fun spec(entity: AudioEntity): GenerationSpec = GenerationSpec(
        audioId = entity.id,
        title = entity.title,
        voiceId = entity.voiceId,
        voiceName = entity.voiceName,
        languageTag = entity.languageTag,
        speakingRate = entity.speakingRate,
        presetId = entity.presetId,
        bitrateKbps = entity.bitrateKbps,
        stripMarkdown = readSpecFlag(entity.id),
    )

    // The Markdown option is part of the plan; persisted beside the text to keep the DB schema small.
    private fun writeSpecFlag(id: String, strip: Boolean) = File(textsDir, "$id.opts").writeText(if (strip) "md=strip" else "md=keep")
    private fun readSpecFlag(id: String) = runCatching { File(textsDir, "$id.opts").readText() != "md=keep" }.getOrDefault(true)

    // ---- live progress (worker → UI) ----
    internal fun publish(update: (LiveGeneration?) -> LiveGeneration?) = _live.update(update)
    internal fun clearLive(id: String) = _live.update { if (it?.audioId == id) null else it }

    fun deleteFiles(entity: AudioEntity) {
        entity.filePath?.let { File(it).delete() }
        File(entity.textPath).delete()
        File(textsDir, "${entity.id}.opts").delete()
        workDir(entity.id).deleteRecursively()
    }

    companion object {
        const val QUEUE_NAME = "generation-queue"
        const val TAG_GENERATION = "generation"
        fun tagFor(id: String) = "gen-$id"

        fun defaultTitle(text: String): String {
            val firstLine = text.lineSequence().map { it.trim().trimStart('#', ' ', '-', '*') }.firstOrNull { it.isNotBlank() } ?: "Áudio"
            return if (firstLine.length <= 60) firstLine else firstLine.take(57).trimEnd() + "…"
        }
    }
}
