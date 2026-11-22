package app.oralis.generation

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.oralis.OralisApp
import app.oralis.data.db.AudioStatus
import app.oralis.notifications.Notifications
import app.oralis.tts.ErrorMessages
import app.oralis.tts.SpeechEngineException
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Runs one generation as a foreground job.
 *
 * Why WorkManager + foreground (and not a plain Service): generation can take from minutes to
 * hours. WorkManager persists the request, re-runs it after process death or reboot, and its
 * foreground mode keeps the process alive and visible (notification with progress and a cancel
 * action). Combined with the pipeline's on-disk checkpoints, an interrupted job resumes where it
 * stopped. The service type is `mediaProcessing` on Android 15+ (the category for media
 * transcoding work) and `dataSync` on older versions, where `mediaProcessing` does not exist.
 */
class GenerationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container get() = (applicationContext as OralisApp).container
    private var lastNotify = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foreground(Notifications.generation(applicationContext, "Oralis", "Preparando…", null, id))

    private fun foreground(n: android.app.Notification): ForegroundInfo = when {
        Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(Notifications.ID_GENERATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ForegroundInfo(Notifications.ID_GENERATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else -> ForegroundInfo(Notifications.ID_GENERATION, n)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val audioId = inputData.getString(KEY_AUDIO_ID) ?: return@withContext Result.failure()
        val dao = container.database.audioDao()
        val repo = container.generationRepository
        val entity = dao.get(audioId) ?: return@withContext Result.failure()
        if (entity.status == AudioStatus.CANCELLED || entity.status == AudioStatus.READY) return@withContext Result.success()

        runCatching { setForeground(foreground(Notifications.generation(applicationContext, entity.title, "Preparando…", null, id))) }
        val wakeLock = (applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "oralis:generation").apply { acquire(6 * 60 * 60 * 1000L) }

        val started = SystemClock.elapsedRealtime()
        dao.update(entity.copy(status = AudioStatus.GENERATING, generationStartedAt = entity.generationStartedAt ?: System.currentTimeMillis(), errorKind = null, errorMessage = null))
        repo.publish { LiveGeneration(audioId, GenerationPhase.PREPARING, entity.segmentsDone, entity.segmentsTotal, 0f, started, null, 0, 0) }

        try {
            val text = File(entity.textPath).readText(Charsets.UTF_8)
            val spec = repo.spec(entity)
            notify(entity.title, GenerationPhase.LOADING_MODEL, 0, 0, 0f, force = true)
            repo.publish { it?.copy(phase = GenerationPhase.LOADING_MODEL) }

            val result = container.engineHolder.use { engine ->
                GenerationPipeline(engine).run(
                    spec = spec,
                    text = text,
                    workDir = repo.workDir(audioId),
                    output = repo.outputFile(audioId),
                    beforeSegment = { waitIfOverheated(audioId) },
                ) { p -> onProgress(audioId, entity.title, p) }
            }

            val finished = dao.get(audioId) ?: return@withContext Result.success()
            dao.update(
                finished.copy(
                    status = AudioStatus.READY,
                    filePath = result.file.absolutePath,
                    fileSizeBytes = result.sizeBytes,
                    durationMs = result.durationMs,
                    segmentsDone = result.segments,
                    segmentsTotal = result.segments,
                    generationFinishedAt = System.currentTimeMillis(),
                ),
            )
            repo.workDir(audioId).deleteRecursively()
            Notifications.generationDone(applicationContext, audioId, finished.title)
            Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (cancelledByUser()) {
                    dao.setStatus(audioId, AudioStatus.CANCELLED)
                    repo.workDir(audioId).deleteRecursively()
                } else {
                    // Stopped by the system (quota, timeout, constraints): WorkManager re-runs it
                    // later and the checkpoints are reused.
                    Log.i(TAG, "Generation stopped by the system; it will resume from its checkpoints")
                    dao.setStatus(audioId, AudioStatus.QUEUED)
                }
            }
            throw e
        } catch (e: SpeechEngineException) {
            Log.e(TAG, "Engine error ${e.kind}", e)
            fail(audioId, entity.title, e.kind.name, ErrorMessages.engine(e))
        } catch (e: GenerationFailure) {
            Log.e(TAG, "Generation failure ${e.kind}: ${e.message}")
            fail(audioId, entity.title, e.kind.name, ErrorMessages.generation(e))
        } catch (e: OutOfMemoryError) {
            fail(audioId, entity.title, "OUT_OF_MEMORY", "Memória insuficiente. Feche outros aplicativos e retome a geração: o progresso foi salvo.")
        } catch (e: java.io.IOException) {
            Log.e(TAG, "I/O error", e)
            val noSpace = e.message?.contains("ENOSPC") == true || e.message?.contains("No space", true) == true
            fail(
                audioId, entity.title, if (noSpace) "NO_SPACE" else "IO",
                if (noSpace) "Espaço de armazenamento insuficiente. Libere espaço e retome: o progresso foi salvo."
                else "Falha ao gravar arquivos. O progresso foi salvo; tente retomar.",
            )
        } finally {
            withContext(NonCancellable) {
                repo.clearLive(audioId)
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    /** True when the user cancelled (in-app or from the notification), false for system stops. */
    private fun cancelledByUser(): Boolean {
        val info = runCatching { WorkManager.getInstance(applicationContext).getWorkInfoById(id).get() }.getOrNull()
        return info?.state == WorkInfo.State.CANCELLED
    }

    private suspend fun fail(id: String, title: String, kind: String, message: String): Result {
        container.database.audioDao().setStatus(id, AudioStatus.FAILED, kind, message)
        Notifications.generationFailed(applicationContext, id, title, message)
        return Result.failure()
    }

    private suspend fun onProgress(audioId: String, title: String, p: GenerationProgress) {
        val repo = container.generationRepository
        repo.publish { cur ->
            val base = cur ?: LiveGeneration(audioId, p.phase, 0, 0, 0f, SystemClock.elapsedRealtime(), null, 0, 0)
            val synthStart = if (p.phase == GenerationPhase.SYNTHESIZING && base.synthStartedAtElapsed == null) SystemClock.elapsedRealtime() else base.synthStartedAtElapsed
            val doneAtStart = if (base.synthStartedAtElapsed == null && p.phase == GenerationPhase.SYNTHESIZING) p.segmentsDone else base.segmentsDoneAtSynthStart
            base.copy(
                phase = p.phase, segmentsDone = p.segmentsDone, segmentsTotal = p.segmentsTotal,
                encodeFraction = p.encodeFraction, synthStartedAtElapsed = synthStart,
                segmentsDoneAtSynthStart = doneAtStart,
                audioMsSynthesized = maxOf(base.audioMsSynthesized, p.audioMsSynthesized),
                thermalPause = false,
            )
        }
        if (p.phase == GenerationPhase.SYNTHESIZING) container.database.audioDao().setProgress(audioId, p.segmentsDone, p.segmentsTotal)
        notify(title, p.phase, p.segmentsDone, p.segmentsTotal, p.encodeFraction)
    }

    private suspend fun notify(title: String, phase: GenerationPhase, done: Int, total: Int, encode: Float, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 1000) return
        lastNotify = now
        val (text, fraction) = when (phase) {
            GenerationPhase.LOADING_MODEL -> "Inicializando o modelo de voz…" to null
            GenerationPhase.PROCESSING_TEXT -> "Processando o texto…" to null
            GenerationPhase.SYNTHESIZING -> "Gerando áudio · trecho $done de $total" to (if (total > 0) done.toFloat() / total else null)
            GenerationPhase.ENCODING -> "Codificando MP3…" to encode
            GenerationPhase.FINALIZING -> "Finalizando o arquivo…" to null
            else -> "Preparando…" to null
        }
        runCatching { setForeground(foreground(Notifications.generation(applicationContext, title, text, fraction, id))) }
    }

    /** Pauses between segments while the device is critically hot, then continues. */
    private suspend fun waitIfOverheated(audioId: String) {
        val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        var paused = false
        while (pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
            if (!paused) {
                paused = true
                Log.w(TAG, "Thermal status ${pm.currentThermalStatus}: pausing generation")
                container.generationRepository.publish { it?.copy(thermalPause = true) }
                runCatching {
                    setForeground(foreground(Notifications.generation(applicationContext, "Oralis", "Pausado: o aparelho está quente. Retoma sozinho.", null, id)))
                }
            }
            delay(15_000)
        }
        if (paused) container.generationRepository.publish { if (it?.audioId == audioId) it.copy(thermalPause = false) else it }
    }

    companion object {
        const val KEY_AUDIO_ID = "audio_id"
        private const val TAG = "OralisGeneration"
    }
}
