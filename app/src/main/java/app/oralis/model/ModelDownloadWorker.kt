package app.oralis.model

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.oralis.OralisApp
import app.oralis.notifications.Notifications
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Downloads the selected Kokoro bundle as a foreground (dataSync) job so it continues when the
 * user leaves the app. Supports resuming via HTTP Range requests; bytes already on disk are kept
 * when paused, cancelled by the system or interrupted by a network error.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val repo get() = (applicationContext as OralisApp).container.modelRepository

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0f, true)

    private fun foregroundInfo(fraction: Float, indeterminate: Boolean): ForegroundInfo {
        val n = Notifications.modelDownload(applicationContext, fraction, indeterminate, id)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifications.ID_MODEL, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_MODEL, n)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val variant = ModelVariant.fromId(inputData.getString(KEY_VARIANT))
        runCatching { setForeground(foregroundInfo(0f, true)) }
        val part = repo.partFile(variant)
        try {
            download(variant, part)
            if (part.length() != variant.sizeBytes) {
                repo.reportFailure(variant, ModelError.NETWORK)
                return@withContext Result.failure()
            }
            setForeground(foregroundInfo(1f, true))
            val ok = repo.verifyAndInstall(variant)
            if (ok) Result.success() else Result.failure()
        } catch (e: CancellationException) {
            repo.reportPaused(variant)
            throw e
        } catch (e: UnknownHostException) {
            Log.w(TAG, "No network: ${e.message}")
            retryOrFail(variant, ModelError.NO_NETWORK)
        } catch (e: ServerException) {
            Log.w(TAG, "Server error ${e.code}")
            retryOrFail(variant, ModelError.SERVER)
        } catch (e: IOException) {
            Log.w(TAG, "Download interrupted: ${e.javaClass.simpleName} ${e.message}")
            if (e.message?.contains("ENOSPC") == true) {
                repo.reportFailure(variant, ModelError.NO_SPACE)
                Result.failure()
            } else {
                retryOrFail(variant, ModelError.NETWORK)
            }
        }
    }

    private fun retryOrFail(variant: ModelVariant, error: ModelError): Result {
        return if (runAttemptCount < MAX_ATTEMPTS) {
            repo.reportPaused(variant)
            Result.retry()
        } else {
            repo.reportFailure(variant, error)
            Result.failure()
        }
    }

    private class ServerException(val code: Int) : IOException("HTTP $code")

    private suspend fun download(variant: ModelVariant, part: File) {
        var existing = if (part.isFile) part.length() else 0L
        if (existing > variant.sizeBytes) {
            part.delete(); existing = 0
        }
        if (existing == variant.sizeBytes) return

        val conn = (URL(variant.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Oralis-Android")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val code = conn.responseCode
            val append = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false // server ignored Range: restart from zero
                416 -> { part.delete(); throw IOException("Range not satisfiable") }
                else -> throw ServerException(code)
            }
            if (!append) existing = 0
            val total = variant.sizeBytes
            var bytes = existing
            var lastReport = 0L
            var windowStart = SystemClock.elapsedRealtime()
            var windowBytes = 0L
            var bps = 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val r = input.read(buf)
                        if (r < 0) break
                        if (bytes + r > total) throw IOException("More data than expected")
                        out.write(buf, 0, r)
                        bytes += r
                        windowBytes += r
                        val now = SystemClock.elapsedRealtime()
                        if (now - windowStart >= 1000) {
                            bps = windowBytes * 1000 / (now - windowStart)
                            windowStart = now
                            windowBytes = 0
                        }
                        if (now - lastReport >= 300) {
                            lastReport = now
                            repo.reportDownloading(variant, bytes, total, bps)
                            runCatching { setForeground(foregroundInfo(bytes.toFloat() / total, false)) }
                        }
                    }
                    out.fd.sync()
                }
            }
            repo.reportDownloading(variant, bytes, total, bps)
        } catch (e: InterruptedIOException) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val KEY_VARIANT = "variant"
        private const val TAG = "OralisDownload"
        private const val MAX_ATTEMPTS = 5
    }
}
