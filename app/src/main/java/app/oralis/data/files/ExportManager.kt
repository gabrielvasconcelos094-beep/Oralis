package app.oralis.data.files

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.oralis.data.db.AudioDao
import app.oralis.data.db.AudioEntity
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exports and shares MP3 files without any storage permission:
 *  - Downloads: MediaStore (scoped storage), into "Download/Oralis".
 *  - Any folder: Storage Access Framework document chosen by the user.
 *  - Share / open with: FileProvider content URIs with a temporary read grant.
 * Exported copies are independent files: they keep working if the app is uninstalled.
 */
class ExportManager(private val context: Context, private val dao: AudioDao) {

    fun fileName(audio: AudioEntity): String {
        val safe = audio.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(80)
        return (safe.ifBlank { "Oralis" }) + ".mp3"
    }

    private fun source(audio: AudioEntity): File {
        val f = audio.filePath?.let(::File)
        if (f == null || !f.isFile) throw FileNotFoundException("Audio file missing")
        return f
    }

    /** Returns a human-readable location, e.g. "Download/Oralis/Aula 3.mp3". */
    suspend fun saveToDownloads(audio: AudioEntity): String = withContext(Dispatchers.IO) {
        val src = source(audio)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName(audio))
            put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Oralis")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out, 1 shl 16) } }
                ?: throw java.io.IOException("Cannot open output")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        dao.setExportUri(audio.id, uri.toString())
        val name = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: fileName(audio)
        "Download/Oralis/$name"
    }

    /** Copies the MP3 into a document created by the user through the system file picker. */
    suspend fun exportTo(audio: AudioEntity, target: Uri) = withContext(Dispatchers.IO) {
        val src = source(audio)
        context.contentResolver.openOutputStream(target, "w")?.use { out ->
            src.inputStream().use { it.copyTo(out, 1 shl 16) }
        } ?: throw java.io.IOException("Cannot open destination")
        dao.setExportUri(audio.id, target.toString())
    }

    private fun contentUri(audio: AudioEntity): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", source(audio), fileName(audio))

    fun shareIntent(audio: AudioEntity): Intent {
        val uri = contentUri(audio)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, audio.title)
            clipData = android.content.ClipData.newRawUri(audio.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Compartilhar áudio")
    }

    fun openWithIntent(audio: AudioEntity): Intent {
        val uri = contentUri(audio)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/mpeg")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(view, "Abrir com")
    }
}
