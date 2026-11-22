package app.oralis.model

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Owns the Kokoro model file: install state, download orchestration, integrity verification and
 * removal. A model is only reported as [ModelState.Installed] after its SHA-256 matched the
 * official value; the result is cached in a small marker file (size + mtime + hash) so the 295 MB
 * file is not re-hashed on every launch, and the user can force a re-check at any time.
 */
class ModelRepository(private val context: Context, private val scope: CoroutineScope) {

    private val dir = File(context.filesDir, "model").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<ModelState>(ModelState.Checking)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    @Serializable
    private data class Marker(val variant: String, val size: Long, val lastModified: Long, val sha256: String)

    fun modelFile(variant: ModelVariant) = File(dir, variant.fileName)
    fun partFile(variant: ModelVariant) = File(dir, variant.fileName + ".part")
    private fun markerFile(variant: ModelVariant) = File(dir, variant.fileName + ".verified.json")

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    /** Re-evaluates the state from disk (cheap unless an unverified file must be hashed). */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (_state.value is ModelState.Downloading || _state.value is ModelState.Verifying) return@withContext
        for (variant in ModelVariant.entries) {
            val file = modelFile(variant)
            if (!file.isFile) continue
            val marker = readMarker(variant)
            if (marker != null && marker.size == file.length() && marker.lastModified == file.lastModified() &&
                marker.sha256 == variant.sha256
            ) {
                _state.value = ModelState.Installed(variant, file.absolutePath)
                return@withContext
            }
            // Present but not verified (e.g. interrupted before the marker was written).
            if (verify(variant)) return@withContext
        }
        val partial = ModelVariant.entries.firstOrNull { partFile(it).isFile }
        _state.value = if (partial != null) {
            ModelState.Paused(partial, partFile(partial).length(), partial.sizeBytes)
        } else {
            ModelState.NotInstalled
        }
    }

    fun installedVariant(): ModelVariant? = (state.value as? ModelState.Installed)?.variant

    fun availableBytes(): Long = StatFs(context.filesDir.absolutePath).availableBytes

    fun hasSpaceFor(variant: ModelVariant): Boolean {
        val remaining = variant.sizeBytes - partFile(variant).let { if (it.isFile) it.length() else 0L }
        return availableBytes() > remaining + SAFETY_MARGIN
    }

    fun startDownload(variant: ModelVariant) {
        if (!hasSpaceFor(variant)) {
            _state.value = ModelState.Failed(variant, ModelError.NO_SPACE)
            return
        }
        // Only one variant is kept: discard partial downloads of the other one.
        ModelVariant.entries.filter { it != variant }.forEach { partFile(it).delete() }
        _state.value = ModelState.Downloading(variant, partFile(variant).let { if (it.isFile) it.length() else 0 }, variant.sizeBytes)
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_VARIANT to variant.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Stops the transfer but keeps the bytes already downloaded. */
    fun pauseDownload() {
        val s = state.value
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        if (s is ModelState.Downloading) _state.value = ModelState.Paused(s.variant, partFile(s.variant).length(), s.total)
    }

    /** Stops the transfer and discards partial data. */
    fun cancelDownload() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        ModelVariant.entries.forEach { partFile(it).delete() }
        _state.value = ModelState.NotInstalled
    }

    fun deleteModel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        dir.listFiles()?.forEach { it.delete() }
        _state.value = ModelState.NotInstalled
    }

    // ---- Called by ModelDownloadWorker (same process) ----

    internal fun reportDownloading(variant: ModelVariant, bytes: Long, total: Long, bps: Long) {
        _state.value = ModelState.Downloading(variant, bytes, total, bps)
    }

    internal fun reportFailure(variant: ModelVariant, error: ModelError) {
        val part = partFile(variant)
        _state.value = ModelState.Failed(variant, error, if (part.isFile) part.length() else 0)
    }

    internal fun reportPaused(variant: ModelVariant) {
        _state.value = ModelState.Paused(variant, partFile(variant).length(), variant.sizeBytes)
    }

    /**
     * Verifies [variant]'s file (or its completed .part) against the official SHA-256 and, on
     * success, atomically installs it. Returns true when installed.
     */
    internal suspend fun verifyAndInstall(variant: ModelVariant): Boolean = withContext(Dispatchers.IO) {
        val part = partFile(variant)
        val target = modelFile(variant)
        _state.value = ModelState.Verifying(variant)
        val hash = sha256(part)
        if (hash != variant.sha256) {
            Log.w(TAG, "SHA-256 mismatch for ${variant.fileName}")
            part.delete()
            _state.value = ModelState.Failed(variant, ModelError.CORRUPTED)
            return@withContext false
        }
        ModelVariant.entries.filter { it != variant }.forEach { modelFile(it).delete(); markerFile(it).delete() }
        target.delete()
        if (!part.renameTo(target)) {
            _state.value = ModelState.Failed(variant, ModelError.UNKNOWN)
            return@withContext false
        }
        writeMarker(variant, target, hash)
        _state.value = ModelState.Installed(variant, target.absolutePath)
        true
    }

    /** Full integrity re-check of an installed model (user-triggered or unverified file). */
    suspend fun verify(variant: ModelVariant = installedVariant() ?: ModelVariant.DEFAULT): Boolean = withContext(Dispatchers.IO) {
        val file = modelFile(variant)
        if (!file.isFile) {
            _state.value = ModelState.NotInstalled
            return@withContext false
        }
        _state.value = ModelState.Verifying(variant)
        val hash = sha256(file)
        if (hash == variant.sha256 && file.length() == variant.sizeBytes) {
            writeMarker(variant, file, hash)
            _state.value = ModelState.Installed(variant, file.absolutePath)
            true
        } else {
            file.delete()
            markerFile(variant).delete()
            _state.value = ModelState.Failed(variant, ModelError.CORRUPTED)
            false
        }
    }

    private fun readMarker(variant: ModelVariant): Marker? = runCatching {
        json.decodeFromString(Marker.serializer(), markerFile(variant).readText())
    }.getOrNull()

    private fun writeMarker(variant: ModelVariant, file: File, hash: String) {
        markerFile(variant).writeText(
            json.encodeToString(Marker.serializer(), Marker(variant.id, file.length(), file.lastModified(), hash)),
        )
    }

    companion object {
        private const val TAG = "OralisModel"
        const val WORK_NAME = "model-download"
        const val WORK_TAG = "model"
        const val SAFETY_MARGIN = 64L * 1024 * 1024

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    md.update(buf, 0, r)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
